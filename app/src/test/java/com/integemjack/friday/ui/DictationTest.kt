package com.integemjack.friday.ui

import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.Duration

/**
 * 语音键一直听写到再按一下（Robolectric 的假识别器）：一段段接起来实时写进框里，没听到话接着听，
 * 按了停马上退出、最后那段随后到了照样写，识别服务没回应 / 出错时收掉、说一声
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationTest {

    private val looper get() = shadowOf(Looper.getMainLooper())
    private lateinit var voice: VoiceInput
    private lateinit var draft: ComposerDraft
    private val notices = mutableListOf<String>()
    private var begun = 0
    private var ended = 0
    private var created = 0
    private lateinit var dictation: Dictation

    @Before
    fun setUp() {
        ShadowSpeechRecognizer.reset()
        voice = VoiceInput {}
        draft = ComposerDraft("原有的话，")
        dictation = Dictation(
            input = voice,
            draft = { draft },
            available = { true },
            create = {
                created++
                SpeechRecognizer.createSpeechRecognizer(RuntimeEnvironment.getApplication())
            },
            onBegin = { begun++ },
            onEnd = { ended++ },
            notify = { notices += it },
        )
    }

    private fun recognizer(): ShadowSpeechRecognizer = Shadow.extract(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

    private fun words(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    /** 开始听（识别器的监听、startListening 排在主线程上） */
    private fun start() {
        dictation.start()
        looper.idle()
    }

    /** 一段说完以后过一会儿接着开下一段 */
    private fun nextSegment() = looper.idleFor(Duration.ofMillis(Dictation.RESTART_DELAY_MS + 10))

    @Test
    fun `一直听：一段段接起来实时写进框里，没听到话也接着听，再按一下才停`() {
        start()
        assertTrue(voice.listening)
        assertEquals(1, begun)
        val first = recognizer()
        first.triggerOnReadyForSpeech(Bundle())
        first.triggerOnRmsChanged(7f)
        assertEquals(0.75f, voice.level, 0.01f)
        first.triggerOnPartialResults(words("今天"))
        assertEquals("原有的话，今天", draft.input)
        first.triggerOnResults(words("今天天气不错"))
        assertEquals("原有的话，今天天气不错", draft.input)
        assertTrue("说完一段还在听", voice.listening)

        // 接着开下一段（同一个识别器再 startListening）
        val before = first.lastRecognizerIntent
        nextSegment()
        assertTrue(first.lastRecognizerIntent !== before)
        first.triggerOnPartialResults(words("适合出门"))
        assertEquals("原有的话，今天天气不错适合出门", draft.input)
        // 没听到话（停顿太久）：听到的一半留着，接着听
        first.triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertEquals("原有的话，今天天气不错适合出门", draft.input)
        assertTrue(voice.listening)
        nextSegment()
        first.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        nextSegment()
        assertTrue(voice.listening)
        assertEquals(1, created)
        assertTrue(notices.isEmpty())

        // 再按一下：马上退出在听；最后那段的结果随后到了照样写进去
        first.triggerOnPartialResults(words("走吧"))
        dictation.stop()
        assertFalse(voice.listening)
        assertEquals(0f, voice.level, 0f)
        first.triggerOnResults(words("走吧走吧"))
        assertEquals("原有的话，今天天气不错适合出门走吧走吧", draft.input)
        assertTrue(first.isDestroyed)
        assertEquals(1, ended)
        // 停了不再开下一段
        nextSegment()
        first.triggerOnPartialResults(words("不该写进去"))
        assertEquals("原有的话，今天天气不错适合出门走吧走吧", draft.input)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `按了停最后那段一直不来：一会儿就不等了`() {
        start()
        recognizer().triggerOnReadyForSpeech(Bundle())
        dictation.stop()
        assertFalse(voice.listening)
        assertEquals(0, ended)
        looper.idleFor(Duration.ofMillis(Dictation.STOP_GRACE_MS + 10))
        assertTrue(recognizer().isDestroyed)
        assertEquals(1, ended)
        // 不等了以后再按：重新开始
        start()
        assertTrue(voice.listening)
        assertEquals(2, created)
    }

    @Test
    fun `识别服务一点回应都没有（系统里选的识别服务装不上、在等同意条款）：到点收掉，说一声为什么`() {
        start()
        assertTrue(voice.listening)
        looper.idleFor(Duration.ofMillis(Dictation.CONNECT_TIMEOUT_MS + 10))
        assertFalse(voice.listening)
        assertTrue(recognizer().isDestroyed)
        assertEquals(1, ended)
        assertTrue(notices.single().startsWith("语音识别没有回应"))
    }

    @Test
    fun `还没回应时按停：马上收掉，不等`() {
        start()
        dictation.stop()
        assertFalse(voice.listening)
        assertTrue(recognizer().isDestroyed)
        assertEquals(1, ended)
        looper.idleFor(Duration.ofMillis(Dictation.CONNECT_TIMEOUT_MS + 10))
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `识别服务正忙：等一下再开，连着几次都不行就收掉；别的错误直接收掉说一声`() {
        start()
        val shadow = recognizer()
        shadow.triggerOnReadyForSpeech(Bundle())
        repeat(Dictation.MAX_BUSY_RETRIES) {
            shadow.triggerOnError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            assertTrue(voice.listening)
            looper.idleFor(Duration.ofMillis(Dictation.BUSY_RETRY_MS + 10))
        }
        shadow.triggerOnError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        assertFalse(voice.listening)
        assertEquals("语音识别正忙，等一下再按", notices.single())

        notices.clear()
        start()
        recognizer().triggerOnPartialResults(words("听到一半"))
        recognizer().triggerOnError(SpeechRecognizer.ERROR_NETWORK)
        assertFalse(voice.listening)
        assertEquals("语音识别要联网，网络不通", notices.single())
        // 出错前听到的一半留在框里
        assertTrue(draft.input.endsWith("听到一半"))
    }

    @Test
    fun `没有识别服务：不开始，说一声`() {
        val none = Dictation(voice, { draft }, available = { false }, create = { null }, notify = { notices += it })
        none.start()
        assertFalse(voice.listening)
        assertTrue(notices.single().startsWith("这台手机没有语音识别服务"))
    }

    @Test
    fun `离开页面：收掉`() {
        start()
        dictation.release()
        assertFalse(voice.listening)
        assertTrue(recognizer().isDestroyed)
        assertEquals(1, ended)
    }

    @Test
    fun `接字：中文直接接，英文字母、数字之间补空格`() {
        assertEquals("你好", joinSpoken("", "你好"))
        assertEquals("你好世界", joinSpoken("你好", "世界"))
        assertEquals("hello world", joinSpoken("hello", "world"))
        assertEquals("版本2.97", joinSpoken("版本", "2.97"))
        assertEquals("abc", dictatedText("abc", ""))
        assertEquals("abc 今天", dictatedText("abc", "今天"))
        assertEquals("你好，今天", dictatedText("你好，", "今天"))
        assertNull(dictationError(SpeechRecognizer.ERROR_NO_MATCH))
    }

    @Test
    fun `音波起得快、落得慢`() {
        assertEquals(0.8f, waveStep(0.2f, 0.8f), 0f)
        val falling = waveStep(0.8f, 0f)
        assertTrue(falling in 0.4f..0.6f)
        assertEquals(1f, waveStep(0f, 3f), 0f)
    }
}
