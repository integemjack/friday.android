package com.integemjack.friday.proto

// 助手页的「思考中…」：你说完话到它出第一个字之间（REMOTE.md §6「助手」）。
// 同桌面端（Friday 533f4f60：Swift AssistantTimeline、Qt AssistantTimeline.qml 的 assistantThinkingRow）：
// 在反应（assistant.state 的 reacting）、正在写的那条（reactingEntryID）还没字，就在时间线最下面摆一行，
// 开不开深度思考都一样。
//
// 主机上大脑一开始反应就推 reacting，但回复那条要等有了**看得见的字**才进日志（`<think>` 里的思考在主机上
// 剥掉了，withoutThinking），所以这段时间里日志里没有它的回复。
// 回复里带思考内容（data.thinking）的话，那条会自己显示成能点开的「思考中…」——主机到 533f4f60 为止还不发，先认着。

/** 说完话最多替它挂这么久「思考中…」：一直等不到 reacting（主机没接上大脑、忙别的）就算了 */
const val ASSISTANT_AWAIT_MS = 15_000L

/**
 * 这句话说出去以后该不该等助手回（先摆「思考中…」）：
 * - 助手关着：只记日志，不会回
 * - 「#短号 …」直发给会话（回执里 data.direct）：不经过大脑，结果记成一条说明
 * - 「/命令」：主机直接答、不经过大模型（交给大脑的那几个会推 reacting，到时候自然显示）
 */
fun expectsAssistantReply(text: String, said: JournalEntry?, assistant: AssistantState?): Boolean {
    if (assistant?.enabled != true) return false
    if (said?.data?.get("direct") != null) return false
    return !text.trimStart().startsWith("/")
}

/**
 * 收到 assistant.state 以后还要不要替它挂着「思考中…」：它开始反应了（交给 reacting 显示），
 * 或者刚反应完一轮（说完话时它正忙着上一轮、那一轮收了；下一轮马上就来，来了照样显示）——都不用再等
 */
fun awaitingAfterState(awaiting: Boolean, before: AssistantState?, next: AssistantState): Boolean =
    awaiting && !next.reacting && before?.reacting != true

/** 新来的这条算不算「回了」：它的回复，或者一条说明（没接上大脑、命令的结果……） */
fun answersAwaiting(entry: JournalEntry): Boolean = entry.kind == "assistant" || entry.kind == "notice"

/**
 * 助手页底下要不要摆「思考中…」：它在反应（或刚对它说了话、还没开始）、这一轮的回复还没出字。
 * 回复那条已经带着思考（data.thinking）进了日志的，由那条自己显示「思考中…」（能点开），这里不再重一行
 */
fun assistantThinkingPending(state: FridayState): Boolean {
    val assistant = state.assistant ?: return false
    if (!assistant.enabled) return false
    if (!assistant.reacting && !state.assistantAwaiting) return false
    val entries = state.assistantEntries
    // 这一轮的回复：主机说了是哪条就认哪条；旧主机不给 id 时看最后一条是不是正在写的回复
    val reply = assistant.reactingEntryID?.takeIf { assistant.reacting }
        ?.let { id -> entries.firstOrNull { it.id == id } }
        ?: entries.lastOrNull()?.takeIf { it.kind == "assistant" && it.streaming }
    return reply == null || reply.silent || (reply.text.isBlank() && reply.thinking == null && reply.error == null)
}
