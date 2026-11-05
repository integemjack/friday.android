package com.integemjack.friday.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * 听的那条线程，整条走一遍：假麦克风按剧本出声音（安静 / 说话，按 100 毫秒一块），
 * 假模型在剧本说的那一块「认出」词，时间按真模型的口径（从这一段第一个喂进去的采样算起）。
 * 看静音门限、开口前那一点（pre-roll）、前面要停一下、冷却、念播报时不认，合起来对不对
 */
class WakeListenerTest {

    /** 剧本：每块是安静还是说话；第 [index] 块的第一个采样里藏着块号（给假模型认是哪一块） */
    private class ScriptMic(private val loud: List<Boolean>) : MicSource {
        val done = CountDownLatch(1)
        @Volatile var chunks = 0
        var starts = 0
        override fun start(): Boolean {
            starts++
            return true
        }

        override fun read(buffer: FloatArray): Int {
            val index = chunks
            if (index >= loud.size) {
                done.countDown()
                Thread.sleep(2)
                buffer.fill(0f)
                return buffer.size
            }
            val db = if (loud[index]) -25.0 else -70.0
            val amp = (10.0.pow(db / 20) * Math.sqrt(2.0)).toFloat()
            for (i in buffer.indices) buffer[i] = (amp * sin(2 * PI * 300 * i / WakeTuning.SAMPLE_RATE)).toFloat()
            buffer[0] = (index + 1) * MARK
            chunks = index + 1
            return buffer.size
        }

        override fun stop() {}
    }

    /** 在第 [recognizeAt] 块喂进来时认出 [keyword]，词从绝对第 [wordStartChunk] 块开始 */
    private class ScriptEngine(
        private val keyword: String,
        private val wordStartChunk: Int,
        private val recognizeAt: Set<Int>,
    ) : KeywordEngine {
        private var segmentFirst = -1
        val fed = mutableListOf<Int>()

        override fun newSegment() {
            segmentFirst = -1
        }

        override fun accept(samples: FloatArray): SpottedWord? {
            val index = Math.round(samples[0] / MARK) - 1
            fed += index
            if (segmentFirst < 0) segmentFirst = index
            if (index !in recognizeAt) return null
            val start = (wordStartChunk - segmentFirst) * WakeTuning.CHUNK / WakeTuning.SAMPLE_RATE.toFloat()
            return SpottedWord(keyword, start)
        }

        override fun release() {}
    }

    private fun run(
        loud: List<Boolean>,
        engine: ScriptEngine,
        /** 正在读的是第几块（0 起）时算忙 */
        busy: (Int) -> Boolean = { false },
    ): Pair<Int, WakeListener> {
        val mic = ScriptMic(loud)
        val woke = AtomicInteger()
        val listener = WakeListener(
            engineFactory = { engine },
            micFactory = { mic },
            busy = { busy(mic.chunks - 1) },
            onWake = { woke.incrementAndGet() },
            // 时间跟着剧本走（读了多少块）：冷却按剧本里的时间算
            clock = { mic.chunks * 100L },
        )
        listener.start()
        assertTrue("剧本没放完", mic.done.await(10, TimeUnit.SECONDS))
        listener.stop()
        return woke.get() to listener
    }

    private fun script(vararg parts: Pair<Int, Boolean>): List<Boolean> = parts.flatMap { (n, loud) -> List(n) { loud } }

    @Test
    fun `安静一秒后说「星期五」：叫醒一次，开口前那一点也喂进去了`() {
        // 0–1 秒安静，1.0–1.8 秒说，1.6 秒认出来
        val engine = ScriptEngine(WAKE_WORD, wordStartChunk = 10, recognizeAt = setOf(16))
        val (woke, listener) = run(script(10 to false, 8 to true, 30 to false), engine)
        assertEquals(1, woke)
        assertTrue(listener.heard.value!!.woke)
        // 门是第 10 块开的：前面 6 块（4–9）先喂进去
        assertEquals((4..16).toList(), engine.fed.take(13))
    }

    @Test
    fun `安静时不喂模型`() {
        val engine = ScriptEngine(WAKE_WORD, wordStartChunk = 0, recognizeAt = emptySet())
        run(script(40 to false), engine)
        assertTrue(engine.fed.isEmpty())
    }

