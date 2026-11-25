package com.integemjack.friday.speech

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.integemjack.friday.proto.PendingApproval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 语音播报（设置 › 后台保持连接 › 语音播报，默认开），用手机自己的文字转语音（TextToSpeech）念：
 * - 停在助手页时它的新回复（说完一条念一条，连着几条排队念，[speak] 的 queue）；你开口 / 发话 / 点打断时 [stop]
 * - App 在后台时有调用等你确认、Friday 在问你：除了弹通知，再念一句，不用拿起手机看
 *
 * - 进程得活着：靠后台保持连接的前台服务（ConnectionService）。Android 15（targetSdk 35）起后台申请音频焦点
 *   也要有前台服务，拿不到焦点就不念（打电话时也拿不到）
 * - 不打扰：静音 / 震动、勿扰、正在打电话（系统通话或跟助手通话）时不念；念的时候别的声音压低、不停
 * - 先弹通知、等提示音响完再念（[AFTER_CHIME_MS]），不然开头被提示音压低
 * - 引擎不会说中文（没装中文语音数据）就不念，[engine] 告诉设置页
 * - 同一句 60 秒内不重复念（attention 事件可能连着来几次）；闲 60 秒就把引擎放掉
 *
 * 所有 TextToSpeech / 音频焦点的调用都在主线程上。
 */
