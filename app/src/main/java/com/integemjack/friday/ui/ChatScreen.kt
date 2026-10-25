package com.integemjack.friday.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.integemjack.friday.proto.ChatMessage
import com.integemjack.friday.proto.MessageBlock
import com.integemjack.friday.proto.ToolEvent
import com.integemjack.friday.proto.blockText
import com.integemjack.friday.proto.blockThinking
import com.integemjack.friday.proto.blockTool
import kotlinx.coroutines.launch

/** 会话窗口：消息流（text / thinking / 工具卡片）+ 发送 / 停止 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onBack: () -> Unit) {
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    val chat = state.chat
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    val messages = chat?.messages ?: emptyList()

    // 流式更新时自动滚到底
    LaunchedEffect(messages.size, messages.lastOrNull()?.blocks?.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(chat?.title?.ifBlank { "（无标题）" } ?: "会话", maxLines = 1)
                        if (chat?.runtime?.generating == true) {
                            Text("生成中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        model.client.closeConversation()
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("发消息…") },
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                if (chat?.runtime?.generating == true) {
                    IconButton(onClick = { model.client.stop() }) {
                        Icon(Icons.Filled.Stop, contentDescription = "停止", tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    IconButton(
                        enabled = input.isNotBlank(),
                        onClick = {
                            val text = input.trim()
                            if (text.isEmpty()) return@IconButton
                            input = ""
                            scope.launch { runCatching { model.client.submit(text) } }
                        },
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送") }
                }
            }
        },
    ) { padding ->
        if (chat == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("会话已关闭")
            }
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { message -> MessageBubble(message) }
            chat.runtime?.let { rt ->
                if (rt.generating && (messages.isEmpty() || messages.last().role != "assistant")) {
                    item { GeneratingDots() }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val fromUser = message.role == "user"
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = if (fromUser) 48.dp else 0.dp, end = if (fromUser) 0.dp else 48.dp),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
    ) {
        if (message.fromAgent != null) {
            Text(
                "子 Agent · ${message.fromAgent}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (message.sentBy != null) {
            Text("助手发送 · 来自 ${message.sentBy}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp, if (fromUser) 4.dp else 16.dp, if (fromUser) 16.dp else 4.dp, 16.dp))
                .background(
                    if (fromUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            message.blocks.forEach { block -> BlockView(block) }
            message.text.takeIf { it.isEmpty() && message.blocks.none { b -> blockText(b) != null || blockTool(b) != null } }
                ?.let { Text("（无内容）", style = MaterialTheme.typography.bodySmall) }
        }
        if (message.error != null) {
            Text("⚠ ${message.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (message.stoppedByUser) {
            Text("已停止", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun BlockView(block: MessageBlock) {
    val text = blockText(block)
    if (text != null) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val thinking = blockThinking(block)
    if (thinking != null) {
        ThinkingBlock(thinking)
        return
    }
    val tool = blockTool(block)
    if (tool != null) {
        ToolCard(tool)
        return
    }
    when (block.case) {
        "media" -> Text("📎 附件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {}
    }
}

@Composable
private fun ThinkingBlock(content: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.clickable { expanded = !expanded }.animateContentSize()) {
        Text(
            if (expanded) "思考 ▾" else "思考 …",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded && content.isNotBlank()) {
            Text(
                content,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ToolCard(tool: ToolEvent) {
    var expanded by remember { mutableStateOf(false) }
    val (dot, label) = when (tool.status) {
        "awaitingApproval" -> MaterialTheme.colorScheme.tertiary to "待确认"
        "running" -> MaterialTheme.colorScheme.primary to "运行中"
        "succeeded" -> MaterialTheme.colorScheme.secondary to "完成"
        "failed" -> MaterialTheme.colorScheme.error to "失败"
        "denied" -> MaterialTheme.colorScheme.onSurfaceVariant to "已拒绝"
        else -> MaterialTheme.colorScheme.outline to tool.status
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(dot)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                tool.title?.ifBlank { tool.name } ?: tool.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (tool.status == "running") {
                CircularProgressIndicator(Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = dot)
        }
        if (!expanded) {
            val brief = tool.result.lineSequence().firstOrNull { it.isNotBlank() }
            if (!brief.isNullOrBlank()) {
                Text(
                    brief,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        } else {
            if (tool.summaryOrPreview().isNotBlank()) {
                Text(tool.summaryOrPreview(), style = MaterialTheme.typography.bodySmall)
            }
            if (tool.arguments.isNotBlank() && tool.arguments != "{}") {
                Text(
                    tool.arguments.take(2000),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tool.result.isNotBlank()) {
                Text(
                    tool.result.take(4000),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            // 子 Agent 运行记录（递归一层）
            tool.transcript.forEach { BlockView(it) }
        }
    }
}

private fun ToolEvent.summaryOrPreview(): String = preview ?: ""

@Composable
private fun GeneratingDots() {
    Row(
        Modifier.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("思考中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
