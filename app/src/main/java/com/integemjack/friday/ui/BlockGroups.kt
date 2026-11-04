package com.integemjack.friday.ui

import com.integemjack.friday.proto.JournalEntry
import com.integemjack.friday.proto.MessageBlock
import com.integemjack.friday.proto.TOOL_MEDIA_ARG
import com.integemjack.friday.proto.ToolEvent
import com.integemjack.friday.proto.blockThinking
import com.integemjack.friday.proto.blockTool
import com.integemjack.friday.proto.fileLineCounts

/**
 * 消息流里一行一行摆的东西：单独一块，或者收起来的一串连续操作。
 * [key] 给 Compose 认人：一组从第一块开始，流式往后长时 key 不变，展开状态不丢
 */
sealed interface BlockItem {
    val key: String

    data class Single(val block: MessageBlock) : BlockItem {
        override val key: String get() = block.id
    }

    data class Ops(val blocks: List<MessageBlock>) : BlockItem {
        override val key: String get() = "ops:" + blocks.first().id
        val tools: List<ToolEvent> get() = blocks.mapNotNull(::blockTool)
    }
}

/**
 * 收进一组的工具。不收的：在跑的（单独一行、详情摊开，跑完再收进去）、
 * 等你确认 / 回答的（要你点的不能藏起来）、直接内嵌媒体的（看图 / 看视频 / 听录音、带附件的：图收起来就看不到了）
 */
private fun foldableTool(block: MessageBlock): Boolean {
    val tool = blockTool(block) ?: return false
    if (tool.status == "running" || tool.status == "awaitingApproval") return false
    if (TOOL_MEDIA_ARG.containsKey(tool.name.lowercase()) || tool.media.isNotEmpty()) return false
    return true
}

/**
 * 连续的操作收成一组：从第一个工具到最后一个工具，中间夹着的思考一起收进去；
 * 至少两个工具才收。字、媒体、等你确认的工具把它们隔开；一串前头、后头的思考留在外面
 */
fun groupBlocks(blocks: List<MessageBlock>): List<BlockItem> {
    val out = ArrayList<BlockItem>(blocks.size)
    var i = 0
    while (i < blocks.size) {
        if (!foldableTool(blocks[i])) {
            out += BlockItem.Single(blocks[i])
            i++
            continue
        }
        var j = i
        var lastTool = i
        var tools = 0
        while (j < blocks.size && (foldableTool(blocks[j]) || blockThinking(blocks[j]) != null)) {
            if (foldableTool(blocks[j])) {
                lastTool = j
                tools++
            }
            j++
        }
        if (tools >= 2) {
            out += BlockItem.Ops(blocks.subList(i, lastTool + 1).toList())
            i = lastTool + 1
        } else {
            out += BlockItem.Single(blocks[i])
            i++
        }
    }
    return out
}

/** 收起来那一行写什么 */
data class OpsSummary(
    val count: Int,
    /** 还在跑的那个（在跑的不收进组，一般是 null；留着给运行记录里的旧数据） */
    val running: ToolEvent?,
    val failed: Int,
    val linesAdded: Int,
    val linesRemoved: Int,
    /** 做了哪几种：「执行命令 ×3、修改文件 ×2」，按第一次出现的顺序 */
    val kinds: String,
)

fun toolTitle(tool: ToolEvent): String = tool.title?.ifBlank { tool.name } ?: tool.name

// MARK: 助手页的日志

/** 助手页一行：单独一条日志，或者收起来的一串连续的工具调用 */
sealed interface JournalItem {
    val key: String
    val first: JournalEntry

    data class Single(val entry: JournalEntry) : JournalItem {
        override val key: String get() = entry.id
        override val first: JournalEntry get() = entry
    }

    data class Tools(val entries: List<JournalEntry>) : JournalItem {
        override val key: String get() = "tools:" + entries.first().id
        override val first: JournalEntry get() = entries.first()

        /** 「search_memory ×2、say」：按第一次出现的顺序 */
        val kinds: String
            get() {
                val counts = LinkedHashMap<String, Int>()
                entries.forEach { counts.merge(journalToolName(it), 1, Int::plus) }
                return counts.entries.joinToString("、") { (name, n) -> if (n > 1) "$name ×$n" else name }
            }
    }
}

fun journalToolName(entry: JournalEntry): String = entry.tool?.name?.takeIf { it.isNotBlank() } ?: entry.text

/** 连着两条以上的工具调用收成一行；一天的开头（[dayStarts]，上面要画日期）把它们隔开 */
fun groupJournal(entries: List<JournalEntry>, dayStarts: Set<String>): List<JournalItem> {
    val out = ArrayList<JournalItem>(entries.size)
    var i = 0
    while (i < entries.size) {
        if (entries[i].kind != "tool") {
            out += JournalItem.Single(entries[i])
            i++
            continue
        }
        var j = i + 1
        while (j < entries.size && entries[j].kind == "tool" && entries[j].id !in dayStarts) j++
        out += if (j - i >= 2) JournalItem.Tools(entries.subList(i, j).toList()) else JournalItem.Single(entries[i])
        i = j
    }
    return out
}

fun opsSummary(tools: List<ToolEvent>): OpsSummary {
    val counts = LinkedHashMap<String, Int>()
    var added = 0
    var removed = 0
    for (tool in tools) {
        counts.merge(toolTitle(tool), 1, Int::plus)
        fileLineCounts(tool)?.let { (a, r) ->
            added += a
            removed += r
        }
    }
    return OpsSummary(
        count = tools.size,
        running = tools.lastOrNull { it.status == "running" },
        failed = tools.count { it.status == "failed" },
        linesAdded = added,
        linesRemoved = removed,
        kinds = counts.entries.joinToString("、") { (title, n) -> if (n > 1) "$title ×$n" else title },
    )
}
