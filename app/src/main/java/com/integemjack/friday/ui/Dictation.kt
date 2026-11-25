package com.integemjack.friday.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 输入框的语音键（输入框空着时右边默认是它）：按一下开始听，一直听到再按一下才停；说的话实时写进输入框，
 * 停了字留在框里，看一眼再发。听着的时候输入框底行原来写模型的地方换成跟着音量滚动的音波（[VoiceWave]）。
 * 用的是手机自己的语音识别（SpeechRecognizer），中文优先。
 *
 * 不是助手的「语音模式」：那个是整段对话交给那台机器（voice.*），这里只管把话变成字。
 */
@Stable
class VoiceInput internal constructor(
    private val toggleFn: () -> Unit,
) {
    /** 正在听 */
    var listening by mutableStateOf(false)
        internal set

    /** 麦克风音量 0–1（按钮跟着跳一跳、音波跟着滚） */
    var level by mutableFloatStateOf(0f)
        internal set

    fun toggle() = toggleFn()
}

/**
 * 一直听写到用户再按一下（2026-11-25 用户：「直到用户再次点击语音按钮才停止识别，识别的内容实时显示到输入框里」）。
 *
 * 手机的语音识别说完一句就结束一段（onResults / 没听到话的 onError）：还要听就接着开下一段，一段段接起来——
 * 框里是「开始听时框里原有的字 + 已经说完的几段 + 正在说的这段（partial）」。
 *
 * 不会一直卡在「在听」（以前识别服务连不上时回调一个都不来，按钮停在在听、再按也停不了）：
 * - 再按一下马上退出在听（[stop]），最后那段的结果随后到了照样写进框里，[STOP_GRACE_MS] 还不来就不等了
 * - 开始后 [CONNECT_TIMEOUT_MS] 里识别服务一点回应都没有：收掉，说一声为什么（系统设置里选的识别服务装不上、
 *   在等用户同意它的条款……）
 *
 * 只在主线程用（SpeechRecognizer 也只能在主线程调）。
 */
internal class Dictation(
    private val input: VoiceInput,
    /** 写进去的那份草稿（开始时记下原有的字） */
    private val draft: () -> ComposerDraft,
    private val available: () -> Boolean,
    private val create: () -> SpeechRecognizer?,
    /** 开始听（让语音唤醒放开麦克风、正在念的停下） */
    private val onBegin: () -> Unit = {},
    /** 不听了 */
    private val onEnd: () -> Unit = {},
    private val notify: (String) -> Unit = {},
    /** 交给识别服务的「谁在用」（EXTRA_CALLING_PACKAGE） */
    private val callingPackage: String = "com.integemjack.friday",
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** 用户还要听（没按停） */
    private var wanted = false

    /** 这一次听写写到哪份草稿、前面原有的字 */
    private var target: ComposerDraft? = null
    private var base = ""

    /** 已经说完的几段 */
    private var committed = ""

    /** 正在说的这段 */
    private var partial = ""

    /** 识别服务回过话（就绪、音量、字、出错都算）：没回过话到点了就是没连上 */
    private var answered = false
    private var busyRetries = 0
    private var running = false

    private val connectTimeout = Runnable {
        if (running && !answered) {
            fail("语音识别没有回应：系统设置里选的语音识别服务可能用不了，或者它在等你同意它的条款")
        }
    }
    private val stopTimeout = Runnable { end() }

    val active: Boolean get() = running

    fun start() {
        // 刚按了停、还在等最后那段的结果时又按：那段不等了，重新开始
        if (running) {
            if (wanted) return
            end()
        }
        if (!available()) {
            notify("这台手机没有语音识别服务（装个讯飞 / 谷歌语音输入就能用）")
            return
        }
        val recognizer = create() ?: run {
            notify("语音识别起不来")
            return
        }
        this.recognizer = recognizer
        val target = draft()
        this.target = target
        base = target.input
        committed = ""
        partial = ""
        answered = false
        busyRetries = 0
        running = true
        wanted = true
        input.listening = true
        input.level = 0f
        onBegin()
        recognizer.setRecognitionListener(listener)
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        listen()
    }

    /** 再按一下：马上退出在听；最后那段的结果随后到了照样写进去 */
    fun stop() {
        if (!running || !wanted) return
        wanted = false
        input.listening = false
        input.level = 0f
        main.removeCallbacks(connectTimeout)
        if (!answered) {
            end()
            return
        }
        runCatching { recognizer?.stopListening() }
        main.postDelayed(stopTimeout, STOP_GRACE_MS)
    }

    /** 离开页面：不管说到哪都收掉 */
    fun release() = end()

    private fun listen() {
        val recognizer = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, callingPackage)
            // 停顿久一点再算说完（有的识别服务认这几个，不认的照样一段段接起来）
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
        }
        runCatching { recognizer.startListening(intent) }.onFailure { fail("语音识别起不来（${it.message}）") }
    }

    /** 说完一段（或者没听到话）：还要听就接着开下一段 */
    private fun next() {
        if (!running) return
        if (wanted) main.postDelayed({ if (running && wanted) listen() }, RESTART_DELAY_MS) else end()
    }

    private fun write() {
        val target = target ?: return
        target.input = dictatedText(base, joinSpoken(committed, partial))
    }

    /** 正在说的这段算说完了（出错前听到一半的也留着） */
    private fun commitPartial() {
        if (partial.isBlank()) return
        committed = joinSpoken(committed, partial)
        partial = ""
        write()
    }

    private fun fail(message: String?) {
        end()
        message?.let(notify)
    }

    private fun end() {
        if (!running) return
        running = false
        wanted = false
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(stopTimeout)
        input.listening = false
        input.level = 0f
        runCatching { recognizer?.destroy() }
        recognizer = null
        target = null
        onEnd()
    }

    private val listener = object : RecognitionListener {
        private fun heard() {
            if (!answered) {
                answered = true
                main.removeCallbacks(connectTimeout)
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = heard()
        override fun onBeginningOfSpeech() = heard()
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            heard()
            // 大约 -2..10 dB → 0..1；停了以后来的不算（音波已经收了）
            if (wanted) input.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onEndOfSpeech() {
            input.level = 0f
        }

        override fun onPartialResults(partialResults: Bundle?) {
            heard()
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank() || !running) return
            partial = text
            write()
        }

        override fun onResults(results: Bundle?) {
            heard()
            if (!running) return
            busyRetries = 0
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            // 这段的最终结果替掉 partial；没给结果就留着听到的一半
            if (!text.isNullOrBlank()) partial = text
            commitPartial()
            next()
        }

        override fun onError(error: Int) {
            heard()
            if (!running) return
            commitPartial()
            when {
                // 按了停：这段收尾时报的错不用管
                !wanted -> end()
                // 没听到话：接着听
                error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> next()
                // 刚结束一段马上开下一段，有的识别服务还没收拾好：等一下再开，连着几次都不行就算了
                (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) && busyRetries < MAX_BUSY_RETRIES -> {
                    busyRetries++
                    main.postDelayed({ if (running && wanted) listen() }, BUSY_RETRY_MS)
                }
                else -> fail(dictationError(error))
            }
        }
    }

    companion object {
        /** 开始后这么久识别服务一点回应都没有：算没连上 */
        const val CONNECT_TIMEOUT_MS = 8_000L

        /** 按了停以后最多等最后那段的结果这么久 */
        const val STOP_GRACE_MS = 1_500L

        /** 一段说完到开下一段 */
        const val RESTART_DELAY_MS = 80L
        const val BUSY_RETRY_MS = 400L
        const val MAX_BUSY_RETRIES = 4
    }
}

