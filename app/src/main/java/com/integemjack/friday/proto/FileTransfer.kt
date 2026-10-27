package com.integemjack.friday.proto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream

/**
 * 远程文件读写（REMOTE.md §6）：
 * - `file.read {media, offset, length}`：读消息附件（relativePath 在服务端媒体目录之内）
 * - `fs.read {path, offset, length}`：读这台机器上任意文件（工具参数里的本地媒体路径）
 * - `file.upload {uploadID, name, offset, data(base64), last}`：分块上传，回执给服务端上的路径
 * 每块最多 1 MB（同 Qt RemoteProtocol::chunkBytes）。独立成一个类：只需要一个 RpcClient，便于测试。
 */
interface RemoteFiles {
    suspend fun readMediaChunk(relativePath: String, offset: Long, length: Int = CHUNK_BYTES): FileChunk
    suspend fun readFileChunk(path: String, offset: Long, length: Int = CHUNK_BYTES): FileChunk
}

class FileTransfer(private val rpc: RpcClient) : RemoteFiles {

    override suspend fun readMediaChunk(relativePath: String, offset: Long, length: Int): FileChunk {
        val result = rpc.request("file.read", buildJsonObject {
            put("media", relativePath)
            put("offset", offset)
            put("length", length)
        }).jsonObject
        return FileChunk.fromJson(result)
    }

    override suspend fun readFileChunk(path: String, offset: Long, length: Int): FileChunk {
        val result = rpc.request("fs.read", buildJsonObject {
            put("path", path)
            put("offset", offset)
            put("length", length)
        }).jsonObject
        return FileChunk.fromJson(result)
    }

    /**
     * 上传一个本地文件，返回服务端上的路径（用在 runner.submit 的 attachments 里）。
     * 服务端要求 offset 正好等于它已有的字节数，所以一块一块顺序传、等回执再传下一块。
     */
    suspend fun uploadFile(file: File, onProgress: (Float) -> Unit = {}): String {
        val total = file.length()
        val uploadID = java.util.UUID.randomUUID().toString()
        val name = file.name
        var path: String? = null
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            var offset = 0L
            while (true) {
                val read = readFully(input, buffer)
                if (read <= 0 && offset > 0) break
                val last = offset + read >= total
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                val result = rpc.request("file.upload", buildJsonObject {
                    put("uploadID", uploadID)
                    put("name", name)
                    put("offset", offset)
                    put("data", java.util.Base64.getEncoder().encodeToString(chunk))
                    put("last", last)
                }).jsonObject
                if (last) {
                    path = result.str("path")
                    break
                }
                offset += read
                if (total > 0) onProgress((offset.toFloat() / total).coerceIn(0f, 1f))
                if (read <= 0) break
            }
        }
        onProgress(1f)
        return path ?: throw Exception("上传没拿到服务端路径（文件可能在传的中途变短了）")
    }

    /** 把流读满一块（InputStream.read 不保证一次读够） */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read <= 0) break
            total += read
        }
        return total
    }
}
