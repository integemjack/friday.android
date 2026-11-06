package com.integemjack.friday.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.integemjack.friday.proto.FridayState
import com.integemjack.friday.proto.LoopTask
import kotlinx.coroutines.launch

/** 助手页顶栏右上角：点开全部会话的后台任务；有在跑的时候角上写个数 */
@Composable
fun BackgroundTasksButton(state: FridayState, onClick: () -> Unit) {
    val running = allBackground(state).sumOf { it.tasks.runningCount }
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (running > 0) Badge { Text("$running") } }) {
            Icon(Icons.Filled.Checklist, contentDescription = "后台任务")
        }
    }
}

/** 这一页能做的事：都是结束一项，[ending] 里是已经点了、还没消失的 */
class EndTaskActions(
    val ending: Set<String> = emptySet(),
    val endAgent: (String) -> Unit = {},
    val endTerminal: (String) -> Unit = {},
    val endLoop: (String) -> Unit = {},
)

/** 全部会话的后台任务（子页面，从助手页右上角进来；外壳 [SwipePageHost]） */
@Composable
fun BackgroundTasksScreen(onBack: () -> Unit) {
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var ending by remember { mutableStateOf(emptySet<String>()) }
    var failure by remember { mutableStateOf<String?>(null) }

    // 点了就按住按钮，别连点（term.close 第二下回「找不到终端」）；成了等任务自己从列表里消失，没成放开
    fun end(id: String, block: suspend () -> Unit) {
        ending = ending + id
        scope.launch {
            runCatching { block() }.onFailure {
                ending = ending - id
                failure = it.message ?: ""
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = { HomeTopBar(title = "后台任务", onBack = onBack) },
    ) { padding ->
        BackgroundTasksContent(
            groups = allBackground(state),
            connected = state.connected,
            actions = EndTaskActions(
                ending = ending,
                endAgent = { id -> end(id) { model.client.stopAgent(id) } },
                endTerminal = { id -> end(id) { model.client.closeTerminal(id) } },
                endLoop = { id -> end(id) { model.client.removeLoop(id) } },
            ),
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            bottomPadding = padding.calculateBottomPadding(),
        )
    }

    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text("没结束") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("知道了") } },
        )
    }
}

/** 列表（单独拿出来：界面测试里不用真连接也能验） */
@Composable
fun BackgroundTasksContent(
    groups: List<BackgroundGroup>,
    connected: Boolean,
    actions: EndTaskActions,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val now by rememberNow()
    val scheme = MaterialTheme.colorScheme
    // 定时任务结束了就是删掉，要不回来：多问一句（子 Agent、终端同会话页的面板，点了就结束）
    var confirmLoop by remember { mutableStateOf<LoopTask?>(null) }
    LazyColumn(
        modifier.fillMaxSize().fadeTopEdge(),
        contentPadding = PaddingValues(start = 20.dp, end = 12.dp, top = TopFadeHeight, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!connected) {
            item(key = "offline") {
                Text(
                    if (groups.isEmpty()) "没连上，看不到后台任务" else "没连上，下面是断开前的样子",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        } else if (groups.isEmpty()) {
            item(key = "empty") {
                Text("现在没有后台任务", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
        groups.forEach { group ->
            item(key = "group:${group.conversationID}") {
                Text(
                    group.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(group.tasks.agents, key = { "agent:${it.id}" }) { run ->
                EndableRow(
                    running = true,
                    icon = Icons.Filled.SmartToy,
                    iconTint = scheme.outline,
                    title = run.name.ifBlank { "子 Agent" },
                    status = "子 Agent · " + runStatusLine(run, now),
                    enabled = connected && !run.stopRequested,
                    ending = run.stopRequested || run.id in actions.ending,
                    onEnd = { actions.endAgent(run.id) },
                )
            }
            items(group.tasks.terminals, key = { "terminal:${it.id}" }) { terminal ->
                EndableRow(
                    running = true,
                    icon = Icons.Filled.Terminal,
                    iconTint = scheme.outline,
                    title = terminal.label,
                    monospace = true,
                    status = "终端 · 在跑",
                    enabled = connected,
                    ending = terminal.id in actions.ending,
                    onEnd = { actions.endTerminal(terminal.id) },
                )
            }
            items(group.tasks.loops, key = { "loop:${it.id}" }) { loop ->
                EndableRow(
                    running = loop.running,
                    icon = Icons.Filled.Schedule,
                    iconTint = if (loop.paused) scheme.outline else scheme.tertiary,
                    title = loop.label,
                    status = "定时任务 · " + loopStatusLine(loop, now),
                    enabled = connected,
                    ending = loop.id in actions.ending,
                    onEnd = { confirmLoop = loop },
                )
            }
        }
    }
    confirmLoop?.let { loop ->
        AlertDialog(
            onDismissRequest = { confirmLoop = null },
            title = { Text("结束这个定时任务？") },
            text = {
                Text(
                    "「${loop.label}」会被删掉，以后不再跑，要跑得重新建。" +
                        if (loop.running) "这一次已经开始了，不会被打断。" else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLoop = null
                    actions.endLoop(loop.id)
                }) { Text("结束", color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLoop = null }) { Text("取消") } },
        )
    }
}

/** 一项后台任务：左边状态，中间是什么、到哪儿了，右边「结束」 */
@Composable
private fun EndableRow(
    running: Boolean,
    icon: ImageVector,
    iconTint: Color,
    title: String,
    status: String,
    enabled: Boolean,
    ending: Boolean,
    onEnd: () -> Unit,
    monospace: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TaskRow(running = running && !ending, icon = icon, iconTint = iconTint, modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(status, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(4.dp))
        SmallButton(if (ending) "结束中" else "结束", scheme.error, enabled = enabled && !ending, onClick = onEnd)
    }
}
