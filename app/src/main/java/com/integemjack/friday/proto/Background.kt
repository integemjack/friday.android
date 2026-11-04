package com.integemjack.friday.proto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 会话页「后台任务」里除子 Agent 以外的两样：内置终端（run_in_terminal 开的，TERMINAL.md）和定时任务（/loop，LOOPS.md）
 */

/** 一个内置终端（REMOTE.md §5 TerminalsState 的 TerminalInfo） */
data class TerminalInfo(
    val id: String,
    val conversationID: String,
    val title: String,
    /** 服务端算好的标题（客户端没有更好的标题时显示它） */
    val shell: String,
    /** 智能体用 run_in_terminal 开时跑的那条命令 */
    val command: String?,
    val running: Boolean,
    val exitCode: Int?,
) {
    /** 给人看的：命令，没有就标题 */
    val label: String get() = command?.takeIf { it.isNotBlank() } ?: title.ifBlank { shell }

    companion object {
        fun fromJson(o: JsonObject): TerminalInfo = TerminalInfo(
            id = o.str("id") ?: "",
            conversationID = o.str("conversationID") ?: "",
            title = o.str("title") ?: "",
            shell = o.str("shell") ?: "",
            command = o.str("command"),
            running = o.bool("running") ?: false,
            exitCode = o.long("exitCode")?.toInt(),
        )

        /** TerminalsState：`{terminals:[TerminalInfo], available}` */
        fun listFrom(o: JsonObject?): List<TerminalInfo> =
            (o?.get("terminals") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::fromJson) }.orEmpty()
    }
}

/** 一个定时任务（REMOTE.md §6 LoopsState 的任务：loops.json 里的那份 + targetLabel） */
data class LoopTask(
    val id: String,
    val number: Int,
    /** self / interval / weekly / once */
    val triggerKind: String,
    val minutes: Int?,
    /** 每周的哪几天：bit0 = 周一 … bit6 = 周日 */
    val days: Int?,
    val minuteOfDay: Int?,
    val at: String?,
    val content: String,
    /** conversation / assistant / system */
    val target: String,
    val conversationID: String?,
    val title: String?,
    val createdBy: String,
    val nextRun: String?,
    val lastRun: String?,
    /** 正在跑：这次开始的时间 */
    val runStartedAt: String?,
    val paused: Boolean,
    val runCount: Int,
    val lastResult: String?,
) {
    val running: Boolean get() = runStartedAt != null

    /** 给人看的：标题，没有就内容的第一行 */
    val label: String
        get() = title?.takeIf { it.isNotBlank() } ?: content.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "定时任务 #$number"

    /** 「每 10 分钟」「每天 9:00」「工作日 17:45」「每周一、三 10:00」「11月5日 14:00 一次」「自己定节奏」（同 Qt LoopTrigger::describe） */
    fun describeTrigger(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String = when (triggerKind) {
        "self" -> "自己定节奏"
        "interval" -> {
            val m = minutes ?: 0
            when {
                m > 0 && m % 1440 == 0 -> if (m == 1440) "每天一次" else "每 ${m / 1440} 天"
                m > 0 && m % 60 == 0 -> "每 ${m / 60} 小时"
                else -> "每 $m 分钟"
            }
        }
        "weekly" -> {
            val mask = days ?: 0
            val whenText = when (mask) {
                0x7f -> "每天"
                0x1f -> "工作日"
                0x60 -> "周末"
                else -> "每周" + (0 until 7).filter { mask and (1 shl it) != 0 }.joinToString("、") { DAY_NAMES[it] }
            }
            "$whenText ${clock(minuteOfDay ?: 0)}"
        }
        "once" -> at?.let { runCatching { java.time.Instant.parse(it).atZone(zone) }.getOrNull() }
            ?.let { "${it.monthValue}月${it.dayOfMonth}日 ${"%02d:%02d".format(it.hour, it.minute)} 一次" } ?: "一次"
        else -> triggerKind
    }

    companion object {
        private val DAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

        private fun clock(minuteOfDay: Int) = "%d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

        fun fromJson(o: JsonObject): LoopTask {
            val trigger = o["trigger"] as? JsonObject
            return LoopTask(
                id = o.str("id") ?: "",
                number = o.long("number")?.toInt() ?: 0,
                triggerKind = trigger?.str("kind") ?: "",
                minutes = trigger?.long("minutes")?.toInt(),
                days = trigger?.long("days")?.toInt(),
                minuteOfDay = trigger?.long("minuteOfDay")?.toInt(),
                at = trigger?.str("at"),
                content = o.str("content") ?: "",
                target = o.str("target") ?: "",
                conversationID = o.str("conversationID"),
                title = o.str("title"),
                createdBy = o.str("createdBy") ?: "",
                nextRun = o.str("nextRun"),
                lastRun = o.str("lastRun"),
                runStartedAt = o.str("runStartedAt"),
                paused = o.bool("paused") ?: false,
                runCount = o.long("runCount")?.toInt() ?: 0,
                lastResult = o.str("lastResult"),
            )
        }

        /** LoopsState：`{tasks:[任务]}` */
        fun listFrom(o: JsonObject?): List<LoopTask> =
            (o?.get("tasks") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::fromJson) }.orEmpty()
    }
}

private val ANSI_CSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
private val ANSI_OSC = Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)")
private val ANSI_OTHER = Regex("\u001B[@-Z\\\\-_]")

/**
 * 终端的原始输出（term.attach 给的最近 512 KB，带颜色、光标控制）变成能看的字：去掉控制序列，
 * 每行只留最后一次回车之后的（进度条那种原地刷新的行只看最后的样子），只要最后 [maxLines] 行
 */
fun terminalText(raw: ByteArray, maxLines: Int = 80): String {
    val text = String(raw, Charsets.UTF_8)
        .replace(ANSI_OSC, "")
        .replace(ANSI_CSI, "")
        .replace(ANSI_OTHER, "")
        .replace("\r\n", "\n")
    val lines = text.split('\n').map { line ->
        line.substringAfterLast('\r').filter { it == '\t' || it >= ' ' }.trimEnd()
    }
    return lines.dropLastWhile { it.isBlank() }.takeLast(maxLines).joinToString("\n")
}