/** 听写写进框里的字：开始时框里原有的接上听到的（原来的末尾是英文字母、数字才补一个空格；中文接中文不加） */
internal fun dictatedText(base: String, spoken: String): String {
    if (spoken.isEmpty()) return base
    val joiner = if (base.isNotEmpty() && base.last().let { it.code < 0x80 && it.isLetterOrDigit() }) " " else ""
    return base + joiner + spoken
}

/** 一段段接起来：前一段末尾和这段开头都是英文字母、数字时中间补空格，别的直接接上 */
internal fun joinSpoken(before: String, next: String): String {
    if (before.isEmpty()) return next
    if (next.isEmpty()) return before
    val latin = { c: Char -> c.code < 0x80 && c.isLetterOrDigit() }
    return if (latin(before.last()) && latin(next.first())) "$before $next" else before + next
}

/** 识别出错时说的话（没听到话的不说） */
internal fun dictationError(error: Int): String? = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别要联网，网络不通"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别正忙，等一下再按"
    SpeechRecognizer.ERROR_AUDIO -> "麦克风用不了（可能在打电话、别的 App 在录音）"
    else -> "语音识别出错了（$error）"
}

/**
 * [draft] 是要写进去的那份草稿：开始听时记下框里原有的字，识别出来的接在后面
 * （中间不加空格：中文接中文；原来的末尾是英文字母才补一个空格）。
 */
@Composable
fun rememberVoiceInput(draft: ComposerDraft): VoiceInput {
    val context = LocalContext.current
    val model = LocalAppModel.current
    val current by rememberUpdatedState(draft)
    val holder = remember { DictationHolder() }

    val voice = remember {
        lateinit var self: VoiceInput
        self = VoiceInput { holder.toggle?.invoke() }
        self
    }
    val dictation = remember(voice) {
        Dictation(
            input = voice,
            draft = { current },
            available = { SpeechRecognizer.isRecognitionAvailable(context) },
            create = { runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() },
            // 语音唤醒先把麦克风让出来（两边一起录，系统可能把听写那边静音）；正在念的停下（别把念的听进去）
            onBegin = {
                model.wake.hold(WAKE_HOLD)
                model.speaker.stop()
            },
            onEnd = { model.wake.release(WAKE_HOLD) },
            notify = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
            callingPackage = context.packageName,
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) dictation.start() else Toast.makeText(context, "要用语音输入得给麦克风权限", Toast.LENGTH_SHORT).show()
    }
    holder.toggle = {
        when {
            voice.listening -> dictation.stop()
            hasMic(context) -> dictation.start()
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    DisposableEffect(Unit) {
        onDispose { dictation.release() }
    }
    return voice
}

/** 听写时让语音唤醒放开麦克风用的名字 */
private const val WAKE_HOLD = "dictation"

private class DictationHolder {
    var toggle: (() -> Unit)? = null
}

private fun hasMic(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
