package com.integemjack.friday.ui

import com.integemjack.friday.proto.MessageBlock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** 连续操作收成一组的规则 */
class BlockGroupsTest {

    private fun text(id: String) = MessageBlock(id, buildJsonObject { put("text", buildJsonObject { put("_0", "字") }) })
    private fun think(id: String) = MessageBlock(id, buildJsonObject { put("thinking", buildJsonObject { put("_0", "想") }) })
    private fun tool(id: String, name: String = "run_shell", title: String = "执行命令", status: String = "succeeded") = MessageBlock(
        id,
        buildJsonObject {
            put("tool", buildJsonObject {
                put("_0", buildJsonObject {
                    put("id", id); put("name", name); put("title", title)
                    put("arguments", "{}"); put("status", status); put("result", "")
                })
            })
        },
    )

    /** 每项写成「块 id」或「[组里的块 id]」 */
    private fun shape(blocks: List<MessageBlock>) = groupBlocks(blocks).joinToString(" ") { item ->
        when (item) {
            is BlockItem.Single -> item.block.id
            is BlockItem.Ops -> item.blocks.joinToString(",", "[", "]") { it.id }
        }
    }

    @Test
    fun `连着两个以上的操作收成一组，中间的思考一起收，首尾的思考留在外面`() {
        val blocks = listOf(think("T1"), tool("x1"), tool("x2"), think("T2"), tool("x3"), think("T3"), text("s1"), tool("x4"))
        assertEquals("T1 [x1,x2,T2,x3] T3 s1 x4", shape(blocks))
    }

    @Test
    fun `字把操作隔开，只有一个的不收`() {
        assertEquals("x1 s1 x2", shape(listOf(tool("x1"), text("s1"), tool("x2"))))
    }

    @Test
    fun `等确认的、看图的不收进去，把一串断开`() {
        val blocks = listOf(
            tool("x1"), tool("x2"), tool("ask", status = "awaitingApproval"),
            tool("x3"), tool("img", name = "analyze_image", title = "看图"), tool("x4"), tool("x5"),
        )
        assertEquals("[x1,x2] ask x3 img [x4,x5]", shape(blocks))
    }

    @Test
    fun `在跑的不收进去，单独一行；跑完再并进前面那组`() {
        val running = listOf(tool("x1"), tool("x2"), think("T1"), tool("x3", status = "running"))
        assertEquals("[x1,x2] T1 x3", shape(running))
        val done = listOf(tool("x1"), tool("x2"), think("T1"), tool("x3"))
        assertEquals("[x1,x2,T1,x3]", shape(done))
    }

    private fun journal(id: String, kind: String, name: String = "") = com.integemjack.friday.proto.JournalEntry(
        id, "2026-01-01T00:00:00Z", kind, null, if (kind == "tool") "" else "话", emptyList(), false, false,
        if (kind == "tool") buildJsonObject { put("name", name) } else null,
    )

    @Test
    fun `助手页连着的工具调用收成一行，一天的开头把它们隔开`() {
        val entries = listOf(
            journal("u", "user"),
            journal("t1", "tool", "search_memory"), journal("t2", "tool", "search_memory"), journal("t3", "tool", "say"),
            journal("a", "assistant"),
            journal("t4", "tool", "say"),
            journal("t5", "tool", "say"), journal("t6", "tool", "say"),
        )
        val items = groupJournal(entries, dayStarts = setOf("u", "t6"))
        assertEquals("u [t1,t2,t3] a [t4,t5] t6", journalShape(items))
        assertEquals("search_memory ×2、say", (items[1] as JournalItem.Tools).kinds)
    }

    private fun journalShape(items: List<JournalItem>) = items.joinToString(" ") { item ->
        when (item) {
            is JournalItem.Single -> item.entry.id
            is JournalItem.Tools -> item.entries.joinToString(",", "[", "]") { it.id }
            is JournalItem.Notices -> item.entries.joinToString(",", "{", "}") { it.id }
        }
    }

    @Test
    fun `助手页连着的系统说明收成一行，跟工具分开收，一天的开头把它们隔开`() {
        val entries = listOf(
            journal("n1", "notice"), journal("n2", "notice"), journal("n3", "notice"),
            journal("u", "user"),
            journal("n4", "notice"),
            journal("a", "assistant"),
            // 说明和工具挨着：各收各的
            journal("n5", "notice"), journal("n6", "notice"), journal("t1", "tool", "say"), journal("t2", "tool", "say"),
            journal("n7", "notice"), journal("n8", "notice"),
        )
        val items = groupJournal(entries, dayStarts = setOf("n1", "n8"))
        assertEquals("{n1,n2,n3} u n4 a {n5,n6} [t1,t2] n7 n8", journalShape(items))
        assertEquals("notices:n1", items.first().key)
    }

    @Test
    fun `一组写几个操作、做了哪几种、改了多少行`() {
        val group = groupBlocks(
            listOf(
                tool("x1"), tool("x2"),
                tool("e1", name = "edit_file", title = "修改文件").let { b ->
                    MessageBlock(b.id, buildJsonObject {
                        put("tool", buildJsonObject {
                            put("_0", buildJsonObject {
                                put("id", "e1"); put("name", "edit_file"); put("title", "修改文件")
                                put("arguments", """{"path":"/a.c","old_text":"x","new_text":"y\nz"}""")
                                put("status", "succeeded"); put("result", "")
                            })
                        })
                    })
                },
                tool("x3", status = "failed"),
                tool("x4", title = "读取文件"),
            )
        ).single() as BlockItem.Ops
        val summary = opsSummary(group.tools)
        assertEquals(5, summary.count)
        assertEquals("执行命令 ×3、修改文件、读取文件", summary.kinds)
        assertEquals(null, summary.running)
        assertEquals(1, summary.failed)
        assertEquals(2, summary.linesAdded)
        assertEquals(1, summary.linesRemoved)
    }
}
