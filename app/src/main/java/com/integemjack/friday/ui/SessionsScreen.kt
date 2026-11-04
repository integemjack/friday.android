package com.integemjack.friday.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.integemjack.friday.proto.ConnState
import com.integemjack.friday.proto.ConversationSummary
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 会话列表：整段嵌在侧栏里（没有单独的会话页），所以它**不画任何页头、也没有悬浮按钮**——
 * 「会话」标题、计数和「新建」都在侧栏那条头部里。
 *
 * 按置顶 / 今天 / 昨天 / 更早分组；[selectedId] 那条（会话页正开着的）整行高亮。
 * 点一条 → 先收侧栏，再 [onOpen]（AppRoot 去订那段会话、切到会话页）；长按置顶 / 重命名 / 删除。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    onOpen: (String) -> Unit,
    selectedId: String? = null,
    onGoSettings: () -> Unit = {},
) {
    val closeDrawer = LocalCloseDrawer.current
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var newError by remember { mutableStateOf<String?>(null) }
    var menuSummary by remember { mutableStateOf<ConversationSummary?>(null) }
    var renaming by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleting by remember { mutableStateOf<ConversationSummary?>(null) }

    // 没连上、但有上次存下来的列表：照样列出来（点开看存下来的内容），顶上一行说明是离线的
    val showList = state.conn is ConnState.Connected || state.summaries.isNotEmpty()
    Column(Modifier.fillMaxSize()) {
        if (showList && state.conn !is ConnState.Connected) OfflineLine(state.conn, state.cachedAt)
        val conn = state.conn
        if (showList) {
                val groups = remember(state.summaries) { groupSummaries(state.summaries) }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (state.summaries.isEmpty()) {
                        item {
                            DrawerHint(
                                icon = Icons.AutoMirrored.Filled.Chat,
                                title = "还没有会话",
                                hint = "点上面的「新建」开一段，或者跟助手说一声让它开。",
                            )
                        }
                    }
                    groups.forEach { (label, list) ->
                        item(key = "group-$label") { GroupLabel(label) }
                        items(list, key = { it.id }) { s ->
                            Box {
                                SummaryRow(
                                    s,
                                    selected = s.id == selectedId,
                                    generating = state.runner?.isGenerating(s.id) == true,
                                    background = state.runner?.hasBackground(s.id) == true,
                                    onClick = {
                                        closeDrawer?.invoke()
                                        onOpen(s.id)
                                    },
                                    onLongPress = { menuSummary = s },
                                )
                                SummaryMenu(
                                    summary = menuSummary,
                                    anchor = s.id,
                                    onDismiss = { menuSummary = null },
                                    onRename = { renaming = it },
                                    onDelete = { deleting = it },
                                    onTogglePin = { summary ->
                                        scope.launch {
                                            runCatching { model.client.setPinned(summary.id, !summary.pinned) }
                                                .onFailure { newError = it.message }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
        } else when (conn) {
            is ConnState.Connecting -> Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    conn.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            is ConnState.Disconnected -> DrawerHint(
                icon = Icons.Filled.WifiOff,
                title = "还没有连接远程机器",
                hint = "到设置里登 SChat 账号、选那台远程 Friday，连上后这里列出它的全部会话。",
                action = "去设置",
                onAction = onGoSettings,
            )
            is ConnState.Failed -> DrawerHint(
                icon = Icons.Filled.WifiOff,
                title = "连接失败",
                hint = conn.message,
                tint = MaterialTheme.colorScheme.error,
                action = "去设置",
                onAction = onGoSettings,
            )
            is ConnState.Connected -> Unit
        }
    }

    newError?.let { err ->
        AlertDialog(
            onDismissRequest = { newError = null },
            title = { Text("操作失败") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { newError = null }) { Text("好") } },
        )
    }

    renaming?.let { summary ->
        RenameDialog(
            initial = summary.title,
            onDismiss = { renaming = null },
            onConfirm = { title ->
                renaming = null
                scope.launch {
                    runCatching { model.client.renameConversation(summary.id, title) }
                        .onFailure { newError = it.message }
                }
            },
        )
    }

    deleting?.let { summary ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删掉这段会话？") },
            text = { Text("「${summary.title.ifBlank { "无标题" }}」会被删掉，它的媒体文件也一起删。远程那边正在跑的任务会被停下。删了不能恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        runCatching { model.client.removeConversation(summary.id) }
                            .onFailure { newError = it.message }
                    }
                }) { Text("删掉", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("算了") } },
        )
    }
}

/**
 * 分组：置顶的单独一组，其余按更新时间落到今天 / 昨天 / 更早。
 * 组内保持服务端给的顺序（服务端已经按时间排好了），只是把它们归到对应的组下面。
 */
/** 离线时列表顶上那一行：在连 / 断着，显示的是哪会儿的内容 */
@Composable
private fun OfflineLine(conn: ConnState, cachedAt: Long?) {
    val scheme = MaterialTheme.colorScheme
    val time = cachedAt?.let { formatTime(java.time.Instant.ofEpochMilli(it).toString()) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainerHighest.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (conn is ConnState.Connecting) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        } else {
            Icon(Icons.Filled.WifiOff, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(7.dp))
        Text(
            when (conn) {
                is ConnState.Connecting -> "正在连接…" + (time?.let { " · 先显示 $it 的" } ?: "")
                else -> "离线" + (time?.let { " · 显示的是 $it 的内容" } ?: "")
            },
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun groupSummaries(all: List<ConversationSummary>): List<Pair<String, List<ConversationSummary>>> {
    val today = LocalDate.now()
    val pinned = mutableListOf<ConversationSummary>()
    val todays = mutableListOf<ConversationSummary>()
    val yesterdays = mutableListOf<ConversationSummary>()
    val earlier = mutableListOf<ConversationSummary>()
    all.forEach { s ->
        if (s.pinned) {
            pinned += s
            return@forEach
        }
        val day = runCatching { Instant.parse(s.updatedAt).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        when (day) {
            today -> todays += s
            today.minusDays(1) -> yesterdays += s
            else -> earlier += s
        }
    }
    return listOf("置顶" to pinned, "今天" to todays, "昨天" to yesterdays, "更早" to earlier)
        .filter { it.second.isNotEmpty() }
}

@Composable
private fun GroupLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 3.dp),
    )
}

/** 长按会话弹出的菜单（挂在对应那一行的 Box 里） */
@Composable
private fun SummaryMenu(
    summary: ConversationSummary?,
    anchor: String,
    onDismiss: () -> Unit,
    onRename: (ConversationSummary) -> Unit,
    onDelete: (ConversationSummary) -> Unit,
    onTogglePin: (ConversationSummary) -> Unit,
) {
    if (summary == null || summary.id != anchor) return
    var expanded by remember(summary.id) { mutableStateOf(true) }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false; onDismiss() }) {
        DropdownMenuItem(
            text = { Text(if (summary.pinned) "取消置顶" else "置顶") },
            leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
            onClick = { expanded = false; onDismiss(); onTogglePin(summary) },
        )
        DropdownMenuItem(
            text = { Text("重命名") },
            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            onClick = { expanded = false; onDismiss(); onRename(summary) },
        )
        DropdownMenuItem(
            text = { Text("删掉", color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            onClick = { expanded = false; onDismiss(); onDelete(summary) },
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var title by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onConfirm(title.trim()) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 侧栏里的提示卡：没连上 / 连接失败 / 还没有会话。比整页的空状态紧凑，不占满侧栏 */
@Composable
private fun DrawerHint(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    hint: String?,
    tint: Color = MaterialTheme.colorScheme.primary,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.7f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint = tint, size = 30.dp)
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null) {
            FilledTonalButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) { Text(action) }
        }
    }
}

/**
 * 侧栏里的一条会话：字母牌 + 标题 + 「时间 · 目录」，右边在跑转圈 / 草稿标签。
 * 不再是一张张白卡片（侧栏只有 3/4 宽，卡片堆起来很重），平时透明，按下 / 选中才有底色。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SummaryRow(
    s: ConversationSummary,
    selected: Boolean,
    generating: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    /** 主 Agent 这一轮说完了，派到后台的子 Agent 还在跑 */
    background: Boolean = false,
) {
    val title = s.title.ifBlank { "（无标题）" }
    val scheme = MaterialTheme.colorScheme
    // 按下去轻微收缩 + 底色深一档：点下去要有反应（打开会话要等远程回应）
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, tween(120), label = "rowScale")
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    selected -> scheme.primaryContainer.copy(alpha = 0.75f)
                    pressed -> scheme.surfaceContainerHighest.copy(alpha = 0.8f)
                    else -> Color.Transparent
                }
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonogramBadge(title, size = 34.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTime(s.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                s.workingDirectoryName?.let { dir ->
                    Spacer(Modifier.width(6.dp))
                    SepDot()
                    Spacer(Modifier.width(6.dp))
                    Text(
                        dir,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        when {
            generating -> {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            }
            background -> {
                Spacer(Modifier.width(8.dp))
                Chip("后台", color = scheme.tertiary)
            }
            s.hasDraft -> {
                Spacer(Modifier.width(8.dp))
                Chip("草稿", color = scheme.tertiary)
            }
            else -> Unit
        }
    }
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** 时间：今天只给几点几分，昨天给「昨天」，更早才带日期（一列全是 11-04 18:23 看不出先后） */
private fun formatTime(iso: String): String = runCatching {
    val at = Instant.parse(iso).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    when (at.toLocalDate()) {
        today -> timeFormatter.format(at)
        today.minusDays(1) -> "昨天 " + timeFormatter.format(at)
        else -> dayFormatter.format(at)
    }
}.getOrDefault(iso.take(16))