class BackgroundSpeaker(
    private val app: Context,
    /** 正在跟助手通话（它的声音和话筒都在用，不插嘴） */
    private val inAssistantCall: () -> Boolean = { false },
) {
    enum class Engine {
        /** 还没起过引擎 */
        Unknown,
        Ready,
        /** 引擎在，但不会说中文 */
        NoChinese,
        /** 没有可用的文字转语音引擎 */
        Unavailable,
    }

    private val _engine = MutableStateFlow(Engine.Unknown)
    val engine: StateFlow<Engine> = _engine.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val audio: AudioManager? get() = app.getSystemService(AudioManager::class.java)

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var tts: TextToSpeech? = null
    private var ready = false

    /** 正在念（语音唤醒这时不认：别把自己念的听进去） */
    @Volatile
    var speaking = false
        private set
    /** 引擎还没起来时攒着的（念完一句顶掉的那种只留最新一句，排队的都留着） */
    private val pending = ArrayDeque<Line>()
    private var focus: AudioFocusRequest? = null
    private var holdingFocus = false
    private var utterance = 0
    private var lastText: String? = null
    private var lastAt = 0L

    private val release = Runnable {
        val t = tts ?: return@Runnable
        if (t.isSpeaking) scheduleRelease() else shutdown()
    }

    private class Line(val text: String, val force: Boolean, val queue: Boolean)

    /**
     * 念一句（排在主线程上，马上返回）。[force]：设置页「试听」——不管静音 / 勿扰、不去重；
     * 拿不到音频焦点（在打电话）照样不念。[delayMs]：过这么久再念（刚弹的通知有提示音时等它响完）。
     * [queue]：排在正在念的后面（助手连着说的几条回复，一条不落；不按文字去重，调用方按条去重），
     * 不排队的顶掉正在念的
     */
    fun speak(text: String, force: Boolean = false, delayMs: Long = 0, queue: Boolean = false) {
        val line = text.trim()
        if (line.isEmpty()) return
        main.postDelayed({ enqueue(Line(line, force, queue)) }, delayMs.coerceAtLeast(0))
    }

    /** 别念了（你开口、发话、点了打断）：正在念的和排着的都不要，焦点还回去 */
    fun stop() {
        main.post {
            pending.clear()
            if (!speaking) return@post
            // 停下来的那句回调 onStop 时 id 已经对不上，不再动焦点：这里自己收
            utterance++
            runCatching { tts?.stop() }
            speaking = false
            abandonFocus()
            scheduleRelease()
        }
    }

    /** 现在为什么不该出声（null = 可以念） */
    fun quietReason(): String? {
        val audio = audio ?: return null
        val nm = app.getSystemService(NotificationManager::class.java)
        return quietReasonFor(
            ringerMode = audio.ringerMode,
            interruptionFilter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
            audioMode = audio.mode,
            inAssistantCall = inAssistantCall(),
        )
    }

    private fun enqueue(line: Line) {
        if (!line.queue) {
            val now = SystemClock.elapsedRealtime()
            if (!line.force && line.text == lastText && now - lastAt < REPEAT_WINDOW_MS) return
            lastText = line.text
            lastAt = now
        }
        main.removeCallbacks(release)
        if (tts == null) {
            create()
            if (tts == null) return
        }
        if (!ready) {
            // 引擎起来再念；这期间又来一句不排队的就只念新的
            if (!line.queue) pending.clear()
            pending.addLast(line)
            return
        }
        say(line)
    }

    private fun create() {
        ready = false
        val created = runCatching {
            // 绑不上引擎时 onInit 可能在构造函数里就同步回调了（那会儿 tts 还没赋值）：一律排到主线程之后处理
            TextToSpeech(app) { status -> main.post { onInit(status) } }
        }.getOrNull()
        if (created == null) {
            _engine.value = Engine.Unavailable
            pending.clear()
            return
        }
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
        tts = created
    }

    private fun onInit(status: Int) {
        val t = tts ?: return
        if (ready) return
        if (status != TextToSpeech.SUCCESS) {
            _engine.value = Engine.Unavailable
            pending.clear()
            shutdown()
            return
        }
        val lang = runCatching { t.setLanguage(Locale.SIMPLIFIED_CHINESE) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            _engine.value = Engine.NoChinese
            pending.clear()
            shutdown()
            return
        }
        runCatching { t.setAudioAttributes(attributes) }
        ready = true
        _engine.value = Engine.Ready
        if (pending.isEmpty()) {
            scheduleRelease()
            return
        }
        val lines = pending.toList()
        pending.clear()
        lines.forEach { say(it) }
    }

    private fun say(line: Line) {
        val t = tts ?: return
        if ((!line.force && quietReason() != null) || !requestFocus()) {
            if (!speaking) scheduleRelease()
            return
        }
        val id = "friday-${++utterance}"
        speaking = true
        val mode = if (line.queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        val result = runCatching { t.speak(line.text, mode, Bundle(), id) }.getOrDefault(TextToSpeech.ERROR)
        // 念不出来（引擎进程被收掉了之类）：整个放掉，下一句重新起
        if (result != TextToSpeech.SUCCESS) shutdown()
    }

    /** 念完 / 出错 / 被下一句顶掉：只认最新那句（被 QUEUE_FLUSH 顶掉的、排在后面还有的那句不放焦点） */
    private fun finished(utteranceId: String?) {
        main.post {
            if (utteranceId != "friday-$utterance") return@post
            speaking = false
            abandonFocus()
            scheduleRelease()
        }
    }

    private fun requestFocus(): Boolean {
        val audio = audio ?: return false
        val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                // 来电话、别的 App 要独占：别念了（stop 会回调 onStop，那边放焦点）
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    runCatching { tts?.stop() }
                }
            }, main)
            .build()
            .also { focus = it }
        val granted = runCatching { audio.requestAudioFocus(request) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        // 排队念时前一句还拿着焦点：这次没要到也别把它忘了（念完要还）
        if (granted) holdingFocus = true
        return granted
    }

    private fun abandonFocus() {
        if (!holdingFocus) return
        holdingFocus = false
        focus?.let { request -> runCatching { audio?.abandonAudioFocusRequest(request) } }
    }

    private fun scheduleRelease() {
        main.removeCallbacks(release)
        main.postDelayed(release, IDLE_RELEASE_MS)
    }

    private fun shutdown() {
        main.removeCallbacks(release)
        speaking = false
        abandonFocus()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    companion object {
        const val REPEAT_WINDOW_MS = 60_000L
        const val IDLE_RELEASE_MS = 60_000L

        /**
         * 跟通知一起来时晚这么久再念：通知提示音自己也要音频焦点（压低别的声音），一起响的话头一秒多的话被压下去。
         * 小米 17 上实测提示音占焦点约 1.24 秒
         */
        const val AFTER_CHIME_MS = 1_500L

        /** 设置页「试听」念的那句 */
        const val SAMPLE = "这是 Friday 的语音播报：助手的新回复、有调用等你确认的时候，我会这样念出来。"
    }
}

/**
 * 什么时候不出声（null = 可以念）：跟助手通话 / 打电话、静音或震动、勿扰开着。
 * 勿扰的 [interruptionFilter] 是 UNKNOWN（拿不到）时当没开
 */
