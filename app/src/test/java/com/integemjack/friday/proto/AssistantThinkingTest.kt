package com.integemjack.friday.proto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 助手页「思考中…」什么时候摆、什么时候收（AssistantThinking.kt） */
class AssistantThinkingTest {

    private fun assistant(enabled: Boolean = true, reacting: Boolean = false, reactingEntryID: String? = null) =
        AssistantState.fromJson(buildJsonObject {
            put("enabled", enabled)
            put("reacting", reacting)
            reactingEntryID?.let { put("reactingEntryID", it) }
        })

    private fun entry(
        id: String,
        kind: String,
        text: String = "",
        streaming: Boolean = false,
        data: JsonObject? = null,
    ) = JournalEntry(id, "2026-11-05T08:00:${id.filter { it.isDigit() }.padStart(2, '0')}Z", kind, null, text, emptyList(), false, streaming, data)

    private fun state(
        assistant: AssistantState?,
        entries: List<JournalEntry> = listOf(entry("u1", "user", "帮我看看")),
        awaiting: Boolean = false,
    ) = FridayState(assistant = assistant, assistantEntries = entries, assistantAwaiting = awaiting)

    // MARK: 说完话要不要先等它回

    @Test
    fun `平常跟助手说话要等它回`() {
        assertTrue(expectsAssistantReply("明天几点开会", entry("u1", "user", "明天几点开会"), assistant()))
        // 回执没带回来（旧主机）也照样等
        assertTrue(expectsAssistantReply("在吗", null, assistant()))
    }

    @Test
    fun `助手关着、直发给会话、斜杠命令都不等`() {
        assertFalse(expectsAssistantReply("在吗", null, assistant(enabled = false)))
        assertFalse(expectsAssistantReply("在吗", null, null))
        val direct = entry("u1", "user", "#3f2a 再跑一遍", data = buildJsonObject {
            put("direct", buildJsonObject { put("machineID", "local"); put("conversationID", "C1") })
        })
        assertFalse(expectsAssistantReply("#3f2a 再跑一遍", direct, assistant()))
        assertFalse(expectsAssistantReply(" /status", null, assistant()))
    }

    @Test
    fun `它开始反应或者反应完一轮就不用再替它挂着`() {
        // 开始反应：交给 reacting
        assertFalse(awaitingAfterState(true, assistant(), assistant(reacting = true)))
        // 说话时它在忙上一轮，那一轮收了
        assertFalse(awaitingAfterState(true, assistant(reacting = true), assistant()))
        // 别的变化（改了设置）：接着等
        assertTrue(awaitingAfterState(true, assistant(), assistant()))
        assertTrue(awaitingAfterState(true, null, assistant()))
        assertFalse(awaitingAfterState(false, assistant(), assistant()))
    }

    @Test
    fun `它的回复或者一条说明到了算回了，工具和回执不算`() {
        assertTrue(answersAwaiting(entry("a1", "assistant", "好")))
        assertTrue(answersAwaiting(entry("n1", "notice", "没接上大脑")))
        assertFalse(answersAwaiting(entry("t1", "tool")))
        assertFalse(answersAwaiting(entry("r1", "receipt", "完成")))
        assertFalse(answersAwaiting(entry("u2", "user", "再说一句")))
    }

    // MARK: 底下那行「思考中…」

    @Test
    fun `在反应、回复还没进日志时显示思考中`() {
        assertTrue(assistantThinkingPending(state(assistant(reacting = true, reactingEntryID = "a2"))))
        // 旧主机不给 reactingEntryID
        assertTrue(assistantThinkingPending(state(assistant(reacting = true))))
        // 中间调了工具：还在想
        val withTool = listOf(entry("u1", "user", "查一下"), entry("t2", "tool", "查日程"))
        assertTrue(assistantThinkingPending(state(assistant(reacting = true, reactingEntryID = "a3"), withTool)))
    }

    @Test
    fun `刚说完、还没开始反应也显示思考中`() {
        assertTrue(assistantThinkingPending(state(assistant(), awaiting = true)))
    }

    @Test
    fun `回复出字了就收`() {
        val replying = listOf(entry("u1", "user", "在吗"), entry("a2", "assistant", "在的", streaming = true))
        assertFalse(assistantThinkingPending(state(assistant(reacting = true, reactingEntryID = "a2"), replying)))
        assertFalse(assistantThinkingPending(state(assistant(reacting = true), replying)))
        // 刚说完、它上一轮还在出字：也是那条自己写着「在说…」
        assertFalse(assistantThinkingPending(state(assistant(), replying, awaiting = true)))
    }

