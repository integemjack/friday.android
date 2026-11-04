package com.integemjack.friday.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.integemjack.friday.proto.FridayState
import com.integemjack.friday.proto.LoopTask
import com.integemjack.friday.proto.TeamRun
import com.integemjack.friday.proto.TerminalInfo
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「后台任务」：不挡着对话、在后台跑着的东西——派到后台的子 Agent、内置终端（run_in_terminal 开的命令）、
 * 定时任务（/loop）。会话页输入框上面一行，点开一个面板；助手页列助手名下的定时任务
 */
data class BackgroundTasks(
    val agents: List<TeamRun> = emptyList(),
    val terminals: List<TerminalInfo> = emptyList(),
    val loops: List<LoopTask> = emptyList(),
) {
    val runningAgents: List<TeamRun> get() = agents.filter { it.running }
    val runningTerminals: List<TerminalInfo> get() = terminals.filter { it.running }

    /** 没有可列的（跑完了的子 Agent 不算；跑完了的终端还在列表里、能看输出，算） */
    val isEmpty: Boolean get() = runningAgents.isEmpty() && terminals.isEmpty() && loops.isEmpty()
    val anyRunning: Boolean get() = runningAgents.isNotEmpty() || runningTerminals.isNotEmpty() || loops.any { it.running }

    /** 「1 个子 Agent、2 个终端在跑、1 个定时任务」 */
    val summary: String
        get() = buildList {
            if (runningAgents.isNotEmpty()) add("${runningAgents.size} 个子 Agent")
            if (terminals.isNotEmpty()) {
                val running = runningTerminals.size
                add(if (running > 0) "$running 个终端在跑" else "${terminals.size} 个终端")
            }
            if (loops.isNotEmpty()) add("${loops.size} 个定时任务")
        }.joinToString("、")
}

/** 这段会话派到后台的子 Agent（主 Agent 这一轮说完了它们还在跑） */
fun backgroundRuns(team: List<TeamRun>, conversationID: String?): List<TeamRun> =
    if (conversationID == null) emptyList()
    else team.filter { it.conversationID == conversationID && (it.background || it.detached) }

/** 一段会话的后台任务：它的后台子 Agent、它的终端、给它的定时任务 */
fun conversationBackground(state: FridayState, conversationID: String?): BackgroundTasks =
    if (conversationID == null) BackgroundTasks()
    else BackgroundTasks(
        agents = backgroundRuns(state.team, conversationID),
        terminals = state.terminals.filter { it.conversationID == conversationID },
        loops = state.loops.filter { it.target == "conversation" && it.conversationID == conversationID },
    )

/** 助手名下的定时任务（助手那里 /loop 建的） */
fun assistantBackground(state: FridayState): BackgroundTasks =
    BackgroundTasks(loops = state.loops.filter { it.target == "assistant" })

/** 「在执行「执行命令」 · 第 5 步 · 已运行 3 分 2 秒」：按字段和现在的时间算，每秒刷新 */
fun runStatusLine(run: TeamRun, nowMillis: Long): String {
    val doing = when {
        run.stopRequested -> "正在停下"
        !run.running -> "已结束"
        run.activityKind == "tool" -> "在执行「${run.activityTitle?.ifBlank { null } ?: "工具"}」"
        run.activityKind == "generating" -> "在想"
        run.activityKind == "waitingChildren" -> "等它派的子 Agent"
        run.activityKind == "waitingApproval" -> "等你确认"
        run.activityKind == "queued" -> "排队中"
        else -> "在跑"
    }
    val parts = mutableListOf(doing)
    if (run.steps > 0) parts += "第 ${run.steps} 步"
    run.started?.let { started ->
        runCatching { Instant.parse(started).toEpochMilli() }.getOrNull()?.let { at ->
            parts += "已运行 " + formatElapsed(((nowMillis - at) / 1000).coerceAtLeast(0))
        }
    }
    return parts.joinToString(" · ")
}

fun formatElapsed(seconds: Long): String = when {
    seconds < 60 -> "$seconds 秒"
    seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
    else -> "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分"
}

