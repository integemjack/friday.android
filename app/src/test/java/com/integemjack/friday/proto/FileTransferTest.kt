package com.integemjack.friday.proto

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 文件传输与媒体加载的测试：用一个假的 JSON-RPC 服务端（直接喂 RpcClient），
 * 真跑分块上传 / 分块下载 / 缓存 / 上限 / 去重这些容易静默出错的逻辑。
 */
class FileTransferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 假服务端：收到请求就按 serve 算出回执，直接喂回 RpcClient */
    private class FakeRpc(
        private val serve: (method: String, params: JsonObject?) -> JsonObject,
    ) {
        val requests = mutableListOf<JsonObject>()

        /** 设了就让某个方法回错误（测报错路径） */
        var errorFor: ((method: String) -> Pair<Int, String>?)? = null

        val rpc: RpcClient = RpcClient(sendFn = { text -> onSend(text) }, closeFn = {})

        private fun onSend(text: String) {
            val request = Json.parseToJsonElement(text).jsonObject
            requests.add(request)
            val id = request["id"] ?: return
            val method = (request["method"] as JsonPrimitive).content
            val params = request["params"] as? JsonObject
            val reply = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                val error = errorFor?.invoke(method)
                if (error != null) {
                    put("error", buildJsonObject {
                        put("code", error.first)
                        put("message", error.second)
                    })
                } else {
                    put("result", serve(method, params))
                }
            }
            rpc.handleMessage(reply.toString())
        }

        fun method(at: Int): String = (requests[at]["method"] as JsonPrimitive).content
        fun params(at: Int): JsonObject? = requests[at]["params"] as? JsonObject
        fun countOf(method: String): Int = requests.count { (it["method"] as JsonPrimitive).content == method }
    }

    private fun JsonObject?.field(name: String): JsonPrimitive? = this?.get(name) as? JsonPrimitive

    // MARK: 上传

    @Test
    fun `上传按 1MB 分块，offset 递增，最后一块带 last，字节一个不差`() = runBlocking {
        val size = CHUNK_BYTES * 2 + 12345
        val original = ByteArray(size) { (it % 251).toByte() }
        val file = tmp.newFile("photo.png").apply { writeBytes(original) }

        val received = mutableListOf<Pair<Long, ByteArray>>()
        val lastFlags = mutableListOf<Boolean>()
        val fake = FakeRpc { method, params ->
            assertEquals("file.upload", method)
            val data = params.field("data")!!.content
            val bytes = if (data.isEmpty()) ByteArray(0) else java.util.Base64.getDecoder().decode(data)
            received.add(params.field("offset")!!.content.toLong() to bytes)
            lastFlags.add(params.field("last")!!.content.toBoolean())
            if (lastFlags.last()) buildJsonObject { put("path", "/home/u/.friday/uploads/photo.png") }
            else buildJsonObject { put("size", received.sumOf { it.second.size.toLong() }) }
        }
        val transfer = FileTransfer(fake.rpc)

        val path = transfer.uploadFile(file)

        assertEquals("/home/u/.friday/uploads/photo.png", path)
        assertEquals(3, received.size)
        assertEquals(listOf(0L, CHUNK_BYTES.toLong(), CHUNK_BYTES * 2L), received.map { it.first })
        assertEquals(listOf(false, false, true), lastFlags)
        // 拼回来跟原文件一模一样
        val merged = received.flatMap { it.second.toList() }.toByteArray()
        assertArrayEquals(original, merged)
        // 同一个 uploadID、同一个文件名
        val ids = fake.requests.mapNotNull { (it["params"] as? JsonObject)?.field("uploadID")?.content }.toSet()
        assertEquals(1, ids.size)
        assertEquals("photo.png", fake.params(0).field("name")!!.content)
    }

    @Test
    fun `空文件一次传完就说 last`() = runBlocking {
        val file = tmp.newFile("empty.txt")
        val fake = FakeRpc { _, _ -> buildJsonObject { put("path", "/tmp/empty.txt") } }
        val transfer = FileTransfer(fake.rpc)

        val path = transfer.uploadFile(file)

        assertEquals("/tmp/empty.txt", path)
        assertEquals(1, fake.countOf("file.upload"))
        assertEquals("", fake.params(0).field("data")!!.content)
        assertTrue(fake.params(0).field("last")!!.content.toBoolean())
        assertEquals(0L, fake.params(0).field("offset")!!.content.toLong())
    }

    @Test
    fun `上传进度从 0 报到 1`() = runBlocking {
        val file = tmp.newFile("big.bin").apply { writeBytes(ByteArray(CHUNK_BYTES * 3 + 7)) }
        val fake = FakeRpc { _, params ->
            if (params.field("last")!!.content.toBoolean()) buildJsonObject { put("path", "/tmp/big.bin") }
            else buildJsonObject { put("size", 1) }
        }
        val progress = mutableListOf<Float>()

        FileTransfer(fake.rpc).uploadFile(file) { progress.add(it) }

        assertEquals(1f, progress.last(), 0.0001f)
        assertTrue("进度要单调不降", progress.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(progress.size >= 3)
    }

    @Test
    fun `服务端报错时上传抛出来，不静默当成功`() = runBlocking {
        val file = tmp.newFile("a.png").apply { writeBytes(ByteArray(10)) }
        val fake = FakeRpc { _, _ -> buildJsonObject { put("size", 10) } }
        fake.errorFor = { method ->
            if (method == "file.upload") RpcErrors.NotAllowed to "这台不让上传" else null
        }
        try {
            FileTransfer(fake.rpc).uploadFile(file)
            fail("应该抛出来")
        } catch (e: RpcException) {
            assertEquals(RpcErrors.NotAllowed, e.code)
            assertTrue(e.message!!.contains("不让上传"))
        }
    }

    @Test
    fun `最后一块没拿到 path 时报错（文件中途变短的情况）`() = runBlocking {
        val file = tmp.newFile("a.png").apply { writeBytes(ByteArray(10)) }
        // 服务端只回 size、不回 path
        val fake = FakeRpc { _, _ -> buildJsonObject { put("size", 10) } }
        try {
            FileTransfer(fake.rpc).uploadFile(file)
            fail("应该抛出来")
        } catch (e: Exception) {
            assertNotNull(e.message)
        }
    }

    // MARK: 下载（读一段）

    @Test
    fun `读附件用 file_read 的 media 参数，回执解 base64`() = runBlocking {
        val fake = FakeRpc { method, params ->
            assertEquals("file.read", method)
            assertEquals("2026/01/a.png", params.field("media")!!.content)
            assertEquals(1024L, params.field("offset")!!.content.toLong())
            assertEquals(4096, params.field("length")!!.content.toInt())
            buildJsonObject {
                put("data", java.util.Base64.getEncoder().encodeToString("abc".toByteArray()))
                put("size", 3)
                put("eof", true)
            }
        }
        val chunk = FileTransfer(fake.rpc).readMediaChunk("2026/01/a.png", 1024, 4096)
        assertEquals("abc", String(chunk.data))
        assertEquals(3L, chunk.size)
        assertTrue(chunk.eof)
    }

    @Test
    fun `读任意文件用 fs_read 的 path 参数`() = runBlocking {
        val fake = FakeRpc { method, params ->
            assertEquals("fs.read", method)
            assertEquals("/home/u/proj/out/a.png", params.field("path")!!.content)
            buildJsonObject {
                put("data", java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
                put("size", 3)
                put("eof", false)
            }
        }
        val chunk = FileTransfer(fake.rpc).readFileChunk("/home/u/proj/out/a.png", 0)
        assertArrayEquals(byteArrayOf(1, 2, 3), chunk.data)
        assertFalse(chunk.eof)
        // 不写 length 时按一块 1 MB 要
        assertEquals(CHUNK_BYTES, fake.params(0).field("length")!!.content.toInt())
    }

    // MARK: 媒体加载与缓存

    /** 假的远程文件：按块吐 content，记录被读了几次、读的是哪个路径 */
    private class FakeFiles(private val content: ByteArray, private val serveChunk: Int = 64 * 1024) : RemoteFiles {
        var reads = 0
        var lastPath: String? = null
        var failWith: String? = null
        var reportedSize: Long = content.size.toLong()

        override suspend fun readMediaChunk(relativePath: String, offset: Long, length: Int): FileChunk {
            reads++
            lastPath = relativePath
            return chunkAt(offset, length)
        }

        override suspend fun readFileChunk(path: String, offset: Long, length: Int): FileChunk {
            reads++
            lastPath = path
            return chunkAt(offset, length)
        }

        private suspend fun chunkAt(offset: Long, length: Int): FileChunk {
            failWith?.let { throw Exception(it) }
            delay(1) // 让并发去重那条测试真的能并发上
            val start = offset.toInt().coerceIn(0, content.size)
            val end = minOf(content.size, start + minOf(length, serveChunk))
            return FileChunk(content.copyOfRange(start, end), reportedSize, end >= content.size)
        }
    }

    private fun attachment(path: String = "2026/01/a.png", kind: String = "image") =
        MediaSource.Attachment(
            MediaAttachment("m1", kind, path, path.substringAfterLast('/'), "image/png", 100)
        )

    @Test
    fun `分块拉回来拼成完整文件并缓存，第二次不再走网络`() = runBlocking {
        val content = ByteArray(200_000) { (it % 199).toByte() }
        val files = FakeFiles(content)
        val cache = tmp.newFolder("media")
        val loader = MediaLoader(files, cache)
        val source = attachment()

        val first = loader.load(source)
        assertTrue(first is MediaResult.Ready)
        assertArrayEquals(content, (first as MediaResult.Ready).file.readBytes())
        assertTrue(files.reads > 1) // 200 KB / 64 KB → 多块
        assertEquals("2026/01/a.png", files.lastPath)

        val readsAfterFirst = files.reads
        val second = loader.load(source)
        assertTrue(second is MediaResult.Ready)
        assertEquals("缓存命中就不该再读远程", readsAfterFirst, files.reads)
        assertEquals((first as MediaResult.Ready).file, (second as MediaResult.Ready).file)
        // 缓存文件名不带路径分隔符
        assertFalse(first.file.name.contains('/') || first.file.name.contains('\\'))
    }

    @Test
    fun `工具里的相对路径按会话工作目录拼成绝对路径再读`() = runBlocking {
        val files = FakeFiles(ByteArray(10) { 7 })
        val loader = MediaLoader(files, tmp.newFolder("media"))

        loader.load(MediaSource.ToolPath("image", "out/a.png", "/home/u/proj"))

        assertEquals("/home/u/proj/out/a.png", files.lastPath)
    }

    @Test
    fun `超过上限不拉，告诉用户多大`() = runBlocking {
        val files = FakeFiles(ByteArray(1024), serveChunk = 1024).apply { reportedSize = 30L * 1024 * 1024 }
        val loader = MediaLoader(files, tmp.newFolder("media"))

        val result = loader.load(attachment())

        assertTrue(result is MediaResult.TooBig)
        assertEquals(30L * 1024 * 1024, (result as MediaResult.TooBig).bytes)
        assertNull("不该留下半截文件", loader.cachedFile(attachment()))
    }

    @Test
    fun `读失败时不留半截文件，下次还能重试`() = runBlocking {
        val files = FakeFiles(ByteArray(5000)).apply { failWith = "连接断了" }
        val cache = tmp.newFolder("media")
        val loader = MediaLoader(files, cache)
        val source = attachment()

        val result = loader.load(source)
        assertTrue(result is MediaResult.Failed)
        assertTrue((result as MediaResult.Failed).message!!.contains("连接断了"))
        assertNull(loader.cachedFile(source))
        assertEquals("不能留下 .part", 0, cache.listFiles()?.size ?: 0)

        // 恢复后重试能成
        files.failWith = null
        assertTrue(loader.load(source) is MediaResult.Ready)
    }

    @Test
    fun `同一个源并发只拉一次`() = runBlocking {
        val files = FakeFiles(ByteArray(200_000))
        val loader = MediaLoader(files, tmp.newFolder("media"))
        val source = attachment()

        val results = coroutineScope {
            val a = async { loader.load(source) }
            val b = async { loader.load(source) }
            val c = async { loader.load(source) }
            listOf(a.await(), b.await(), c.await())
        }

        assertTrue(results.all { it is MediaResult.Ready })
        val onePass = (200_000 + 64 * 1024 - 1) / (64 * 1024)
        assertEquals("三份请求合成一趟下载", onePass, files.reads)
    }

    @Test
    fun `不同的源各拉各的，不串图`() = runBlocking {
        val files = FakeFiles(ByteArray(100) { 3 })
        val cache = tmp.newFolder("media")
        val loader = MediaLoader(files, cache)

        val a = loader.load(attachment("x/a.png"))
        val b = loader.load(attachment("y/b.png"))

        assertTrue(a is MediaResult.Ready && b is MediaResult.Ready)
        assertNotSame((a as MediaResult.Ready).file, (b as MediaResult.Ready).file)
        assertEquals(2, cache.listFiles()?.size)
    }

    private fun assertNotSame(a: Any, b: Any) = assertFalse("应该是两个不同的文件", a == b)
}