    @Test
    fun `回复那条带着思考进了日志：由它自己显示思考中，底下不再重一行`() {
        val thinking = listOf(
            entry("u1", "user", "明天几点开会"),
            entry("a2", "assistant", "", streaming = true, data = buildJsonObject { put("thinking", "先查日程") }),
        )
        assertFalse(assistantThinkingPending(state(assistant(reacting = true, reactingEntryID = "a2"), thinking)))
        // 进了日志、但还是空的（没字也没思考）：底下照样显示
        val blank = listOf(entry("u1", "user", "在吗"), entry("a2", "assistant", "", streaming = true))
        assertTrue(assistantThinkingPending(state(assistant(reacting = true, reactingEntryID = "a2"), blank)))
    }

    @Test
    fun `没在反应、没在等、助手关着都不显示`() {
        assertFalse(assistantThinkingPending(state(assistant())))
        assertFalse(assistantThinkingPending(state(assistant(enabled = false, reacting = true))))
        assertFalse(assistantThinkingPending(state(null, awaiting = true)))
    }

    @Test
    fun `思考只认助手回复里的 data_thinking`() {
        assertTrue(entry("a1", "assistant", data = buildJsonObject { put("thinking", "嗯") }).thinking == "嗯")
        assertTrue(entry("a1", "assistant", data = buildJsonObject { put("thinking", "  ") }).thinking == null)
        assertTrue(entry("t1", "tool", data = buildJsonObject { put("thinking", "嗯") }).thinking == null)
    }

    // MARK: 刚说完的回复（语音播报念它）

    private fun reply(
        text: String = "好的，已经开了一段会话去查",
        streaming: Boolean = false,
        source: String? = "remote",
        interrupted: Boolean = false,
        data: JsonObject? = null,
    ) = JournalEntry("a7", "2026-11-25T08:00:07Z", "assistant", source, text, emptyList(), interrupted, streaming, data)

    @Test
    fun `回复从在说变成说完、或者一次就是说完的：算刚说完`() {
        assertTrue(replyFinished(reply(streaming = true), reply()))
        assertTrue(replyFinished(null, reply()))
        // 主动汇报（source notice）、聊天工具里说的也念
        assertTrue(replyFinished(null, reply(source = "notice")))
        assertTrue(replyFinished(null, reply(source = "schat:bot")))
        assertTrue(replyFinished(null, reply(source = null)))
    }

    @Test
    fun `还在说、已经说完的又改了一下、没字的都不算`() {
        assertFalse(replyFinished(null, reply(streaming = true)))
        assertFalse(replyFinished(reply(streaming = true), reply(streaming = true)))
        // 答了它问的、标了项目：同一条又推一次
        assertFalse(replyFinished(reply(), reply(data = buildJsonObject { put("answered", true) })))
        assertFalse(replyFinished(reply(streaming = true), reply(text = "  ")))
    }

    @Test
    fun `被打断、出错、不出声的、别人的条目都不算`() {
        assertFalse(replyFinished(reply(streaming = true), reply(interrupted = true)))
        assertFalse(replyFinished(reply(streaming = true), reply(text = "（出错了：超时）", data = buildJsonObject { put("error", "超时") })))
        assertFalse(replyFinished(reply(streaming = true), reply(text = "（不说）", data = buildJsonObject { put("silent", true) })))
        val user = JournalEntry("u1", "2026-11-25T08:00:01Z", "user", "remote", "在吗", emptyList(), false, false, null)
        assertFalse(replyFinished(null, user))
        val notice = JournalEntry("n1", "2026-11-25T08:00:02Z", "notice", null, "没接上大脑", emptyList(), false, false, null)
        assertFalse(replyFinished(null, notice))
    }

    @Test
    fun `桌面端语音模式那轮的回复主机已经念了，手机上不再念；通话里那轮的照念（挂了才说完的通话里没念）`() {
        assertFalse(replyFinished(reply(streaming = true, source = "voice"), reply(source = "voice")))
        assertTrue(replyFinished(reply(streaming = true, source = "call:schat"), reply(source = "call:schat")))
    }
}