/** 「工作日 17:45 · 下次明天 17:45 · 跑过 12 次」；在跑、暂停了的照实写 */
fun loopStatusLine(loop: LoopTask, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val parts = mutableListOf(loop.describeTrigger(zone))
    when {
        loop.running -> parts += "在跑"
        loop.paused -> parts += "已暂停"
        else -> loop.nextRun?.let { formatWhen(it, nowMillis, zone) }?.let { parts += "下次 $it" }
    }
    if (loop.runCount > 0) parts += "跑过 ${loop.runCount} 次"
    return parts.joinToString(" · ")
}

/** 今天「17:45」、明天「明天 17:45」、更远「11月7日 17:45」 */
fun formatWhen(iso: String, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
    val at = runCatching { Instant.parse(iso).atZone(zone) }.getOrNull() ?: return null
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val clock = "%02d:%02d".format(at.hour, at.minute)
    return when (at.toLocalDate()) {
        today -> clock
        today.plusDays(1) -> "明天 $clock"
        else -> "${at.monthValue}月${at.dayOfMonth}日 $clock"
    }
}

/** 面板里能做的事（会话页、助手页各给各的） */
class BackgroundActions(
    val stopAgent: (String) -> Unit = {},
    val closeTerminal: (String) -> Unit = {},
    val terminalOutput: suspend (String) -> String = { "" },
    val setLoopPaused: (id: String, paused: Boolean) -> Unit = { _, _ -> },
    val runLoopNow: (String) -> Unit = {},
)

// MARK: 输入框上面那一行

