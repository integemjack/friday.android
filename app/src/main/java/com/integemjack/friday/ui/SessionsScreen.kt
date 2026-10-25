package com.integemjack.friday.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
@Composable
fun SessionsScreen(onOpen: () -> Unit) {
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var newError by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        when (val conn = state.conn) {
            is ConnState.Connected -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.summaries, key = { it.id }) { s ->
                        SummaryRow(s, generating = state.runner?.isGenerating(s.id) == true) {
                            scope.launch {
                                runCatching { model.client.subscribeConversation(s.id) }
                                    .onSuccess { onOpen() }
                                    .onFailure { newError = it.message }
                            }
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
            title = { Text("打开会话失败") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { newError = null }) { Text("好") } },
        )
    }
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

@Composable
private fun SummaryRow(s: ConversationSummary, generating: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
