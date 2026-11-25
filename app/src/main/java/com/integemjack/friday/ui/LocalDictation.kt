package com.integemjack.friday.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.integemjack.friday.speech.AsrEngine
import com.integemjack.friday.wake.MicSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/** 语音键背后的两种听写（本地流式 [LocalDictation]、手机自带的语音识别 [Dictation]）：主线程上调 */
internal interface VoiceEngine {
    fun start()
    fun stop()
    fun release()
}

/**
 * 本地流式听写（2026-11-25 用户：音波要是真的麦克风、字要一个个实时出来、带标点）：自己录音（[mic]，16 kHz），
 * 每 [CHUNK_MS] 毫秒一块——算这块的音量给音波（真的麦克风音量），喂给本地的流式识别（[engine]，sherpa-onnx），
 * 正在说的这句实时写进框里；停顿够长算一句说完，留下、接着认下一句；再按一下停：剩下的认完写进去。
 *
 * 录音和识别在一条后台线程上（[worker]），框里的字、音量、状态都回主线程改。加载模型要一两秒：这期间框里写「正在准备」。
 */
internal class LocalDictation(
    private val input: VoiceInput,
    private val draft: () -> ComposerDraft,
    /** 加载好的识别器（阻塞，后台线程上调；用不了回 null） */
    private val engine: () -> AsrEngine?,
    private val mic: () -> MicSource,
    private val onBegin: () -> Unit = {},
    private val onEnd: () -> Unit = {},
    private val notify: (String) -> Unit = {},
    private val worker: (Runnable) -> Unit = { Thread(it, "friday-dictation").start() },
) : VoiceEngine {
    private val main = Handler(Looper.getMainLooper())

    /** 用户还要听（后台线程看它停） */
    private val wanted = AtomicBoolean(false)

    /** 第几次听写：后台线程回来的事是上一次的就不管 */
    private var run = 0
    private var running = false
    private var target: ComposerDraft? = null
    private var base = ""
    private var committed = ""
    private var partial = ""

    val active: Boolean get() = running

    override fun start() {
        if (running) return
        val target = draft()
        this.target = target
        base = target.input
        committed = ""
        partial = ""
        running = true
        wanted.set(true)
        val id = ++run
        input.listening = true
        input.level = 0f
        input.hint = "正在准备本地识别…"
        onBegin()
        Log.i(Dictation.LOG_TAG, "本地听写 #$id 开始")
        worker(Runnable { loop(id) })
    }

    /** 再按一下：马上退出在听；后台把剩下的认完写进框里再收 */
    override fun stop() {
        if (!running || !wanted.get()) return
        wanted.set(false)
        input.listening = false
        input.level = 0f
        input.hint = null
    }

    /** 离开页面：不等了 */
    override fun release() {
        wanted.set(false)
        end(run)
    }

    private fun loop(id: Int) {
        val engine = engine()
        if (engine == null) {
            post(id) { fail("本地语音识别用不了（模型加载失败）") }
            return
        }
        val session = engine.session()
        val mic = mic()
        if (!mic.start()) {
            session.release()
            post(id) { fail("麦克风用不了（可能在打电话、别的 App 在录音）") }
            return
        }
        post(id) { input.hint = null }
        val buffer = FloatArray(CHUNK_SAMPLES)
        var last = ""
        try {
            while (wanted.get()) {
                val n = mic.read(buffer)
                if (n <= 0) {
                    post(id) { fail("麦克风断了") }
                    return
                }
                val level = speechLevel(buffer, n)
                post(id) { input.level = level }
                session.accept(buffer, n)
                val text = session.text()
                if (session.endpoint()) {
                    // 一句说完：留下，接着认下一句
                    session.reset()
                    last = ""
                    if (text.isNotBlank()) post(id) { commit(text) }
                } else if (text != last) {
                    last = text
                    post(id) { show(text) }
                }
            }
            // 按了停：剩下的认完
            mic.stop()
            val tail = session.finish()
            post(id) {
                if (tail.isNotBlank()) commit(tail) else commit(partial)
                end(id)
            }
        } finally {
            mic.stop()
            session.release()
        }
    }

    /** 回主线程做（这次听写已经收掉、又开了新的一次就不做） */
    private fun post(id: Int, block: () -> Unit) {
        main.post { if (id == run && running) block() }
    }

    private fun show(text: String) {
        partial = tidySpoken(text)
        write()
    }

    private fun commit(text: String) {
        val said = tidySpoken(text)
        if (said.isNotBlank()) committed = joinSentences(committed, said)
        partial = ""
        write()
    }

    private fun write() {
        val target = target ?: return
        target.input = dictatedText(base, joinSentences(committed, partial))
    }

    private fun fail(message: String) {
        Log.i(Dictation.LOG_TAG, "本地听写 #$run 出错：$message")
        commit(partial)
        wanted.set(false)
        end(run)
        notify(message)
    }

    private fun end(id: Int) {
        if (!running || id != run) return
        Log.i(Dictation.LOG_TAG, "本地听写 #$id 收掉")
        running = false
        wanted.set(false)
        input.listening = false
        input.level = 0f
        input.hint = null
        target = null
        onEnd()
    }

    companion object {
        /** 一块多长（音波每块动一下：50 毫秒够跟手） */
        const val CHUNK_MS = 50
        const val CHUNK_SAMPLES = 16_000 * CHUNK_MS / 1000
    }
}

/** 中文字、中文标点 */
private fun Char.isCjk(): Boolean =
    Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN || this in "，。？！、；：…“”‘’（）《》【】"

/**
 * 认出来的字收拾一下：x-asr 在标点后面、中文之间会带空格（「开会， 顺便」）——挨着中文、中文标点的空格去掉，
 * 英文单词之间的留着
 */
internal fun tidySpoken(text: String): String {
    val raw = text.trim()
    val out = StringBuilder(raw.length)
    for (i in raw.indices) {
        val c = raw[i]
        if (c.isWhitespace()) {
            val before = out.lastOrNull()
            val after = raw.getOrNull(i + 1)
            if (before == null || after == null || after.isWhitespace() || before.isCjk() || after.isCjk()) continue
        }
        out.append(c)
    }
    return out.toString()
}

/** 一句说完停顿了、接着说下一句：前一句末尾没标点的，中文补个逗号，英文补个空格 */
internal fun joinSentences(before: String, next: String): String {
    if (before.isEmpty()) return next
    if (next.isEmpty()) return before
    if (before.last() in "，。？！、；：…,.?!;:") return before + next
    return if (before.last().isCjk() || next.first().isCjk()) "$before，$next" else "$before $next"
}

/**
 * 一块录音的音量（0–1）给音波：按均方根算分贝，-55 dB（安静的房间）以下算 0，-15 dB（大声说话）算 1
 */
internal fun speechLevel(samples: FloatArray, count: Int): Float {
    if (count <= 0) return 0f
    var sum = 0.0
    for (i in 0 until count) sum += samples[i] * samples[i]
    val rms = sqrt(sum / count)
    if (rms <= 0.0) return 0f
    val db = 20 * log10(rms)
    return ((db + 55) / 40).toFloat().coerceIn(0f, 1f)
}
