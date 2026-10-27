package com.integemjack.friday.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.integemjack.friday.AppModel
import com.integemjack.friday.proto.ConnState
import com.integemjack.friday.proto.ConversationSummary
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 会话列表（或未连接时的连接表单） */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(onOpen: () -> Unit) {
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
                LazyColumn(Modifier.fillMaxSize()) {
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
                    item { Spacer(Modifier.height(88.dp)) }
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
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                )
            }
            is ConnState.Disconnected -> ConnectHint()
            is ConnState.Connecting -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(conn.message, style = MaterialTheme.typography.bodyMedium)
            }
            is ConnState.Failed -> ConnectHint(conn.message)
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
private fun ConnectHint(error: String? = null) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (error != null) "连接失败：$error" else "还没有连接远程机器",
            style = MaterialTheme.typography.bodyLarge,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "到「设置」里填那台装着 Friday 的机器（SSH），连上后这里显示它的全部会话。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SummaryRow(
    s: ConversationSummary,
    generating: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s.pinned) {
            Icon(
                Icons.Filled.PushPin, contentDescription = "置顶",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                s.title.ifBlank { "（无标题）" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatTime(s.updatedAt) + (s.workingDirectoryName?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (generating) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        if (s.hasDraft) Text("草稿", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatTime(iso: String): String = runCatching {
    timeFormatter.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault(iso.take(16))