/** 「转圈 · 后台：1 个子 Agent、1 个终端在跑、1 个定时任务  在执行「执行命令」 ›」，点开面板 */
@Composable
fun BackgroundBar(tasks: BackgroundTasks, onOpen: () -> Unit) {
    if (tasks.isEmpty) return
    val scheme = MaterialTheme.colorScheme
    val now by rememberNow()
    // 后面那句淡字：在跑的子 Agent 在做什么 > 在跑的终端命令 > 最近一个定时任务什么时候跑
    val detail = tasks.runningAgents.firstOrNull()?.let { run ->
        if (run.activityKind == "tool") "在执行「${run.activityTitle.orEmpty()}」" else run.name
    } ?: tasks.runningTerminals.firstOrNull()?.label
        ?: tasks.loops.firstOrNull { it.running }?.let { "在跑：${it.label}" }
        ?: tasks.loops.filter { !it.paused }.mapNotNull { loop -> loop.nextRun?.let { loop to it } }
            .minByOrNull { it.second }?.let { (loop, next) -> "下次 ${formatWhen(next, now).orEmpty()} ${loop.label}" }
        ?: ""
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.background)
            .background(scheme.tertiaryContainer.copy(alpha = 0.34f))
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tasks.anyRunning) {
            CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp, color = scheme.tertiary)
        } else {
            Icon(Icons.Filled.Schedule, contentDescription = null, tint = scheme.tertiary, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text("后台：${tasks.summary}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurface, maxLines = 1)
        Text(
            if (detail.isBlank()) "" else "  $detail",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Filled.ChevronRight, contentDescription = "看后台任务", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** 现在的时间，每秒刷新（运行了多久、下次什么时候） */
@Composable
private fun rememberNow() = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(1000)
        value = System.currentTimeMillis()
    }
}

// MARK: 面板

/** 后台任务的面板（Modal）。[onDismiss] 收起 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSheet(tasks: BackgroundTasks, actions: BackgroundActions, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { BackgroundContent(tasks, actions) }
}

/** 面板里的内容（单独拿出来：界面测试里不开 Modal 窗口也能验） */
@Composable
fun BackgroundContent(tasks: BackgroundTasks, actions: BackgroundActions) {
    val now by rememberNow()
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 600.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("后台任务", style = MaterialTheme.typography.titleMedium)
        if (tasks.isEmpty) {
            Text("现在没有", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        val agents = tasks.agents.filter { it.running || it.stopRequested }
        if (agents.isNotEmpty()) {
            SectionTitle("子 Agent", "派出去后接着跑，做完把结果交回这段会话；停止键不停它们，要停在这里停")
            agents.forEachIndexed { index, run ->
                if (index > 0) RowDivider()
                key(run.id) { AgentRow(run, now, actions) }
            }
            val stoppable = agents.filter { it.running && !it.stopRequested }
            if (stoppable.size > 1) {
                OutlinedButton(onClick = { stoppable.forEach { actions.stopAgent(it.id) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("全部停下（${stoppable.size}）", color = scheme.error)
                }
            }
        }
        if (tasks.terminals.isNotEmpty()) {
            SectionTitle("终端", "智能体在内置终端里开的命令（run_in_terminal），关掉对话它们也接着跑")
            tasks.terminals.forEachIndexed { index, terminal ->
                if (index > 0) RowDivider()
                key(terminal.id) { TerminalRow(terminal, actions) }
            }
        }
        if (tasks.loops.isNotEmpty()) {
            SectionTitle("定时任务", "到点自己跑（/loop）；暂停了恢复时不补错过的")
            tasks.loops.forEachIndexed { index, loop ->
                if (index > 0) RowDivider()
                key(loop.id) { LoopRow(loop, now, actions) }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, hint: String) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

/** 一行的骨架：左边状态（转圈 / 图标），右边内容 */
@Composable
private fun TaskRow(running: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, iconTint: Color, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp).width(LineIndent)) {
            if (running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            else Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { content() }
    }
}

@Composable
private fun SmallButton(text: String, color: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun AgentRow(run: TeamRun, now: Long, actions: BackgroundActions) {
    val scheme = MaterialTheme.colorScheme
    TaskRow(running = run.running && !run.stopRequested, icon = Icons.Filled.SmartToy, iconTint = scheme.outline) {
        Text(run.name.ifBlank { "子 Agent" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(runStatusLine(run, now), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        run.lastOutput?.trim()?.takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (run.running && !run.stopRequested) {
            Row { SmallButton("停下", scheme.error) { actions.stopAgent(run.id) } }
        }
    }
}

/** 终端：命令（等宽字）+ 在跑 / 退出码；能看最近的输出、结束 / 关掉 */
@Composable
private fun TerminalRow(terminal: TerminalInfo, actions: BackgroundActions) {
    val scheme = MaterialTheme.colorScheme
    var showOutput by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var output by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showOutput, reload) {
        if (!showOutput) return@LaunchedEffect
        error = null
        runCatching { actions.terminalOutput(terminal.id) }
            .onSuccess { output = it }
            .onFailure { error = it.message ?: "取不到输出" }
    }
    TaskRow(running = terminal.running, icon = Icons.Filled.Terminal, iconTint = if (terminal.exitCode == 0) scheme.outline else scheme.error) {
        Text(
            terminal.label,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (terminal.running) "在跑" else "已结束" + (terminal.exitCode?.let { " · 退出码 $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = if (!terminal.running && terminal.exitCode != null && terminal.exitCode != 0) scheme.error else scheme.onSurfaceVariant,
        )
        if (showOutput) {
            Column(Modifier.fillMaxWidth().animateContentSize()) {
                when {
                    error != null -> Text(error!!, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                    output == null -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    output!!.isBlank() -> Text("（还没有输出）", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    else -> MonospaceBlock(output!!)
                }
            }
        }
        Row {
            SmallButton(if (showOutput) "收起输出" else "看输出") { showOutput = !showOutput }
            if (showOutput) SmallButton("刷新") { reload++ }
            if (terminal.running) SmallButton("结束", scheme.error) { actions.closeTerminal(terminal.id) }
            else SmallButton("关掉") { actions.closeTerminal(terminal.id) }
        }
    }
}

/** 定时任务：标题 / 内容第一行 + 「工作日 17:45 · 下次明天 17:45 · 跑过 12 次」；能马上跑、暂停 / 恢复 */
@Composable
private fun LoopRow(loop: LoopTask, now: Long, actions: BackgroundActions) {
    val scheme = MaterialTheme.colorScheme
    TaskRow(running = loop.running, icon = Icons.Filled.Schedule, iconTint = if (loop.paused) scheme.outline else scheme.tertiary) {
        Text(
            loop.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (loop.paused) scheme.onSurfaceVariant else scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(loopStatusLine(loop, now), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        loop.lastResult?.trim()?.takeIf { it.isNotEmpty() }?.let {
            Text("上次：$it", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row {
            if (!loop.running) SmallButton("马上跑") { actions.runLoopNow(loop.id) }
            SmallButton(if (loop.paused) "恢复" else "暂停") { actions.setLoopPaused(loop.id, !loop.paused) }
        }
    }
}
