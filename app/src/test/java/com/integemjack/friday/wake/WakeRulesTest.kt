package com.integemjack.friday.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** 静音门限、「前面要停一下」、认出来的词算不算（纯逻辑，不碰麦克风和模型） */
class WakeRulesTest {

    /** 一块 100 毫秒的正弦波，幅度按 dBFS 给（正弦的有效值比峰值低 3 dB） */
    private fun tone(db: Float, n: Int = WakeTuning.CHUNK): FloatArray {
        val amp = (10.0.pow(db / 20.0) * Math.sqrt(2.0)).toFloat()
        return FloatArray(n) { (amp * sin(2 * PI * 300 * it / WakeTuning.SAMPLE_RATE)).toFloat() }
    }

    @Test
    fun `能量按 dBFS 算，全零是 -120`() {
        assertEquals(-20f, levelDb(tone(-20f)), 0.3f)
        assertEquals(-120f, levelDb(FloatArray(160)), 0f)
    }

    @Test
    fun `百分位跟 numpy 一样线性插值`() {
        val v = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        assertEquals(3f, percentile(v, 50f), 1e-6f)
        assertEquals(4.2f, percentile(v, 80f), 1e-6f)
        assertEquals(1.4f, percentile(v, 10f), 1e-6f)
    }

    @Test
    fun `安静时门关着，开口就开，说完等一点五秒才关`() {
        val gate = EnergyGate()
        repeat(20) { assertEquals(EnergyGate.Step.Idle, gate.push(-70f)) }
        assertEquals(EnergyGate.Step.Start, gate.push(-30f))
        assertEquals(EnergyGate.Step.Feed, gate.push(-28f))
        // 停下来：还要再喂 15 块（让模型把最后一个字认完）
        repeat(WakeTuning.HANGOVER_CHUNKS - 1) { assertEquals(EnergyGate.Step.Feed, gate.push(-70f)) }
        assertEquals(EnergyGate.Step.End, gate.push(-70f))
        assertEquals(EnergyGate.Step.Idle, gate.push(-70f))
    }

    @Test
    fun `一直响的风扇声被底噪吃掉，门不开；比它响 9 dB 以上的说话才开`() {
        val gate = EnergyGate()
        repeat(60) { gate.push(-45f) }
        assertEquals(-45f, gate.floorDb, 0.01f)
        assertFalse(gate.isOpen)
        assertEquals(EnergyGate.Step.Idle, gate.push(-40f))
        assertEquals(EnergyGate.Step.Start, gate.push(-34f))
    }

    @Test
    fun `太轻的声音（低于 -62 dBFS）在很安静的房间里也不算说话`() {
        val gate = EnergyGate()
        repeat(30) { gate.push(-90f) }
        assertEquals(EnergyGate.Step.Idle, gate.push(-65f))
        assertEquals(EnergyGate.Step.Start, gate.push(-55f))
    }

    @Test
    fun `说话中间的停顿让底噪留在低处，长句子不会把门关上`() {
        val gate = EnergyGate()
        repeat(30) { gate.push(-70f) }
        assertEquals(EnergyGate.Step.Start, gate.push(-30f))
        // 10 秒的话：每秒停 0.2 秒
        repeat(100) { i -> assertEquals("第 $i 块", EnergyGate.Step.Feed, gate.push(if (i % 10 < 8) -30f else -68f)) }
    }

    @Test
    fun `能量记录按 10 毫秒一帧、按绝对帧号取，太早的取不到`() {
        val history = LevelHistory(capacity = 50)
        history.push(tone(-20f))
        history.push(FloatArray(WakeTuning.CHUNK))
        assertEquals(20L, history.frames)
        assertEquals(-20f, history.range(0, 10).max(), 0.5f)
        assertEquals(-120f, history.range(10, 20).max(), 0f)
        repeat(5) { history.push(FloatArray(WakeTuning.CHUNK)) }
        assertEquals(0, history.range(0, 10).size) // 70 帧 > 容量 50
        assertEquals(10, history.range(60, 80).size) // 只有到 70 的
    }

    /** 按 10 毫秒一帧摆一段能量：[segments] 是（毫秒，dB） */
    private fun historyOf(vararg segments: Pair<Int, Float>): LevelHistory {
        val history = LevelHistory()
        for ((ms, db) in segments) {
            repeat(ms / 100) { history.push(tone(db)) }
        }
        return history
    }

    @Test
    fun `「星期五」前面停过一下才算`() {
        // 安静 1 秒，然后说 0.8 秒
        val paused = historyOf(1000 to -70f, 800 to -25f)
        assertTrue(pausedBefore(paused, wordStartFrame = 100))
        // 前面一直在说（今天是星期五）
        val running = historyOf(1000 to -26f, 800 to -25f)
        assertFalse(pausedBefore(running, wordStartFrame = 100))
        // 前面在说、只停了 100 毫秒（比 350 毫秒短）：大部分时间还在说，不算
        val brief = historyOf(900 to -26f, 100 to -70f, 800 to -25f)
        assertFalse(pausedBefore(brief, wordStartFrame = 100))
        // 停了 300 毫秒：算（「喂，星期五」）
        val comma = historyOf(700 to -26f, 300 to -70f, 800 to -25f)
        assertTrue(pausedBefore(comma, wordStartFrame = 100))
    }

    @Test
    fun `刚开始听、前面没有记录时当停过`() {
        val history = historyOf(800 to -25f)
        assertTrue(pausedBefore(history, wordStartFrame = 0))
    }

    @Test
    fun `只认「星期五」：诱饵词、没有时间的都不算；时间按这一段的起点换成绝对帧`() {
        // 安静 1 秒后开口：这一段从第 0.5 秒（第 8000 个采样）开始，模型说词在段里第 0.5 秒开始 → 绝对第 1.0 秒
        val history = historyOf(1000 to -70f, 800 to -25f)
        assertTrue(shouldWake(SpottedWord(WAKE_WORD, 0.5f), segmentStartSample = 8_000, history = history))
        assertFalse(shouldWake(SpottedWord("星期四", 0.5f), segmentStartSample = 8_000, history = history))
        assertFalse(shouldWake(SpottedWord(WAKE_WORD, null), segmentStartSample = 8_000, history = history))
        // 同一个词要是落在说话中间（绝对第 1.5 秒）就不算
        assertFalse(shouldWake(SpottedWord(WAKE_WORD, 1.0f), segmentStartSample = 8_000, history = history))
    }

    @Test
    fun `唤醒词表里第一行是星期五，后面是挡近音的诱饵词`() {
        val lines = java.io.File("src/main/assets/kws/keywords.txt").readLines().filter { it.isNotBlank() }
        assertEquals("x īng q ī w ǔ @$WAKE_WORD", lines.first())
        assertTrue(lines.size > 1)
        assertTrue(lines.drop(1).none { it.endsWith("@$WAKE_WORD") })
    }
}
