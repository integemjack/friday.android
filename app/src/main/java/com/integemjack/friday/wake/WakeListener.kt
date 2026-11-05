package com.integemjack.friday.wake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一直听「星期五」的那条线程：读麦克风（100 毫秒一块）→ 记能量 → 静音门限 → 有人说话才喂模型 →
 * 认出「星期五」且前面停过一下 → [onWake]（在这条线程上调，调用方自己切线程）。
 *
 * - [busy]：手机在念播报、在打电话时为 true——照样读（麦克风不放），但不认，门限从头来
 * - 叫醒一次以后 [WakeTuning.COOLDOWN_MS] 里不再叫
 * - 模型加载一次留着（停了再开不用重新加载）；[release] 才放掉
 */
class WakeListener(
    private val engineFactory: () -> KeywordEngine,
    private val micFactory: () -> MicSource,
    private val busy: () -> Boolean,
    private val onWake: () -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val retryMs: Long = 5_000,
) {
    sealed interface Status {
        data object Stopped : Status
        data object Starting : Status
        data object Listening : Status
        /** 麦克风开不了（被别的 App 占着之类）：过一会儿再试 */
        data class MicFailed(val message: String) : Status
        /** 这台手机跑不了唤醒模型 */
        data class Unsupported(val message: String) : Status
    }

    /** 最近一次认出来的词，和叫没叫醒（设置页上给人看：说了没反应时知道是没听见还是被规则挡了）；[atMs] 是墙上时间 */
    data class Heard(val keyword: String, val woke: Boolean, val reason: String?, val atMs: Long)

    private val _status = MutableStateFlow<Status>(Status.Stopped)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _heard = MutableStateFlow<Heard?>(null)
    val heard: StateFlow<Heard?> = _heard.asStateFlow()

    private val lock = Object()
    private var thread: Thread? = null
    @Volatile private var stopping = false
    private var engine: KeywordEngine? = null
    private var cooldownUntil = 0L

    val running: Boolean get() = synchronized(lock) { thread?.isAlive == true }

    fun start() = synchronized(lock) {
        if (thread?.isAlive == true) return
        if (_status.value is Status.Unsupported) return
        stopping = false
        _status.value = Status.Starting
        thread = Thread({ run() }, "friday-wake").apply {
            isDaemon = true
            start()
        }
    }

    /** 停下、放掉麦克风（等那条线程收尾，最多一秒多） */
    fun stop() {
        val t = synchronized(lock) {
            stopping = true
            thread.also { thread = null }
        }
        t?.join(1_500)
        if (_status.value !is Status.Unsupported) _status.value = Status.Stopped
    }

    /** 不再用了：停下并放掉模型 */
    fun release() {
        stop()
        synchronized(lock) {
            engine?.release()
            engine = null
        }
    }

    private fun run() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
        val engine = synchronized(lock) {
            engine ?: try {
                engineFactory().also { engine = it }
            } catch (e: EngineUnavailable) {
                _status.value = Status.Unsupported(e.message ?: "这台手机跑不了唤醒模型")
                return
            } catch (e: Throwable) {
                _status.value = Status.Unsupported("唤醒模型加载不了（${e.message ?: e.javaClass.simpleName}）")
                return
            }
        }
        while (!stopping) {
            val mic = micFactory()
            if (!mic.start()) {
                _status.value = Status.MicFailed("麦克风开不了（可能被别的 App 占着），过一会儿再试")
                sleep(retryMs)
                continue
            }
            _status.value = Status.Listening
            try {
                listen(engine, mic)
            } finally {
                mic.stop()
            }
            if (!stopping) {
                // 读出错（麦克风被系统收走了之类）：过一会儿重开
                _status.value = Status.MicFailed("麦克风断了，过一会儿再试")
                sleep(retryMs)
            }
        }
    }

    private class Chunk(val start: Long, val data: FloatArray)

    private fun listen(engine: KeywordEngine, mic: MicSource) {
        val gate = EnergyGate()
        val history = LevelHistory()
        val preRoll = ArrayDeque<Chunk>()
        val buffer = FloatArray(WakeTuning.CHUNK)
        var sample = 0L
        var segmentStart = 0L
        var errors = 0

        fun feed(chunk: Chunk) {
            val word = engine.accept(chunk.data) ?: return
            val (woke, reason) = decide(word, segmentStart, history)
            engine.newSegment()
            segmentStart = chunk.start + chunk.data.size
            _heard.value = Heard(word.keyword, woke, reason, System.currentTimeMillis())
            if (woke) {
                cooldownUntil = clock() + WakeTuning.COOLDOWN_MS
                gate.reset()
                preRoll.clear()
                onWake()
            }
        }

        while (!stopping) {
            val n = mic.read(buffer)
            if (n <= 0) {
                if (++errors >= 5) return
                continue
            }
            errors = 0
            val chunk = Chunk(sample, buffer.copyOf(n))
            sample += n
            history.push(chunk.data)
            if (busy()) {
                gate.reset()
                preRoll.clear()
                continue
            }
            when (gate.push(levelDb(chunk.data))) {
                EnergyGate.Step.Idle -> Unit
                EnergyGate.Step.Start -> {
                    engine.newSegment()
                    segmentStart = preRoll.firstOrNull()?.start ?: chunk.start
                    for (earlier in preRoll.toList()) feed(earlier)
                    feed(chunk)
                }
                EnergyGate.Step.Feed, EnergyGate.Step.End -> feed(chunk)
            }
            preRoll.addLast(chunk)
            while (preRoll.size > WakeTuning.PRE_ROLL_CHUNKS) preRoll.removeFirst()
        }
    }

    private fun decide(word: SpottedWord, segmentStart: Long, history: LevelHistory): Pair<Boolean, String?> = when {
        word.keyword != WAKE_WORD -> false to "不是唤醒词"
        !shouldWake(word, segmentStart, history) -> false to "前面没停顿（像是句子中间的「$WAKE_WORD」）"
        clock() < cooldownUntil -> false to "刚叫醒过"
        else -> true to null
    }

    private fun sleep(ms: Long) {
        val until = clock() + ms
        while (!stopping && clock() < until) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return
            }
        }
    }
}
