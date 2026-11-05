package com.integemjack.friday.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
 * 输入框的语音键（输入框空着时右边默认是它）：按一下开始听，边说边把字写进输入框，说完自己停（或者再按一下停），
 * 停了字留在框里，看一眼再发。用的是手机自己的语音识别（SpeechRecognizer），中文优先。
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

    /** 麦克风音量 0–1（按钮跟着跳一跳） */
    var level by mutableFloatStateOf(0f)
        internal set

    fun toggle() = toggleFn()
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

    fun begin(input: VoiceInput) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, "这台手机没有语音识别服务（装个讯飞 / 谷歌语音输入就能用）", Toast.LENGTH_LONG).show()
            return
        }
        val target = current
        val base = target.input
        val joiner = if (base.isNotEmpty() && base.last().let { it.code < 0x80 && it.isLetterOrDigit() }) " " else ""
        fun write(text: String?) {
            if (text.isNullOrBlank()) return
            target.input = base + joiner + text
        }
        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() ?: run {
            Toast.makeText(context, "语音识别起不来", Toast.LENGTH_SHORT).show()
            return
        }
        holder.recognizer?.destroy()
        holder.recognizer = recognizer
        // 语音唤醒先把麦克风让出来（两边一起录，系统可能把听写那边静音）
        model.wake.hold(WAKE_HOLD)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onRmsChanged(rmsdB: Float) {
                // 大约 -2..10 dB → 0..1
                input.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onEndOfSpeech() {
                input.level = 0f
            }

            override fun onPartialResults(partialResults: Bundle?) {
                write(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }

            override fun onResults(results: Bundle?) {
                write(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                finish(input)
            }

            override fun onError(error: Int) {
                finish(input)
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null // 没听到话：不打扰
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别要联网，网络不通"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别正忙，等一下再按"
                    else -> "语音识别出错了（$error）"
                }
                message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            }

            private fun finish(input: VoiceInput) {
                input.listening = false
                input.level = 0f
                holder.recognizer?.destroy()
                holder.recognizer = null
                model.wake.release(WAKE_HOLD)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        input.listening = true
        runCatching { recognizer.startListening(intent) }.onFailure {
            input.listening = false
            model.wake.release(WAKE_HOLD)
            Toast.makeText(context, "语音识别起不来（${it.message}）", Toast.LENGTH_SHORT).show()
        }
    }

    val voice = remember {
        lateinit var self: VoiceInput
        self = VoiceInput { holder.toggle?.invoke(self) }
        self
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin(voice) else Toast.makeText(context, "要用语音输入得给麦克风权限", Toast.LENGTH_SHORT).show()
    }
    holder.toggle = { input ->
        if (input.listening) {
            holder.recognizer?.stopListening()
        } else if (hasMic(context)) {
            begin(input)
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            holder.recognizer?.destroy()
            holder.recognizer = null
            model.wake.release(WAKE_HOLD)
        }
    }
    return voice
}

/** 听写时让语音唤醒放开麦克风用的名字 */
private const val WAKE_HOLD = "dictation"

private class DictationHolder {
    var recognizer: SpeechRecognizer? = null
    var toggle: ((VoiceInput) -> Unit)? = null
}

private fun hasMic(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
