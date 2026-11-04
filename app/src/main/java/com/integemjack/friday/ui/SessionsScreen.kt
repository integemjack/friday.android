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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.integemjack.friday.proto.ConnState
import com.integemjack.friday.proto.ConversationSummary
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 会话列表（或未连接时的连接提示） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(onOpen: () -> Unit) {
    val openDrawer = LocalOpenDrawer.current
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var newError by remember { mutableStateOf<String?>(null) }
    var menuSummary by remember { mutableStateOf<ConversationSummary?>(null) }
    var renaming by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleting by remember { mutableStateOf<ConversationSummary?>(null) }

    Box(Modifier.fillMaxSize()) {
        when (val conn = state.conn) {
            is ConnState.Connected -> {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        PageHeader(
                            title = "会话",
                            count = state.summaries.size,
                            subtitle = "已连上 · " + conn.server.displayName.ifBlank { conn.server.hostname },
                            dotColor = MaterialTheme.colorScheme.tertiary,
                            onOpenDrawer = openDrawer,
                        )
                    }
                    if (state.summaries.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.AutoMirrored.Filled.Chat,
                                title = "还没有会话",
                                hint = "点右下角「新会话」开一段，或去活动页看远程在跑的活。",
                                modifier = Modifier.padding(top = 48.dp),
                            )
                        }
                    }
                    items(state.summaries, key = { it.id }) { s ->
                        Box {
                            SummaryRow(
                                s,
                                generating = state.runner?.isGenerating(s.id) == true,
                                onClick = {
                                    scope.launch {
                                        runCatching { model.client.subscribeConversation(s.id) }
                                            .onSuccess { onOpen() }
                                            .onFailure { newError = it.message }
                                    }
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
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch {
                            runCatching { model.client.newConversation() }
                                .onSuccess { onOpen() }
                                .onFailure { newError = it.message }
                        }
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("新会话") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp),
                )
            }
            is ConnState.Disconnected -> ConnectHint(onOpenDrawer = openDrawer)
            is ConnState.Connecting -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.height(14.dp))
                Text(conn.message, style = MaterialTheme.typography.bodyMedium)
            }
            is ConnState.Failed -> ConnectHint(conn.message, openDrawer)
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

/** 大标题：会话数 + 连的是哪台机器（已交给通用的 PageHeader） */

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

@Composable
private fun ConnectHint(error: String? = null, onOpenDrawer: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize()) {
        // 没连上时也要能拉出抽屉去设置
        if (onOpenDrawer != null) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.padding(start = 4.dp, top = 2.dp)) {
                Icon(Icons.Filled.Menu, contentDescription = "打开侧边栏", modifier = Modifier.size(22.dp))
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            EmptyState(
                icon = Icons.Filled.WifiOff,
                title = if (error != null) "连接失败" else "还没有连接远程机器",
                hint = error
                    ?: "到「设置」里登 SChat 账号、选那台远程 Friday（走 WebRTC 连），连上后这里显示它的全部会话。",
                tint = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SummaryRow(
    s: ConversationSummary,
    generating: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val title = s.title.ifBlank { "（无标题）" }
    val scheme = MaterialTheme.colorScheme
    // 按下去轻微收缩 + 底色更深一档：长列表里手指点下去要有反应，
    // 不然点完不知道点没点到（打开会话要等远程回应）
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(120), label = "rowScale")
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    pressed -> scheme.surfaceContainerHigh
                    // 置顶的会话底色略偏主色，跟下面的分开
                    s.pinned -> scheme.primaryContainer.copy(alpha = 0.32f)
                    else -> scheme.surfaceContainerLow
                }
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 置顶：左侧一根竖条，一眼看出上面几条是钉住的
        if (s.pinned) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(50))
                    .background(scheme.primary.copy(alpha = 0.55f))
            )
            Spacer(Modifier.width(8.dp))
        }
        MonogramBadge(title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (s.pinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "置顶",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTime(s.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                s.workingDirectoryName?.let { dir ->
                    Spacer(Modifier.width(7.dp))
                    SepDot()
                    Spacer(Modifier.width(7.dp))
                    Text(
                        dir,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            generating -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            s.hasDraft -> Chip("草稿", color = MaterialTheme.colorScheme.tertiary)
            else -> Unit
        }
    }
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** 时间：今天只给几点几分，昨天给「昨天」，更早才带日期（一列全是 11-04 18:23 看不出先后） */
private fun formatTime(iso: String): String = runCatching {
    val at = Instant.parse(iso).atZone(ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    when (at.toLocalDate()) {
        today -> timeFormatter.format(at)
        today.minusDays(1) -> "昨天 " + timeFormatter.format(at)
        else -> dayFormatter.format(at)
    }
}.getOrDefault(iso.take(16))