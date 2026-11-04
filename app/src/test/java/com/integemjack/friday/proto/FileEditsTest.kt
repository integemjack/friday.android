package com.integemjack.friday.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 改文件工具的参数 → diff（手机上画「+ / -」用） */
class FileEditsTest {

    private fun tool(name: String, arguments: String, added: Int? = null, removed: Int? = null) =
        ToolEvent(id = "t", name = name, arguments = arguments, status = "succeeded", result = "", linesAdded = added, linesRemoved = removed)

    private fun kinds(lines: List<DiffLine>) = lines.joinToString("") {
        when (it.kind) { DiffKind.Same -> "="; DiffKind.Added -> "+"; DiffKind.Removed -> "-" }
    }

    @Test
    fun `逐行比：改掉的一行是一删一加，没变的留着`() {
        val diff = lineDiff("a\nb\nc\nd", "a\nB\nc\nd\ne")
        assertEquals("=-+==+", kinds(diff))
        assertEquals(listOf("a", "b", "B", "c", "d", "e"), diff.map { it.text })
    }

    @Test
    fun `删掉一段（new_text 空）全是删`() {
        assertEquals("--", kinds(lineDiff("x\ny\n", "")))
    }

    @Test
    fun `edit_file 的 edits 每处一段，顶层 old_text 也认`() {
        val args = buildJsonObject {
            put("path", "C:/w/src/a.c")
            put("edits", buildJsonArray {
                add(buildJsonObject { put("old_text", "int a;"); put("new_text", "int a = 1;") })
                add(buildJsonObject { put("old_text", "x();\ny();"); put("new_text", "") })
            })
        }.toString()
        val change = fileChange(tool("edit_file", args))!!
        assertEquals("a.c", change.fileName)
        assertEquals(2, change.hunks.size)

        val legacy = fileChange(tool("edit_file", """{"path":"/r/b.py","old_text":"1","new_text":"2"}"""))!!
        assertEquals("b.py", legacy.fileName)
        assertEquals(listOf(FileChange.Hunk("1", "2")), legacy.hunks)
    }

    @Test
    fun `write_file 整个文件都算新加的`() {
        val change = fileChange(tool("write_file", Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildJsonObject {
            put("path", "/r/new.txt"); put("content", "l1\nl2\nl3\n")
        })))!!
        assertEquals(true, change.wholeFile)
        assertEquals("+++", kinds(lineDiff(change.hunks[0].old, change.hunks[0].new)))
    }

    @Test
    fun `参数还没流完、不是改文件的工具都不画`() {
        assertNull(fileChange(tool("edit_file", """{"path":"/r/a","edits":[{"old_te""")))
        assertNull(fileChange(tool("run_shell", """{"command":"ls"}""")))
    }

    @Test
    fun `行数先用服务端给的，没给再自己数`() {
        assertEquals(12 to 3, fileLineCounts(tool("edit_file", "{}", added = 12, removed = 3)))
        assertEquals(1 to 1, fileLineCounts(tool("edit_file", """{"path":"/a","old_text":"x","new_text":"y"}""")))
        assertNull(fileLineCounts(tool("read_file", """{"path":"/a"}""")))
    }

    @Test
    fun `工具补丁重新编码时行数不丢`() {
        val block = MessageBlock(
            "t1",
            Json.parseToJsonElement("""{"tool":{"_0":{"id":"t1","name":"edit_file","arguments":"{}","status":"running","result":""}}}""") as kotlinx.serialization.json.JsonObject,
        )
        val patched = applyBlocks(
            listOf(block),
            BlocksPatch(ids = null, set = null, tools = listOf(ToolPatch("t1", tool("edit_file", "{}", added = 5, removed = 2).copy(id = "t1"), null))),
        )!!
        val event = blockTool(patched[0])!!
        assertEquals(5, event.linesAdded)
        assertEquals(2, event.linesRemoved)
    }
}