    @Test
    fun `句子中间的「星期五」不叫醒，写明为什么`() {
        // 2 秒开始说，一直说到 4 秒，「星期五」从 3.0 秒开始、3.6 秒认出来
        val engine = ScriptEngine(WAKE_WORD, wordStartChunk = 30, recognizeAt = setOf(36))
        val (woke, listener) = run(script(20 to false, 20 to true, 30 to false), engine)
        assertEquals(0, woke)
        val heard = listener.heard.value!!
        assertFalse(heard.woke)
        assertTrue(heard.reason!!.contains("前面没停顿"))
    }

    @Test
    fun `诱饵词认出来了也不叫醒`() {
        val engine = ScriptEngine("星期四", wordStartChunk = 10, recognizeAt = setOf(16))
        val (woke, listener) = run(script(10 to false, 8 to true, 30 to false), engine)
        assertEquals(0, woke)
        assertEquals("星期四", listener.heard.value!!.keyword)
    }

    @Test
    fun `叫醒以后 4 秒里再说一遍不算`() {
        // 第一次 1.0 秒说、1.6 秒认出；停 0.7 秒，2.5 秒再说、3.1 秒认出（离上次 1.5 秒）；再过 5 秒第三次
        val twice = object : KeywordEngine {
            private var segmentFirst = -1
            override fun newSegment() {
                segmentFirst = -1
            }

            override fun accept(samples: FloatArray): SpottedWord? {
                val index = Math.round(samples[0] / MARK) - 1
                if (segmentFirst < 0) segmentFirst = index
                val start = when (index) {
                    16 -> 10
                    31 -> 25
                    86 -> 80
                    else -> return null
                }
                return SpottedWord(WAKE_WORD, (start - segmentFirst) * 0.1f)
            }

            override fun release() {}
        }
        val mic = ScriptMic(script(10 to false, 8 to true, 7 to false, 8 to true, 47 to false, 8 to true, 30 to false))
        val woke = AtomicInteger()
        val listener = WakeListener({ twice }, { mic }, { false }, { woke.incrementAndGet() }, clock = { mic.chunks * 100L })
        listener.start()
        assertTrue(mic.done.await(10, TimeUnit.SECONDS))
        listener.stop()
        assertEquals(2, woke.get())
    }

    @Test
    fun `在念播报时不认（不喂模型）`() {
        val engine = ScriptEngine(WAKE_WORD, wordStartChunk = 10, recognizeAt = setOf(16))
        val (woke, _) = run(script(10 to false, 8 to true, 30 to false), engine, busy = { it in 8..20 })
        assertEquals(0, woke)
        assertTrue(engine.fed.none { it in 8..20 })
    }

    @Test
    fun `这台手机跑不了模型：状态写明，不开麦克风`() {
        var micOpened = false
        val listener = WakeListener(
            engineFactory = { throw EngineUnavailable("这台手机跑不了唤醒模型（UnsatisfiedLinkError）") },
            micFactory = {
                micOpened = true
                ScriptMic(emptyList())
            },
            busy = { false },
            onWake = {},
        )
        listener.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (listener.status.value !is WakeListener.Status.Unsupported && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val status = listener.status.value
        assertTrue(status is WakeListener.Status.Unsupported)
        assertTrue((status as WakeListener.Status.Unsupported).message.contains("UnsatisfiedLinkError"))
        assertFalse(micOpened)
        // 再 start 也不重试
        listener.start()
        assertFalse(listener.running)
    }

    @Test
    fun `麦克风开不了：写明，过一会儿再试`() {
        val attempts = AtomicInteger()
        val second = CountDownLatch(2)
        val listener = WakeListener(
            engineFactory = { ScriptEngine(WAKE_WORD, 0, emptySet()) },
            micFactory = {
                object : MicSource {
                    override fun start(): Boolean {
                        attempts.incrementAndGet()
                        second.countDown()
                        return false
                    }

                    override fun read(buffer: FloatArray) = -1
                    override fun stop() {}
                }
            },
            busy = { false },
            onWake = {},
            retryMs = 50,
        )
        listener.start()
        assertTrue(second.await(5, TimeUnit.SECONDS))
        assertTrue(listener.status.value is WakeListener.Status.MicFailed)
        listener.stop()
        assertEquals(WakeListener.Status.Stopped, listener.status.value)
        assertTrue(attempts.get() >= 2)
    }

    private companion object {
        const val MARK = 1e-6f
    }
}
