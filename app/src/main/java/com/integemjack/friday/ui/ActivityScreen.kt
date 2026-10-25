package com.integemjack.friday.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.dp
import com.integemjack.friday.proto.SessionActivity
import kotlinx.coroutines.launch

/** 活动总览：这台远程机器所有会话的实时动静 + 待确认的调用 / 提问 */
@Composable
fun ActivityScreen() {
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { model.client.refresh() }

    if (!state.connected) {
        EmptyHint("连接远程机器后，这里显示所有会话的实时动静和待确认的调用。")
        return
    }

    val pending = state.activities.flatMap { a -> a.approvals.map { a to it } }
    val others = state.activities

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (pending.isNotEmpty()) {
            item {
                Text(
                    "待确认（${pending.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            items(pending, key = { it.second.eventID }) { (activity, approval) ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(approval.title.ifBlank { approval.tool }, style = MaterialTheme.typography.titleSmall)
                        if (approval.summary.isNotBlank()) {
                            Text(approval.summary, style = MaterialTheme.typography.bodySmall)
                        }
                        if (approval.isQuestion) {
                            approval.questions?.forEach { q ->
                                Spacer(Modifier.height(8.dp))
                                Text(q.question, style = MaterialTheme.typography.bodyMedium)
                                Column {
                                    q.options.forEachIndexed { index, option ->
                                        TextButton(onClick = {
                                            scope.launch {
                                                model.client.resolveApproval(
                                                    approval.eventID, approved = true,
                                                    answers = listOf(option.label),
                                                )
                                            }
                                        }) {
                                            Text("○ ${option.label}" + (option.detail?.let { " — $it" } ?: "") +
                                                if (index == q.recommended) "（推荐）" else "")
                                        }
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.height(8.dp))
                            Row {
                                Button(onClick = {
                                    scope.launch { model.client.resolveApproval(approval.eventID, approved = true) }
                                }) { Text("批准") }
                                Spacer(Modifier.width(12.dp))
                                OutlinedButton(onClick = {
                                    scope.launch { model.client.resolveApproval(approval.eventID, approved = false) }
                                }) { Text("拒绝") }
                            }
                        }
                        Text(
                            "来自：${activity.title.ifBlank { "会话" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Text("会话动静", style = MaterialTheme.typography.titleMedium)
        }
        if (others.isEmpty()) {
            item { Text("没有在跑的会话", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(others, key = { it.id }) { a -> ActivityRow(a) }
    }
}

@Composable
private fun ActivityRow(a: SessionActivity) {
    val (icon, color) = when (a.status) {
        "running" -> "▶" to MaterialTheme.colorScheme.primary
        "approval", "question" -> "!" to MaterialTheme.colorScheme.tertiary
        "quota" -> "⏳" to MaterialTheme.colorScheme.secondary
        "error" -> "✕" to MaterialTheme.colorScheme.error
        else -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, color = color, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                Text(
                    a.title.ifBlank { "（无标题）" },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }
            if (a.currentToolTitle != null) {
                Text(
                    "正在：${a.currentToolTitle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            a.taskGoal?.let { goal ->
                Spacer(Modifier.height(4.dp))
                Text("目标：$goal", style = MaterialTheme.typography.bodySmall)
                val doing = a.taskSteps.firstOrNull { it.status == "doing" }
                if (doing != null) {
                    Text("▸ ${doing.text}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
