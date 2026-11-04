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
    fun `一组写几个操作、做了哪几种、在跑的是哪个、改了多少行`() {
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
                tool("x4", title = "读取文件", status = "running"),
            )
        ).single() as BlockItem.Ops
        val summary = opsSummary(group.tools)
        assertEquals(5, summary.count)
        assertEquals("执行命令 ×3、修改文件、读取文件", summary.kinds)
        assertEquals("读取文件", toolTitle(summary.running!!))
        assertEquals(1, summary.failed)
        assertEquals(2, summary.linesAdded)
        assertEquals(1, summary.linesRemoved)
    }
}
