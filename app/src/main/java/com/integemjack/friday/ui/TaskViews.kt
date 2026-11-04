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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.integemjack.friday.proto.TaskSnapshot
import com.integemjack.friday.proto.TaskStep
import com.integemjack.friday.proto.TeamRun
import com.integemjack.friday.ui.theme.LocalFridayExtra
import kotlinx.coroutines.delay
import java.time.Instant

// MARK: 任务状态

/**
 * 输入框上面的任务条：智能体记下的任务（update_task_state），一行「图标 · 在做的那步 · 2/5」+ 一道细进度条；
 * 点开看目标、全部步骤和要守的约束。没记任务、或者做完了也不在跑了，就不挂
 */
@Composable
fun TaskBar(task: TaskSnapshot, generating: Boolean) {
    if (task.finished && !generating) return
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val total = task.steps.size
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            // 输入框背后透明：先垫页面底色，淡色才不会透出后面滚动的字
            .background(scheme.background)
            .background(scheme.primaryContainer.copy(alpha = 0.30f))
            .animateContentSize(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Checklist, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                task.current?.text?.takeIf { it.isNotBlank() } ?: task.goal ?: "任务",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (total > 0) {
                Spacer(Modifier.width(8.dp))
                Text("${task.done}/$total", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = if (expanded) "收起任务" else "展开任务",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = if (expanded) 90f else -90f },
            )
        }
        if (total > 0) {
            LinearProgressIndicator(
                progress = { task.done.toFloat() / total },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(2.dp),
                color = scheme.primary,
                trackColor = scheme.primary.copy(alpha = 0.15f),
            )
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
            ) { TaskChecklist(task) }
        } else {
            Spacer(Modifier.padding(bottom = 6.dp))
        }
    }
}

/** 目标 + 一步一行（✓ 做完、● 在做、○ 还没做）+ 折着的约束。任务条点开、消息里「记下任务状态」点开都用它 */
@Composable
fun TaskChecklist(task: TaskSnapshot) {
    val scheme = MaterialTheme.colorScheme
    var showConstraints by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        task.goal?.takeIf { it.isNotBlank() }?.let {
            Text("目标", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
        }
        if (task.steps.isNotEmpty()) {
            Text(
                "步骤 ${task.done}/${task.steps.size}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            task.steps.forEach { step -> StepRow(step) }
        }
        if (task.constraints.isNotEmpty()) {
            Text(
                (if (showConstraints) "要守的约束 ▾" else "要守的约束（${task.constraints.size}）…"),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showConstraints = !showConstraints },
            )
            if (showConstraints) {
                task.constraints.forEach { c ->
                    Text("· $c", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: TaskStep) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalFridayExtra.current
    val (icon, tint) = when (step.status) {
        "done" -> Icons.Filled.CheckCircle to extra.success
        "doing" -> Icons.Filled.RadioButtonChecked to scheme.primary
        else -> Icons.Filled.RadioButtonUnchecked to scheme.outline
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            step.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (step.status == "done") scheme.onSurfaceVariant else scheme.onSurface,
        )
    }
}

// MARK: 后台的子 Agent

/** 这段会话派到后台的子 Agent（主 Agent 这一轮说完了它们还在跑） */
fun backgroundRuns(team: List<TeamRun>, conversationID: String?): List<TeamRun> =
    if (conversationID == null) emptyList()
    else team.filter { it.conversationID == conversationID && (it.background || it.detached) }

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

/** 输入框上面那一行：「转圈 · 2 个子 Agent 在后台 · 在执行「执行命令」」，点开面板 */
@Composable
fun BackgroundAgentsBar(runs: List<TeamRun>, onOpen: () -> Unit) {
    val running = runs.filter { it.running }
    if (running.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val latest = running.firstOrNull { it.activityKind == "tool" } ?: running.first()
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
        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp, color = scheme.tertiary)
        Spacer(Modifier.width(8.dp))
        Text(
            "${running.size} 个子 Agent 在后台",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurface,
        )
        Text(
            "  " + (if (latest.activityKind == "tool") "在执行「${latest.activityTitle.orEmpty()}」" else latest.name),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Filled.ChevronRight, contentDescription = "看后台的子 Agent", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** 后台子 Agent 的面板（Modal）。[onDismiss] 收起 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundAgentsSheet(runs: List<TeamRun>, onStop: (String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { BackgroundAgentsContent(runs, onStop) }
}

/** 面板里的内容（单独拿出来：界面测试里不开 Modal 窗口也能验） */
@Composable
fun BackgroundAgentsContent(runs: List<TeamRun>, onStop: (String) -> Unit) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }
    val scheme = MaterialTheme.colorScheme
    val running = runs.filter { it.running && !it.stopRequested }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("后台的子 Agent", style = MaterialTheme.typography.titleMedium)
        Text(
            "主 Agent 派出去后接着跑的，做完会把结果交回这段会话。停止键不停它们，要停在这里停。",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        if (runs.isEmpty()) {
            Text("现在没有", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        runs.forEachIndexed { index, run ->
            if (index > 0) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 2.dp).width(LineIndent)) {
                    if (run.running && !run.stopRequested) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    } else {
                        Icon(Icons.Filled.SmartToy, contentDescription = null, tint = scheme.outline, modifier = Modifier.size(16.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(run.name.ifBlank { "子 Agent" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(runStatusLine(run, now), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    run.lastOutput?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (run.running && !run.stopRequested) {
                    TextButton(
                        onClick = { onStop(run.id) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) { Text("停下", color = scheme.error) }
                }
            }
        }
        if (running.size > 1) {
            OutlinedButton(
                onClick = { running.forEach { onStop(it.id) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("全部停下（${running.size}）", color = scheme.error) }
        }
    }
}
