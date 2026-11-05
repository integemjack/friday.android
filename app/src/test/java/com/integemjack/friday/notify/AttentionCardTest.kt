package com.integemjack.friday.notify

import com.integemjack.friday.proto.AskOption
import com.integemjack.friday.proto.AskQuestion
import com.integemjack.friday.proto.PendingApproval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「待确认」通知：给哪几个按钮、按下去发什么、上面写什么（纯函数） */
class AttentionCardTest {

    private fun approval(title: String, tool: String = "Bash", summary: String = "") =
        PendingApproval(eventID = "E-$title", tool = tool, title = title, summary = summary, questions = null)

    private fun question(
        text: String,
        options: List<String>,
        multiple: Boolean = false,
        recommended: Int = -1,
        more: List<AskQuestion> = emptyList(),
    ) = PendingApproval(
        eventID = "Q-$text", tool = "ask_user", title = "提问", summary = text,
        questions = listOf(AskQuestion(text, options.map { AskOption(it, "$it 的说明") }, multiple, recommended)) + more,
    )

    private fun labels(actions: List<AttentionAction>) = actions.map { it.label }

    @Test
    fun `普通调用：批准、拒绝；要密钥的只给拒绝`() {
        assertEquals(listOf("批准", "拒绝"), labels(attentionActions(approval("运行命令"))))
        assertEquals(listOf("拒绝"), labels(attentionActions(approval("要 API 密钥", tool = "request_secret"))))
    }

    @Test
    fun `单选两个以内：选项加「回答…」`() {
        assertEquals(listOf("听到了", "没听到", "回答…"), labels(attentionActions(question("听到了吗", listOf("听到了", "没听到")))))
    }

    @Test
    fun `单选三四个：摆三个选项，推荐的一定在里面（0 起的序号）`() {
        assertEquals(listOf("A", "B", "C"), labels(attentionActions(question("选哪个", listOf("A", "B", "C")))))
        assertEquals(listOf("A", "B", "C"), labels(attentionActions(question("选哪个", listOf("A", "B", "C", "D")))))
        assertEquals(listOf("A", "B", "D"), labels(attentionActions(question("选哪个", listOf("A", "B", "C", "D"), recommended = 3))))
        assertEquals(listOf("A", "B", "C"), labels(attentionActions(question("选哪个", listOf("A", "B", "C", "D"), recommended = 1))))
    }

    @Test
    fun `多选、好几题：只有「回答…」`() {
        assertEquals(listOf("回答…"), labels(attentionActions(question("要哪些", listOf("A", "B"), multiple = true))))
        val second = AskQuestion("还有呢", listOf(AskOption("X", null), AskOption("Y", null)), false, -1)
        assertEquals(listOf("回答…"), labels(attentionActions(question("先问这个", listOf("A", "B"), more = listOf(second)))))
    }

    @Test
    fun `按下去发什么：选项是答案、回答是 reply（每题空着）、空回答不发`() {
        assertEquals(Resolution(true, listOf("听到了"), null), resolutionFrom("option", "听到了", 1, null))
        assertEquals(Resolution(true, listOf("", ""), "都行"), resolutionFrom("reply", "回答…", 2, "  都行 "))
        assertEquals(Resolution(true, listOf(""), "好"), resolutionFrom("reply", "回答…", 0, "好"))
        assertNull(resolutionFrom("reply", "回答…", 1, "   "))
        assertNull(resolutionFrom("reply", "回答…", 1, null))
        assertEquals(Resolution(true, null, null), resolutionFrom("approve", "批准", 0, null))
        assertEquals(Resolution(false, null, null), resolutionFrom("reject", "拒绝", 0, null))
        assertNull(resolutionFrom("什么", null, 0, null))
    }

    @Test
    fun `一项提问：标题「在问你」、正文是问题、展开列出选项和说明，胶囊写「在问你」`() {
        val card = attentionCard(listOf("发版" to question("推到 main 吗？", listOf("推", "先别"))))
        assertEquals("Friday 在问你", card.title)
        assertEquals("发版：推到 main 吗？", card.text)
        assertEquals("发版：推到 main 吗？\n\n· 推 — 推 的说明\n· 先别 — 先别 的说明", card.detail)
        assertEquals("在问你", card.chip)
    }

    @Test
    fun `一项调用：正文是会话和调用、展开带上摘要（命令）`() {
        val card = attentionCard(listOf("" to approval("运行命令", summary = "rm -rf build")))
        assertEquals("Friday 等你确认", card.title)
        assertEquals("会话：运行命令", card.text)
        assertEquals("会话：运行命令\nrm -rf build", card.detail)
        assertEquals("待确认", card.chip)
    }

    @Test
    fun `好几项：摆第一项、写还有几项，胶囊写个数`() {
        val card = attentionCard(listOf("发版" to approval("推送"), "改代码" to approval("删文件"), "x" to approval("y")))
        assertEquals("Friday 等你确认（3 项）", card.title)
        assertEquals("发版：推送", card.text)
        assertTrue(card.detail.endsWith("还有 2 项等你确认"))
        assertEquals("3项待确认", card.chip)
    }

    @Test
    fun `还不知道是哪项（activity 晚到）：说个大概`() {
        val card = attentionCard(emptyList())
        assertEquals("Friday 等你确认", card.title)
        assertEquals("待确认", card.chip)
    }

    @Test
    fun `胶囊上的字少于 7 个（长了系统只显示图标）`() {
        val many = (1..9).map { "s" to approval("a$it") }
        listOf(attentionCard(many), attentionCard(many.take(1)), attentionCard(listOf("s" to question("q", listOf("a", "b")))))
            .forEach { assertTrue(it.chip, it.chip.length < 7) }
    }
}
