package com.integemjack.friday.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工作通道分帧（REMOTE.md §2）：和 Qt WorkFramer / 桌面端 workFramer.ts 同一套 */
class WorkFramerTest {

    private fun textFrame(message: String) = WorkFramerCodec.encode(message).single()

    @Test
    fun `small message goes out as one text frame`() {
        val frame = textFrame("{\"jsonrpc\":\"2.0\"}")
        assertTrue(!frame.binary)
        assertEquals("{\"jsonrpc\":\"2.0\"}", frame.text)
    }

    @Test
    fun `exactly at the limit stays a text frame`() {
        val message = "x".repeat(WorkFramerCodec.WHOLE_LIMIT)
        assertTrue(!textFrame(message).binary)
    }

    @Test
    fun `one byte over the limit is chunked`() {
        val frames = WorkFramerCodec.encode("x".repeat(WorkFramerCodec.WHOLE_LIMIT + 1))
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { it.binary })
        assertEquals(2, frames.size)
    }

    @Test
    fun `big message reassembles byte-exact`() {
        // 300 KB 的 JSON：分片、乱序喂进去也要拼回原样
        val payload = (1..300_000).joinToString("") { ('a' + (it % 26)).toString() }
        val message = "{\"id\":3,\"result\":\"$payload\"}"
        val frames = WorkFramerCodec.encode(message)
        assertTrue(frames.size >= 3)

        val framer = WorkFramer()
        var ready: String? = null
        for (frame in frames.reversed()) {
            when (val feed = framer.feed(frame.binary, frame.bytes!!)) {
                is WorkFramer.Feed.Ready -> ready = feed.message
                WorkFramer.Feed.More -> Unit
                WorkFramer.Feed.Invalid -> error("分片被判协议错")
            }
        }
        assertEquals(message, ready)
    }

    @Test
    fun `two big messages do not get mixed up`() {
        val a = "a".repeat(WorkFramerCodec.CHUNK * 2 + 5)
        val b = "b".repeat(WorkFramerCodec.CHUNK * 2 + 9)
        val aFrames = WorkFramerCodec.encode(a)
        val bFrames = WorkFramerCodec.encode(b)
        // 交叉着喂：两条消息的片混在一起也不能串
        val interleaved = mutableListOf<WorkOutFrame>()
        for (index in 0 until maxOf(aFrames.size, bFrames.size)) {
            aFrames.getOrNull(index)?.let { interleaved += it }
            bFrames.getOrNull(index)?.let { interleaved += it }
        }

        val framer = WorkFramer()
        val ready = mutableListOf<String>()
        for (frame in interleaved) {
            val feed = framer.feed(frame.binary, frame.bytes!!)
            if (feed is WorkFramer.Feed.Ready) ready += feed.message
        }
        assertEquals(setOf(a, b), ready.toSet())
    }

    @Test
    fun `bad magic is invalid`() {
        val frame = WorkFramerCodec.encode("y".repeat(WorkFramerCodec.WHOLE_LIMIT + 10)).first().bytes!!
        frame[0] = 'X'.code.toByte()
        assertEquals(WorkFramer.Feed.Invalid, WorkFramer().feed(true, frame))
    }

    @Test
    fun `duplicate chunk is invalid`() {
        val frame = WorkFramerCodec.encode("z".repeat(WorkFramerCodec.WHOLE_LIMIT + 10)).first().bytes!!
        val framer = WorkFramer()
        framer.feed(true, frame)
        assertEquals(WorkFramer.Feed.Invalid, framer.feed(true, frame.copyOf()))
    }

    @Test
    fun `too short frame is invalid`() {
        assertEquals(WorkFramer.Feed.Invalid, WorkFramer().feed(true, ByteArray(6)))
    }
}