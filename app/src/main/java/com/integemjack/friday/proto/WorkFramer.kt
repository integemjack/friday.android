package com.integemjack.friday.proto

import java.util.concurrent.atomic.AtomicLong

/** 一条要发出去的 DataChannel 消息：binary = false 是整条文本帧，true 是一片分片 */
class WorkOutFrame private constructor(
    val binary: Boolean,
    val text: String?,
    val bytes: ByteArray?,
) {
    companion object {
        internal fun text(text: String) = WorkOutFrame(binary = false, text = text, bytes = null)
        internal fun chunk(bytes: ByteArray) = WorkOutFrame(binary = true, text = null, bytes = bytes)
    }
}

/**
 * WebRTC 工作通道上的分帧（REMOTE.md §2「WebRTC 工作通道」，和 Qt `WorkFramer`、桌面端
 * `workFramer.ts` 是同一套协议）：
 *
 * - 一条 JSON-RPC 消息（UTF-8）不超过 [WHOLE_LIMIT]：直接一条 DataChannel 文本消息；
 * - 超过：切成若干片，每片一条二进制消息：
 * ```
 *   偏移 0  'F' 'W' '1'   3 字节 magic
 *   3       sid           8 字节大端（每条消息一个）
 *   11      seq           4 字节大端（从 0 起）
 *   15      total         4 字节大端（一共几片）
 *   19      payload       ≤ CHUNK 字节（UTF-8 的一段）
 * ```
 * - magic 不对、seq 重复 / 越界、total 对不上、拼出来超过 [MESSAGE_LIMIT]（收到一半的几条加起来也算）：
 *   协议错，断开。
 */
object WorkFramerCodec {

    /** ≤ 这个字节数整条直接发（一条文本帧）；留了余量，远在 256 KB 的硬限之下 */
    const val WHOLE_LIMIT = 200 * 1024

    /** 分片时每片 payload 的上限 */
    const val CHUNK = 128 * 1024

    /** 一条消息的上限，也是收到一半的消息加起来的上限 */
    const val MESSAGE_LIMIT = 64L * 1024 * 1024

    /** 分片的头长度 */
    const val HEADER = 19

    private const val MAGIC0: Byte = 0x46 // 'F'
    private const val MAGIC1: Byte = 0x57 // 'W'
    private const val MAGIC2: Byte = 0x31 // '1'

    /** sid 每次递增，同一条连接里不重复 */
    private val nextSid = AtomicLong(0)

    /** 拆：小消息一条文本帧；大消息若干二进制片 */
    fun encode(message: String): List<WorkOutFrame> {
        val bytes = message.toByteArray(Charsets.UTF_8)
        if (bytes.size <= WHOLE_LIMIT) return listOf(WorkOutFrame.text(message))
        val sid = nextSid.incrementAndGet()
        val total = (bytes.size + CHUNK - 1) / CHUNK
        val frames = ArrayList<WorkOutFrame>(total)
        for (seq in 0 until total) {
            val start = seq * CHUNK
            val end = minOf(bytes.size, start + CHUNK)
            val frame = ByteArray(HEADER + (end - start))
            frame[0] = MAGIC0
            frame[1] = MAGIC1
            frame[2] = MAGIC2
            putLongBE(frame, 3, sid)
            putIntBE(frame, 11, seq)
            putIntBE(frame, 15, total)
            System.arraycopy(bytes, start, frame, HEADER, end - start)
            frames += WorkOutFrame.chunk(frame)
        }
        return frames
    }

    private fun putLongBE(buffer: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) buffer[offset + i] = ((value ushr (56 - 8 * i)) and 0xFF).toByte()
    }

    private fun putIntBE(buffer: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) buffer[offset + i] = ((value ushr (24 - 8 * i)) and 0xFF).toByte()
    }
}

/**
 * 收：喂一帧，拼出完整消息。文本帧直接就是一条消息；二进制片按 sid 攒。
 *
 * 一个连接一个实例（服务端也一样：一条 DataChannel 一个 framer）。
 */
class WorkFramer {

    sealed interface Feed {
        /** 拼出一条完整的消息 */
        class Ready(val message: String) : Feed

        /** 还差分片 */
        data object More : Feed

        /** 协议错：该断开 */
        data object Invalid : Feed
    }

    private class Parts(val total: Int) {
        val chunks = HashMap<Int, ByteArray>()
        var received = 0L
        var seen = 0
        var at = 0L
    }

    private val pending = HashMap<Long, Parts>()
    private var pendingBytes = 0L

    /** 喂一帧：binary = false 是文本帧（整条消息），true 是分片 */
    fun feed(binary: Boolean, frame: ByteArray): Feed {
        if (!binary) {
            // 文本帧不参与拼装；和 Qt 一样，攒着一半的分片留着（它们自成一组）
            return Feed.Ready(String(frame, Charsets.UTF_8))
        }
        if (frame.size < WorkFramerCodec.HEADER) return Feed.Invalid
        if (frame[0] != 'F'.code.toByte() || frame[1] != 'W'.code.toByte() || frame[2] != '1'.code.toByte()) {
            return Feed.Invalid
        }
        val sid = readLongBE(frame, 3)
        val seq = readIntBE(frame, 11)
        val total = readIntBE(frame, 15)
        if (total < 2 || total.toLong() * WorkFramerCodec.CHUNK > WorkFramerCodec.MESSAGE_LIMIT) return Feed.Invalid
        if (seq < 0 || seq >= total) return Feed.Invalid

        val entry = pending.getOrPut(sid) { Parts(total) }
        if (entry.total != total) return Feed.Invalid
        if (entry.chunks.containsKey(seq)) return Feed.Invalid
        val payload = frame.copyOfRange(WorkFramerCodec.HEADER, frame.size)
        entry.chunks[seq] = payload
        entry.received += payload.size
        entry.seen += 1
        entry.at = System.currentTimeMillis()
        pendingBytes += payload.size
        if (pendingBytes > WorkFramerCodec.MESSAGE_LIMIT) return Feed.Invalid
        if (entry.seen < entry.total) return Feed.More

        pending.remove(sid)
        pendingBytes -= entry.received
        val assembled = ByteArray(entry.received.toInt())
        var offset = 0
        for (index in 0 until entry.total) {
            val part = entry.chunks[index] ?: return Feed.Invalid
            System.arraycopy(part, 0, assembled, offset, part.size)
            offset += part.size
        }
        return Feed.Ready(String(assembled, Charsets.UTF_8))
    }

    private fun readLongBE(buffer: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (buffer[offset + i].toLong() and 0xFF)
        return value
    }

    private fun readIntBE(buffer: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until 4) value = (value shl 8) or (buffer[offset + i].toInt() and 0xFF)
        return value
    }
}