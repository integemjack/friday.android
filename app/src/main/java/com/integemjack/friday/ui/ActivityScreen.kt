package com.integemjack.friday.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.integemjack.friday.proto.SessionActivity
import com.integemjack.friday.ui.theme.LocalFridayExtra
import kotlinx.coroutines.launch

/** 活动总览：这台远程机器所有会话的实时动静 + 待确认的调用 / 提问 */
@Composable
fun ActivityScreen(onOpen: () -> Unit = {}) {
    val openDrawer = LocalOpenDrawer.current
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var openError by remember { mutableStateOf<String?>(null) }

    fun openSession(id: String) {
        scope.launch {
            runCatching { model.client.subscribeConversation(id) }
                .onSuccess { onOpen() }
                .onFailure { openError = it.message }
        }
    }

    LaunchedEffect(Unit) { model.client.refresh() }

    if (!state.connected) {
        Box(Modifier.fillMaxSize()) {
            if (openDrawer != null) {
                IconButton(onClick = openDrawer, modifier = Modifier.padding(start = 4.dp, top = 2.dp)) {
                    Icon(Icons.Filled.Menu, contentDescription = "打开侧边栏", modifier = Modifier.size(22.dp))
                }
            }
            EmptyHint("连接远程机器后，这里显示所有会话的实时动静和待确认的调用。")
        }
        return
    }

    val pending = state.activities.flatMap { a -> a.approvals.map { a to it } }
    val others = state.activities
    val running = others.count { it.status == "running" }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageHeader(
                title = "活动",
                count = if (running > 0) running else null,
                subtitle = when {
                    running > 0 -> "$running 个会话在跑" + if (pending.isNotEmpty()) " · ${pending.size} 个等你确认" else ""
                    pending.isNotEmpty() -> "${pending.size} 个等你确认"
                    else -> "现在都闲下来了"
                },
                dotColor = if (running > 0) MaterialTheme.colorScheme.primary
                else if (pending.isNotEmpty()) MaterialTheme.colorScheme.tertiary
                else LocalFridayExtra.current.success,
                onOpenDrawer = openDrawer,
            )
        }
        if (pending.isNotEmpty()) {
            item {
                SectionTitle(
                    icon = Icons.Filled.QuestionAnswer,
                    title = "待确认（${pending.size}）",
                    trailing = null,
                )
            }
            items(pending, key = { it.second.eventID }) { (activity, approval) ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f),
                            RoundedCornerShape(20.dp),
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(
                            icon = if (approval.isQuestion) Icons.Filled.QuestionAnswer else Icons.Filled.Bolt,
                            tint = MaterialTheme.colorScheme.tertiary,
                            size = 30.dp,
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            approval.title.ifBlank { approval.tool },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Chip(
                            if (approval.isQuestion) "要你选" else "要你批",
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (approval.summary.isNotBlank()) {
                        Text(
                            approval.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (approval.isQuestion) {
                        approval.questions?.forEach { q ->
                            Text(q.question, style = MaterialTheme.typography.bodyMedium)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                q.options.forEachIndexed { index, option ->
                                    val recommended = index == q.recommended
                                    // 左边那个图标已经是个圈了，文字里不要再拼一个「○」
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (recommended) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
                                                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
                                            )
                                            .clickable {
                                                scope.launch {
                                                    model.client.resolveApproval(
                                                        approval.eventID, approved = true,
                                                        answers = listOf(option.label),
                                                    )
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            if (recommended) Icons.Filled.RadioButtonChecked
                                            else Icons.Filled.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (recommended) MaterialTheme.colorScheme.tertiary
                                            else MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                option.label,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            option.detail?.let {
                                                Text(
                                                    it,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        if (recommended) {
                                            Spacer(Modifier.width(6.dp))
                                            Chip("推荐", color = MaterialTheme.colorScheme.tertiary)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    scope.launch { model.client.resolveApproval(approval.eventID, approved = true) }
                                },
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                            ) { Text("批准") }
                            OutlinedButton(
                                onClick = {
                                    scope.launch { model.client.resolveApproval(approval.eventID, approved = false) }
                                },
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                            ) { Text("拒绝") }
                        }
                    }
                    // 跳到那段会话：给个箭头，别让人以为这只是行说明
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { openSession(activity.id) }
                            .padding(end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "来自：${activity.title.ifBlank { "会话" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = "打开这段会话",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(2.dp))
            SectionTitle(icon = Icons.Filled.PlayCircle, title = "会话动静")
        }
        if (others.isEmpty()) {
            item { EmptyHint("没有在跑的会话") }
        }
        items(others, key = { it.id }) { a -> ActivityRow(a) { openSession(a.id) } }
    }

    openError?.let { err ->
        AlertDialog(
            onDismissRequest = { openError = null },
            title = { Text("打开会话失败") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { openError = null }) { Text("好") } },
        )
    }
}

@Composable
private fun ActivityRow(a: SessionActivity, onClick: () -> Unit) {
    val extra = LocalFridayExtra.current
    val scheme = MaterialTheme.colorScheme
    val (icon, tint, label) = when (a.status) {
        "running" -> Triple(Icons.Filled.PlayCircle, scheme.primary, "在跑")
        "approval", "question" -> Triple(Icons.Filled.QuestionAnswer, scheme.tertiary, "待确认")
        "quota" -> Triple(Icons.Filled.HourglassEmpty, scheme.secondary, "限流")
        "error" -> Triple(Icons.Filled.ErrorOutline, scheme.error, "出错")
        else -> Triple(Icons.Filled.CheckCircle, extra.success, "空闲")
    }
    // 在跑的那几行左边一根跳动的主色竖条，一眼能扫出哪些还活着
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (pressed) scheme.surfaceContainerHigh else scheme.surfaceContainerLow)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (a.status == "running") {
            Box(
                Modifier
                    .width(3.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(50))
                    .background(scheme.primary.copy(alpha = 0.55f))
            )
            Spacer(Modifier.width(9.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    a.title.ifBlank { "（无标题）" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Chip(label, color = tint)
            }
            if (a.currentToolTitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (a.status == "running") {
                        CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        "正在：${a.currentToolTitle}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            a.taskGoal?.let { goal ->
                Text(
                    "目标：$goal",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val doing = a.taskSteps.firstOrNull { it.status == "doing" }
                if (doing != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(5.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(scheme.primary)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            doing.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(
            icon = Icons.Filled.Bolt,
            title = "还没有动静",
            hint = text,
            tint = MaterialTheme.colorScheme.secondary,
        )
    }
}