internal fun quietReasonFor(ringerMode: Int, interruptionFilter: Int, audioMode: Int, inAssistantCall: Boolean): String? = when {
    inAssistantCall -> "正在跟助手通话"
    audioMode != AudioManager.MODE_NORMAL -> "正在打电话"
    ringerMode != AudioManager.RINGER_MODE_NORMAL -> "手机静音或震动"
    interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
        interruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> "开着勿扰"
    else -> null
}

/** 待确认的那几项（会话标题 + 调用）：系统通知和语音播报都用这份 */
internal fun pendingApprovals(activities: List<com.integemjack.friday.proto.SessionActivity>): List<Pair<String, PendingApproval>> =
    activities.flatMap { activity -> activity.approvals.map { activity.title to it } }

/**
 * 待确认时念的那一句。跟通知一个意思，但说给耳朵听：
 * 一项就说哪段会话、什么调用（提问直接念问题）；好几项念前两项，剩下的说个数
 */
internal fun attentionSpeech(approvals: List<Pair<String, PendingApproval>>): String {
    fun what(approval: PendingApproval): String =
        speakable(approval.title.ifBlank { approval.tool }, 40)

    return when (approvals.size) {
        0 -> "Friday 有调用等你确认"
        1 -> {
            val (session, approval) = approvals[0]
            val where = speakable(session, 24)
            if (approval.isQuestion) {
                val question = speakable(approval.questions?.firstOrNull()?.question.orEmpty(), 80)
                    .ifEmpty { what(approval) }
                listOf(where, question).filter { it.isNotEmpty() }.joinToString("，")
                    .let { if (it.isEmpty()) "Friday 在问你" else "Friday 在问你：$it" }
            } else {
                listOf(where, what(approval)).filter { it.isNotEmpty() }.joinToString("，")
                    .let { if (it.isEmpty()) "Friday 等你确认" else "Friday 等你确认：$it" }
            }
        }
        else -> {
            val named = approvals.take(2).map { what(it.second) }.filter { it.isNotEmpty() }
            val rest = approvals.size - named.size
            buildString {
                append("Friday 有 ").append(approvals.size).append(" 项等你确认")
                if (named.isNotEmpty()) {
                    append("：").append(named.joinToString("、"))
                    if (rest > 0) append("，还有 ").append(rest).append(" 项")
                }
            }
        }
    }
}

/** 念得出来的样子：去掉 Markdown 记号、并成一行、太长截断 */
internal fun speakable(text: String, max: Int): String {
    val line = text
        .replace(Regex("[`*#>_~|]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (line.length <= max) line else line.take(max).trimEnd() + "…"
}

/** 助手一条回复最多念这么多字（它说的本来就短，长内容在「给看的」里；万一写长了别念几分钟） */
internal const val REPLY_MAX = 400

/**
 * 助手回复念出来的样子：代码块不念，链接只念文字（光秃秃的网址说成「链接」），去掉 Markdown 记号和列表的序号；
 * 一行一行接起来，行尾没标点的补个逗号停一下；太长截断
 */
internal fun replySpeech(text: String, max: Int = REPLY_MAX): String {
    val plain = text
        .replace(Regex("```[\\s\\S]*?(```|$)"), "\n")
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), "链接")
    val lines = plain.lines()
        // 列表记号：「- 」「1. 」「2) 」后面要有空格（「3.5 版」不是列表），中文的「3、」后面可以不空
        .map { it.replace(Regex("^\\s*(?:(?:[-*+]|\\d+[.)])\\s+|\\d+、\\s*)"), "") }
        // 加粗、删除线、行内代码的记号直接去掉（不留空格：「**测试**全过了」念成「测试全过了」）
        .map { speakable(it.replace(Regex("[*~`]+"), ""), Int.MAX_VALUE) }
        .filter { it.isNotEmpty() }
    val joined = buildString {
        lines.forEachIndexed { i, line ->
            if (i > 0 && last() !in SPOKEN_PAUSES) append('，')
            append(line)
        }
    }
    return if (joined.length <= max) joined else joined.take(max).trimEnd('，', ' ') + "…"
}

/** 行尾已经有停顿的标点：接下一行时不再补逗号 */
private const val SPOKEN_PAUSES = "。！？!?，,；;：:…、"
