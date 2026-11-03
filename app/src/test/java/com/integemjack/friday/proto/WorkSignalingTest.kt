package com.integemjack.friday.proto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作通道信令状态机（REMOTE.md §2）：拨号 → accepted → offer/answer/ICE → 收尾。
 * 和桌面端 `WorkSignaling.ts` 的原则一致：没被接受就 request_cancel、被接受后 hangup、
 * 被别的设备顶掉不自动重拨。
 */
class WorkSignalingTest {

    private class FakeTimer : WorkSignaling.Timer {
        class Task(val delay: Long, val run: () -> Unit) {
            var cancelled = false
        }

        val tasks = mutableListOf<Task>()

        override fun schedule(delayMs: Long, run: () -> Unit): WorkSignaling.Timer.Cancel {
            val task = Task(delayMs, run)
            tasks += task
            return WorkSignaling.Timer.Cancel { task.cancelled = true }
        }

        fun fire() {
            tasks.filter { !it.cancelled }.forEach { it.run() }
        }
    }

    private class RecordingHost(private val account: Long = 42L) : WorkSignaling.Host {
        val frames = mutableListOf<Triple<Long, String, JsonObject>>()

        override fun accountId(): Long = account

        override fun sendCallFrame(botId: Long, event: String, data: JsonObject) {
            frames += Triple(botId, event, data)
        }

        fun last(type: String): JsonObject? = frames.lastOrNull { it.second == type }?.third
    }

    private class Setup {
        val host = RecordingHost()
        val timer = FakeTimer()
        val events = mutableListOf<WorkSignaling.Event>()
        var requestId = "req-1"
        val signaling = WorkSignaling(
            host = host,
            onEvent = { events += it },
            timers = timer,
            now = { 1_000L },
            newRequestId = { requestId },
        )
    }

    @Test
    fun `dial sends p2p_request with callType work`() {
        val s = Setup()
        s.signaling.dial(7)

        val frame = s.host.last("p2p_request") ?: error("没发 p2p_request")
        assertEquals(7L, s.host.frames.last().first)
        assertEquals("work", frame["callType"]!!.jsonPrimitive.content)
        assertEquals("req-1", frame["requestId"]!!.jsonPrimitive.content)
        assertEquals(7L, frame["toUserId"]!!.jsonPrimitive.content.toLong())
        assertEquals(42L, frame["fromUserId"]!!.jsonPrimitive.content.toLong())
        assertEquals(31_000L, frame["expiresAt"]!!.jsonPrimitive.content.toLong())
        assertEquals("req-1", s.signaling.currentRequestId())
    }

    @Test
    fun `accepted event fires and offer goes out with same requestId`() {
        val s = Setup()
        s.signaling.dial(7)
        s.signaling.onResponse("req-1", accepted = true, reason = "")

        assertEquals(listOf<WorkSignaling.Event>(WorkSignaling.Event.Accepted("req-1")), s.events)

        s.signaling.send("offer", "v=0")
        val signal = s.host.last("p2p_signaling") ?: error("没发 p2p_signaling")
        assertEquals("offer", signal["type"]!!.jsonPrimitive.content)
        assertEquals("v=0", signal["payload"]!!.jsonPrimitive.content)
        assertEquals("req-1", signal["requestId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `decline from a deleted friend is permanent`() {
        val s = Setup()
        s.signaling.dial(7)
        s.signaling.onResponse("req-1", accepted = false, reason = "not_friends")

        val ended = s.events.single() as WorkSignaling.Event.Ended
        assertTrue(ended.permanent)
        assertTrue(ended.reason.contains("好友"))
        assertFalse(s.signaling.isActive)
    }

    @Test
    fun `no answer within 30 seconds ends the dial`() {
        val s = Setup()
        s.signaling.dial(7)
        s.timer.fire()

        val ended = s.events.single() as WorkSignaling.Event.Ended
        assertFalse(ended.permanent)
        assertTrue(ended.reason.contains("30 秒"))
    }

    @Test
    fun `hangup reason replaced means another device took over`() {
        val s = Setup()
        s.signaling.dial(7)
        s.signaling.onResponse("req-1", accepted = true, reason = "")
        s.signaling.onSignal("req-1", "hangup", payload = "", reason = "新的连接来了")

        val ended = s.events.last() as WorkSignaling.Event.Ended
        assertTrue(ended.replaced)
        assertTrue(ended.reason.contains("顶掉"))
    }

    @Test
    fun `incoming frames for another requestId are ignored`() {
        val s = Setup()
        s.signaling.dial(7)
        s.signaling.onResponse("other", accepted = true, reason = "")
        s.signaling.onSignal("other", "answer", "v=0")
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun `hangup before accept sends request_cancel`() {
        val s = Setup()
        s.signaling.dial(7)
        s.signaling.hangup()

        val signal = s.host.last("p2p_signaling") ?: error("没发 p2p_signaling")
        assertEquals("request_cancel", signal["type"]!!.jsonPrimitive.content)
        assertEquals("", s.signaling.currentRequestId())
    }

    @Test
    fun `hangup after accept sends hangup`() {
        val s = Setup()
        s.signaling.dial(7)
        s.signaling.onResponse("req-1", accepted = true, reason = "")
        s.signaling.hangup()

        val signal = s.host.last("p2p_signaling") ?: error("没发 p2p_signaling")
        assertEquals("hangup", signal["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `answer is forwarded as a signal event`() {
        val s = Setup()
        s.signaling.dial(7)
        s.signaling.onResponse("req-1", accepted = true, reason = "")
        s.events.clear()
        s.signaling.onSignal("req-1", "answer", "v=0\r\no=- 1 1 IN IP4 0.0.0.0")

        val signal = s.events.single() as WorkSignaling.Event.Signal
        assertEquals("answer", signal.type)
        assertTrue(signal.payload.startsWith("v=0"))
    }

    @Test
    fun `response for the current request after ended is ignored`() {
        val s = Setup()
        s.signaling.dial(7)
        s.signaling.onResponse("req-1", accepted = false, reason = "error")
        s.events.clear()
        s.signaling.onResponse("req-1", accepted = true, reason = "")
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun `redial uses a fresh requestId`() {
        val s = Setup()
        s.signaling.dial(7)
        s.signaling.hangup()
        s.requestId = "req-2"
        s.signaling.dial(7)
        assertEquals("req-2", s.signaling.currentRequestId())
        assertEquals(2, s.host.frames.count { it.second == "p2p_request" })
    }

    @Test
    fun `accepted flag in expiresAt math is a number`() {
        // 服务端按 expiresAt - timestamp 判断过期（30 秒）：两个字段都必须是数字，不能是字符串
        val s = Setup()
        s.signaling.dial(9)
        val frame = s.host.last("p2p_request")!!
        val timestamp = frame["timestamp"]!!.jsonPrimitive.content.toLong()
        val expiresAt = frame["expiresAt"]!!.jsonPrimitive.content.toLong()
        assertEquals(30_000L, expiresAt - timestamp)
        assertTrue(frame.containsKey("callType"))
    }
}