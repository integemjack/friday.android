package com.integemjack.friday.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议层纯逻辑的测试（不碰 Android framework，JVM 上直接跑）。
 * 重点是几处容易静默出错的地方：排队消息解析、消息树分支、媒体路径拼接、补丁后媒体不丢。
 */
class ProtoLogicTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // MARK: Runtime / 排队

    @Test
    fun `runtime 解析出排队的消息`() {
        val runtime = Runtime.fromJson(
            obj(
                """{"generating":true,"stopping":false,"queuePaused":true,
                   "queue":[{"id":"q1","text":"接着做","attachments":["/tmp/a.png"],"createdAt":"2026-01-01T00:00:00Z"},
                            {"id":"q2","text":"再来一条","attachments":[],"createdAt":"2026-01-01T00:00:01Z","from":{"kind":"assistant","label":"助手"}}],
                   "pendingApproval":false,"canRetry":true,"canSend":true,"thinkingLevel":"high"}"""
            )
        )
        assertEquals(2, runtime.queue.size)
        assertEquals("q1", runtime.queue[0].id)
        assertEquals("接着做", runtime.queue[0].text)
        assertEquals(listOf("/tmp/a.png"), runtime.queue[0].attachments)
        assertEquals("助手", runtime.queue[1].fromLabel)
        assertTrue(runtime.queuePaused)
        assertTrue(runtime.canRetry)
        assertEquals("high", runtime.thinkingLevel)
    }

    @Test
    fun `runtime 没有 queue 字段时是空列表而不是崩`() {
        val runtime = Runtime.fromJson(obj("""{"generating":false}"""))
        assertTrue(runtime.queue.isEmpty())
        assertFalse(runtime.generating)
        assertTrue(runtime.canSend) // 缺省可发
    }

    // MARK: 消息树 / 分支

    private fun message(id: String, parent: String?, createdAt: String, role: String = "user") = ChatMessage(
        id = id, role = role, blocks = emptyList(), createdAt = createdAt, parentID = parent
    )

    @Test
    fun `一条直线的消息树按父子串起来`() {
        val nodes = listOf(
            message("a", null, "2026-01-01T00:00:00Z"),
            message("b", "a", "2026-01-01T00:00:01Z", "assistant"),
            message("c", "b", "2026-01-01T00:00:02Z"),
        )
        val tree = conversationTree(nodes, emptyMap())
        assertEquals(listOf("a", "b", "c"), tree.path.map { it.id })
        assertTrue(tree.branches.isEmpty())
    }

    @Test
    fun `同父多个孩子时给出分支信息，缺省选最早的那条`() {
        val nodes = listOf(
            message("root", null, "2026-01-01T00:00:00Z"),
            message("b1", "root", "2026-01-01T00:00:01Z", "assistant"),
            message("b2", "root", "2026-01-01T00:00:02Z", "assistant"),
        )
        val tree = conversationTree(nodes, emptyMap())
        assertEquals(listOf("root", "b1"), tree.path.map { it.id })
        val branch = tree.branches["b1"]
        assertNotNull(branch)
        assertEquals(0, branch!!.index)
        assertEquals(2, branch.count)
        assertEquals(listOf("b1", "b2"), branch.siblings)
        assertEquals("root", branch.parentKey)
    }

    @Test
    fun `selectedChildren 选中哪条就走哪条分支`() {
        val nodes = listOf(
            message("root", null, "2026-01-01T00:00:00Z"),
            message("b1", "root", "2026-01-01T00:00:01Z", "assistant"),
            message("b2", "root", "2026-01-01T00:00:02Z", "assistant"),
            message("after", "b2", "2026-01-01T00:00:03Z"),
        )
        val tree = conversationTree(nodes, mapOf("root" to "b2"))
        assertEquals(listOf("root", "b2", "after"), tree.path.map { it.id })
        assertEquals(1, tree.branches["b2"]?.index)
    }

    @Test
    fun `selectedChildren 指向不存在的孩子时退回第一条`() {
        val nodes = listOf(
            message("root", null, "2026-01-01T00:00:00Z"),
            message("b1", "root", "2026-01-01T00:00:01Z", "assistant"),
        )
        val tree = conversationTree(nodes, mapOf("root" to "gone"))
        assertEquals(listOf("root", "b1"), tree.path.map { it.id })
    }

    @Test
    fun `parentID 指向不在这一批里的消息时当根消息处理`() {
        val nodes = listOf(
            message("orphan", "missing", "2026-01-01T00:00:00Z"),
            message("child", "orphan", "2026-01-01T00:00:01Z", "assistant"),
        )
        val tree = conversationTree(nodes, emptyMap())
        // 孤儿的父不在这一批里 → 它自己就是根，往下正常串
        assertEquals(listOf("orphan", "child"), tree.path.map { it.id })
    }

    @Test
    fun `多个根时只跟最早那条链（同桌面版和 Qt 的取路径规则）`() {
        val nodes = listOf(
            message("r1", null, "2026-01-01T00:00:00Z"),
            message("r2", null, "2026-01-01T00:00:05Z"),
        )
        val tree = conversationTree(nodes, emptyMap())
        assertEquals(listOf("r1"), tree.path.map { it.id })
        // 两条根互为分支，能切过去
        assertEquals(2, tree.branches["r1"]?.count)
        assertEquals("", tree.branches["r1"]?.parentKey)
    }

    // MARK: 媒体路径

    @Test
    fun `相对路径按会话工作目录拼成绝对路径`() {
        assertEquals(
            "C:/work/proj/out/a.png",
            resolvedPath("out/a.png", "C:/work/proj").replace('\\', '/'),
        )
        assertEquals("/home/u/proj/a.png", resolvedPath("/a.png".removePrefix("/"), "/home/u/proj"))
        assertEquals("/home/u/proj/a.png", resolvedPath("a.png", "/home/u/proj/"))
    }

    @Test
    fun `绝对路径原样用，不拼工作目录`() {
        assertEquals("C:\\tmp\\a.png", resolvedPath("C:\\tmp\\a.png", "/home/u"))
        assertEquals("/tmp/a.png", resolvedPath("/tmp/a.png", "/home/u"))
        assertEquals("~/a.png", resolvedPath("~/a.png", "/home/u"))
        assertEquals("\\\\srv\\a.png", resolvedPath("\\\\srv\\a.png", "/home/u"))
    }

    @Test
    fun `没有工作目录时相对路径原样交给服务端`() {
        assertEquals("out/a.png", resolvedPath("out/a.png", null))
        assertEquals("out/a.png", resolvedPath("out/a.png", ""))
    }

    @Test
    fun `缓存名确定性、不含路径分隔符、扩展名洗干净`() {
        val a = cacheNameOf("tool:image:/x/y.png", "png")
        val b = cacheNameOf("tool:image:/x/y.png", "png")
        assertEquals(a, b)
        assertNotEquals(a, cacheNameOf("tool:image:/x/y2.png", "png"))
        assertFalse(a.contains('/') || a.contains('\\'))
        assertTrue(a.endsWith(".png"))
        // 扩展名里的怪字符不能带进文件名
        assertEquals(32 + 1 + 3, cacheNameOf("k", "p/n\\g").length)
        assertEquals(32, cacheNameOf("k", "").length)
    }

    @Test
    fun `内嵌上限按类型分（同桌面版）`() {
        assertEquals(24L * 1024 * 1024, capBytesFor("image"))
        assertEquals(64L * 1024 * 1024, capBytesFor("video"))
        assertEquals(32L * 1024 * 1024, capBytesFor("audio"))
    }

    @Test
    fun `mime 按扩展名认，认不出的当二进制流`() {
        assertEquals("image/png", mimeOf("a/b/c.PNG"))
        assertEquals("image/jpeg", mimeOf("x.jpeg"))
        assertEquals("audio/mpeg", mimeOf("voice.mp3"))
        assertEquals("video/mp4", mimeOf("clip.mp4"))
        assertEquals("application/octet-stream", mimeOf("archive.zip"))
        assertEquals("application/octet-stream", mimeOf("noext"))
    }

    // MARK: 工具媒体

    @Test
    fun `看图看视频听录音的参数里取出媒体源`() {
        assertEquals(
            "image" to "/tmp/a.png",
            toolMediaSource("analyze_image", """{"image":"/tmp/a.png","question":"这是什么"}"""),
        )
        assertEquals(
            "video" to "clip.mp4",
            toolMediaSource("analyze_video", """{"video":"clip.mp4"}"""),
        )
        assertEquals(
            "audio" to "v.m4a",
            toolMediaSource("transcribe_audio", """{"audio":"v.m4a","language":"zh"}"""),
        )
        // 工具名大小写不敏感
        assertEquals("image" to "a.png", toolMediaSource("Analyze_Image", """{"image":"a.png"}"""))
    }

    @Test
    fun `不是媒体工具、参数没流完整、没填路径时不给源`() {
        assertNull(toolMediaSource("read_file", """{"path":"a.txt"}"""))
        assertNull(toolMediaSource("analyze_image", """{"image":"/tmp/a.pn""")) // JSON 还没流完整
        assertNull(toolMediaSource("analyze_image", ""))
        assertNull(toolMediaSource("analyze_image", """{"question":"这是什么"}"""))
        assertNull(toolMediaSource("analyze_image", """{"image":"   "}"""))
    }

    @Test
    fun `媒体源的缓存键稳定且互不相同`() {
        val attachment = MediaSource.Attachment(
            MediaAttachment("m1", "image", "2026/01/a.png", "a.png", "image/png", 10)
        )
        val tool = MediaSource.ToolPath("image", "out/a.png", "/home/u/proj")
        val url = MediaSource.Url("image", "https://x/a.png")
        assertEquals(attachment.key, attachment.copy().key)
        assertEquals("tool:image:/home/u/proj/out/a.png", tool.key)
        assertNotEquals(attachment.key, tool.key)
        assertNotEquals(tool.key, url.key)
        // 工作目录不同 → 键不同（不会串图）
        assertNotEquals(tool.key, tool.copy(workingDirectory = "/other").key)
        assertEquals("image", attachment.kind)
        assertEquals("a.png", attachment.displayName)
        assertEquals("a.png", tool.displayName)
    }

    @Test
    fun `附件类型缺省时按 mimeType 和扩展名兜底`() {
        val byMime = MediaAttachment("m", "", "a/x.png", "x.png", "image/png", 1)
        assertTrue(byMime.isImage)
        val byExt = MediaAttachment("m", "", "a/x.webp", "x.webp", "", 1)
        assertTrue(byExt.isImage)
        val audio = MediaAttachment("m", "audio", "a/x.m4a", "x.m4a", "audio/mp4", 1)
        assertTrue(audio.isAudio)
        assertFalse(audio.isImage)
        val other = MediaAttachment("m", "file", "a/x.zip", "x.zip", "application/zip", 1)
        assertFalse(other.isImage || other.isAudio || other.isVideo)
    }

    // MARK: 块解析

    @Test
    fun `media 块解出附件（线上形状包在 _0 里）`() {
        val block = MessageBlock(
            "b1",
            obj(
                """{"media":{"_0":{"id":"m1","kind":"image","relativePath":"2026/01/a.png",
                    "fileName":"a.png","mimeType":"image/png","byteCount":2048,"caption":"看这个","width":800,"height":600}}}"""
            )
        )
        val media = blockMedia(block)
        assertNotNull(media)
        assertEquals("m1", media!!.id)
        assertEquals("2026/01/a.png", media.relativePath)
        assertEquals("a.png", media.fileName)
        assertEquals(2048L, media.byteCount)
        assertEquals("看这个", media.caption)
        assertEquals(800, media.width)
        assertTrue(media.isImage)
        // 别的 case 不误认
        assertNull(blockMedia(MessageBlock("b2", obj("""{"text":{"_0":"hi"}}"""))))
    }

    @Test
    fun `工具块解出它带的媒体`() {
        val block = MessageBlock(
            "t1",
            obj(
                """{"tool":{"_0":{"id":"t1","name":"generate_image","arguments":"{}","status":"succeeded","result":"ok",
                    "media":[{"id":"m1","kind":"image","relativePath":"a.png","fileName":"a.png","mimeType":"image/png","byteCount":9}]}}}"""
            )
        )
        val tool = blockTool(block)
        assertNotNull(tool)
        assertEquals(1, tool!!.media.size)
        assertEquals("a.png", tool.media[0].fileName)
    }

    @Test
    fun `file_read 回执解 base64、带 size 和 eof`() {
        // "hello" 的 base64
        val chunk = FileChunk.fromJson(obj("""{"data":"aGVsbG8=","size":5,"eof":true}"""))
        assertEquals("hello", String(chunk.data, Charsets.UTF_8))
        assertEquals(5L, chunk.size)
        assertTrue(chunk.eof)
        val empty = FileChunk.fromJson(obj("""{"data":"","size":0,"eof":true}"""))
        assertEquals(0, empty.data.size)
        assertTrue(empty.eof)
    }

    // MARK: 补丁

    @Test
    fun `工具补丁改状态后媒体和运行记录都还在`() {
        val block = MessageBlock(
            "t1",
            obj(
                """{"tool":{"_0":{"id":"t1","name":"analyze_image","arguments":"{}","status":"running","result":"",
                    "media":[{"id":"m1","kind":"image","relativePath":"a.png","fileName":"a.png","mimeType":"image/png","byteCount":9}],
                    "transcript":[{"id":"s1","kind":{"text":{"_0":"子 Agent 说了一句"}}}]}}}"""
            )
        )
        val patched = applyBlocks(
            listOf(block),
            BlocksPatch(
                ids = null,
                set = null,
                tools = listOf(
                    ToolPatch(
                        id = "t1",
                        tool = ToolEvent.fromJson(obj("""{"id":"t1","name":"analyze_image","arguments":"{}","status":"succeeded","result":"是只猫"}""")),
                        transcript = null,
                    )
                ),
            ),
        )
        assertNotNull(patched)
        val tool = blockTool(patched!![0])
        assertNotNull(tool)
        assertEquals("succeeded", tool!!.status)
        assertEquals("是只猫", tool.result)
        // 服务端补丁不带 transcript / 重新编码时不能把媒体丢了
        assertEquals(1, tool.transcript.size)
        assertEquals("子 Agent 说了一句", blockText(tool.transcript[0]))
        assertEquals(1, tool.media.size)
        assertEquals("a.png", tool.media[0].fileName)
    }

    @Test
    fun `补丁引用不存在的块时打不上（调用方去拉整条）`() {
        val block = MessageBlock("t1", obj("""{"tool":{"_0":{"id":"t1","name":"x","arguments":"{}","status":"running","result":""}}}"""))
        val result = applyBlocks(
            listOf(block),
            BlocksPatch(ids = null, set = null, tools = listOf(ToolPatch("nope", null, null))),
        )
        assertNull(result)
    }

    @Test
    fun `消息生成用量解出来给界面显示`() {
        val message = ChatMessage.fromJson(
            obj(
                """{"id":"m","role":"assistant","blocks":[],"createdAt":"2026-01-01T00:00:00Z",
                    "stats":{"tokensPerSecond":42.7,"tokenCount":1234,"engineName":"智谱 GLM"}}"""
            )
        )
        assertNotNull(message.stats)
        assertEquals(1234L, message.stats!!.tokenCount)
        assertEquals(42, message.stats!!.tokensPerSecond.toInt())
        assertEquals("智谱 GLM", message.stats!!.engineName)
    }
}
