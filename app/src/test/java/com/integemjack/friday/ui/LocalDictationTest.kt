package com.integemjack.friday.ui

import android.os.Looper
import com.integemjack.friday.speech.AsrEngine
import com.integemjack.friday.speech.AsrSession
import com.integemjack.friday.wake.MicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 本地流式听写（假麦克风、假识别）：边说边写进框里、一句说完留下接着认下一句、音波是录音的真音量、
 * 再按一下停把剩下的认完、加载不了 / 麦克风用不了时收掉说一声
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalDictationTest {

    /** 假麦克风：一块块给（音量按给的幅度），给完了就当用户按了停 */
    private class FakeMic(private val amplitudes: List<Float>, private val onEmpty: () -> Unit, private val starts: Boolean = true) : MicSource {
        var reads = 0
        var stopped = false
        override fun start() = starts
        override fun read(buffer: FloatArray): Int {
            val amplitude = amplitudes.getOrNull(reads) ?: run {
                onEmpty()
                0.0f
            }
            reads++
            for (i in buffer.indices) buffer[i] = if (i % 2 == 0) amplitude else -amplitude
            return buffer.size
        }
        override fun stop() {
            stopped = true
        }
    }

    /** 假识别：每喂一块给出那时的字和这句完没完；收尾时给 [tail] */
    private class FakeSession(private val script: List<Pair<String, Boolean>>, private val tail: String) : AsrSession {
        var fed = 0
        var released = false
        private var current = "" to false
        override fun accept(samples: FloatArray, count: Int) {
            current = script.getOrElse(fed) { current.first to false }
            fed++
        }
        override fun text() = current.first
        override fun endpoint() = current.second
        override fun reset() {
            current = "" to false
        }
        override fun finish() = tail
        override fun release() {
            released = true
        }
    }

    private val looper get() = shadowOf(Looper.getMainLooper())

    @Test
    fun `边说边出字、一句说完留下接着认下一句，音波是真音量，再按一下停把剩下的认完`() {
        val voice = VoiceInput {}
        val written = mutableListOf<String>()
        val draft = ComposerDraft("原有的话，") { written += it }
        lateinit var dictation: LocalDictation
        val session = FakeSession(
            listOf(
                "明" to false, "明天下午" to false, "明天下午三点提醒我开会" to false, "明天下午三点提醒我开会。" to true,
                "顺便" to false, "顺便把纪要" to false,
            ),
            tail = "顺便把纪要发给我。",
        )
        val levels = mutableListOf<Float>()
        val mic = FakeMic(listOf(0.3f, 0.3f, 0.3f, 0.001f, 0.3f, 0.3f), onEmpty = { dictation.stop() })
        var begun = 0
        var ended = 0
        dictation = LocalDictation(
            input = voice,
            draft = { draft },
            engine = { AsrEngine { session } },
            mic = { mic },
            onBegin = { begun++ },
            onEnd = { ended++ },
            // 后台线程换成同一个线程直接跑：每喂一块就看一眼主线程上做了什么
            worker = { it.run() },
        )
        dictation.start()
        assertEquals(1, begun)
        // 一块块回主线程：字一点点长、音量跟着声音
        val steps = mutableListOf<String>()
        while (looper.nextScheduledTaskTime != null && !looper.isIdle) {
            looper.runOneTask()
            if (steps.lastOrNull() != draft.input) steps += draft.input
            levels += voice.level
        }
        looper.idle()
        assertEquals("原有的话，明天下午三点提醒我开会。顺便把纪要发给我。", draft.input)
        assertTrue("字是一点点长出来的：$steps", steps.containsAll(listOf("原有的话，明", "原有的话，明天下午", "原有的话，明天下午三点提醒我开会")))
        assertTrue("大声的块音量高：$levels", levels.any { it > 0.6f })
        assertFalse(voice.listening)
        assertEquals(0f, voice.level, 0f)
        assertTrue(mic.stopped)
        assertTrue(session.released)
        assertEquals(1, ended)
        assertTrue(written.isNotEmpty())
    }

    @Test
    fun `模型加载不了、麦克风用不了：收掉说一声`() {
        val voice = VoiceInput {}
        val draft = ComposerDraft("")
        val notices = mutableListOf<String>()
        var ended = 0
        LocalDictation(voice, { draft }, engine = { null }, mic = { error("不该开麦克风") }, onEnd = { ended++ }, notify = { notices += it }, worker = { it.run() }).start()
        looper.idle()
        assertFalse(voice.listening)
        assertEquals(1, ended)
        assertTrue(notices.single().startsWith("本地语音识别用不了"))

        notices.clear()
        val session = FakeSession(emptyList(), "")
        LocalDictation(voice, { draft }, engine = { AsrEngine { session } }, mic = { FakeMic(emptyList(), {}, starts = false) }, notify = { notices += it }, worker = { it.run() }).start()
        looper.idle()
        assertFalse(voice.listening)
        assertTrue(session.released)
        assertTrue(notices.single().startsWith("麦克风用不了"))
    }

    @Test
    fun `准备好之前框里写正在准备，开始录音后换回默认`() {
        val voice = VoiceInput {}
        val draft = ComposerDraft("")
        var started: Runnable? = null
        val dictation = LocalDictation(voice, { draft }, engine = { null }, mic = { error("x") }, worker = { started = it })
        dictation.start()
        assertTrue(voice.listening)
        assertEquals("正在准备本地识别…", voice.hint)
        dictation.stop()
        assertFalse(voice.listening)
        assertEquals(null, voice.hint)
        assertTrue(started != null)
    }

    @Test
    fun `收拾字：去掉挨着中文的空格，英文单词之间留着；一句接一句补逗号`() {
        assertEquals("明天下午三点提醒我开会，顺便把会议纪要发给我", tidySpoken(" 明天下午三点提醒我开会， 顺便把会议纪要发给我 "))
        assertEquals("今天天气怎么样？要不要带伞", tidySpoken("今天天气怎么样？ 要不要带伞"))
        assertEquals("看一下G T Hip上的CI", tidySpoken("看一下 G T Hip 上的 CI"))
        assertEquals("hello world", tidySpoken("hello  world"))
        assertEquals("今天天气怎么样？要不要带伞，明天呢", joinSentences("今天天气怎么样？要不要带伞", "明天呢"))
        assertEquals("好的。明天见", joinSentences("好的。", "明天见"))
        assertEquals("ok sure", joinSentences("ok", "sure"))
        assertEquals("明天呢", joinSentences("", "明天呢"))
    }

    @Test
    fun `一句说完停顿了接着说：中间补逗号，空格收拾掉`() {
        val voice = VoiceInput {}
        val draft = ComposerDraft("")
        lateinit var dictation: LocalDictation
        val session = FakeSession(listOf("今天天气怎么样？ 要不" to false, "今天天气怎么样？ 要不要带伞" to true, "明天" to false), tail = "明天呢")
        dictation = LocalDictation(
            voice, { draft }, engine = { AsrEngine { session } },
            mic = { FakeMic(listOf(0.3f, 0.3f, 0.3f), onEmpty = { dictation.stop() }) }, worker = { it.run() },
        )
        dictation.start()
        looper.idle()
        assertEquals("今天天气怎么样？要不要带伞，明天呢", draft.input)
    }

    @Test
    fun `音量：安静是 0，大声接近 1`() {
        assertEquals(0f, speechLevel(FloatArray(800), 800), 0f)
        assertEquals(0f, speechLevel(FloatArray(800) { 0.0005f }, 800), 0f)
        assertTrue(speechLevel(FloatArray(800) { if (it % 2 == 0) 0.3f else -0.3f }, 800) > 0.8f)
        val middle = speechLevel(FloatArray(800) { if (it % 2 == 0) 0.02f else -0.02f }, 800)
        assertTrue(middle in 0.3f..0.7f)
    }
}
