package com.integemjack.friday.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.integemjack.friday.proto.BranchInfo
import com.integemjack.friday.proto.ChatMessage
import com.integemjack.friday.proto.MessageBlock
import com.integemjack.friday.proto.QueuedInput
import com.integemjack.friday.proto.Runtime
import com.integemjack.friday.proto.ToolEvent
import com.integemjack.friday.proto.blockMedia
import com.integemjack.friday.proto.blockText
import com.integemjack.friday.proto.blockThinking
import com.integemjack.friday.proto.blockTool
import kotlinx.coroutines.launch
import java.util.UUID

/** 会话窗口：消息流（text / thinking / 工具卡片 / 媒体）+ 附件 + 排队 + 发送 / 停止 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(onBack: () -> Unit) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val state by model.client.stateFlow.collectAsState()
    val chat = state.chat
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    val attachments = remember { androidx.compose.runtime.mutableStateListOf<PendingAttachment>() }
    var menuMessage by remember { mutableStateOf<ChatMessage?>(null) }

    val tree = remember(chat) { chat?.tree }
    val messages = tree?.path ?: emptyList()
    val branches = tree?.branches ?: emptyMap()
    val runtime = chat?.runtime
    val workingDirectory = chat?.workingDirectory

    // 上传前拷到本地的副本（发完就删）
    val localCopies = remember { mutableStateMapOf<String, java.io.File>() }

    fun updateAttachment(id: String, transform: (PendingAttachment) -> PendingAttachment) {
        val index = attachments.indexOfFirst { it.id == id }
        if (index >= 0) attachments[index] = transform(attachments[index])
    }

    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val fresh = uris.map { uri ->
            PendingAttachment(
                id = UUID.randomUUID().toString(),
                name = displayName(context, uri),
                size = displaySize(context, uri),
            ) to uri
        }
        attachments.addAll(fresh.map { it.first })
        fresh.forEach { (item, uri) ->
            scope.launch {
                val local = copyToUploadCache(context, uri, item.name)
                if (local == null) {
                    updateAttachment(item.id) { it.copy(error = "读不了这个文件") }
                    return@launch
                }
                localCopies[item.id] = local
                runCatching {
                    model.client.uploadFile(local) { fraction ->
                        updateAttachment(item.id) { it.copy(progress = fraction) }
                    }
                }.onSuccess { path ->
                    updateAttachment(item.id) { it.copy(progress = 1f, serverPath = path) }
                }.onFailure { error ->
                    updateAttachment(item.id) { it.copy(error = error.message ?: "上传失败") }
                }
            }
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris -> addAttachments(uris) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { addAttachments(listOf(it)) } }

    // 流式更新时自动滚到底（用户自己往上翻了就不抢他的滚动条）
    val lastSignature = messages.lastOrNull()?.let { "${it.id}:${it.blocks.size}" } ?: ""
    LaunchedEffect(messages.size, lastSignature) {
        if (messages.isEmpty()) return@LaunchedEffect
        val lastIndex = messages.size - 1
        val visible = listState.layoutInfo.visibleItemsInfo
        val nearBottom = visible.isEmpty() || visible.last().index >= lastIndex - 1
        if (nearBottom) listState.scrollToItem(lastIndex)
    }
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            visible.isEmpty() || visible.last().index >= info.totalItemsCount - 2
        }
    }

    val uploading = attachments.any { !it.done && it.error == null }
    val readyPaths = attachments.filter { it.done }.mapNotNull { it.serverPath }
    val canSend = (input.isNotBlank() || readyPaths.isNotEmpty()) && !uploading

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(chat?.title?.ifBlank { "（无标题）" } ?: "会话", maxLines = 1)
                        if (runtime?.generating == true) {
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
            Column(Modifier.fillMaxWidth().imePadding()) {
                runtime?.let { rt -> QueueBar(rt) }
                if (runtime?.canRetry == true && runtime?.generating != true) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "上一轮没成",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(onClick = { model.client.retry() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重试")
                        }
                    }
                }
                AttachmentChips(
                    attachments.toList(),
                    onRemove = { id ->
                        attachments.removeAll { it.id == id }
                        localCopies.remove(id)?.delete()
                    },
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AttachmentButton(
                        enabled = chat != null,
                        onPickMedia = {
                            mediaPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    )
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (uploading) "附件上传中…" else "发消息…") },
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(4.dp))
                    if (runtime?.generating == true) {
                        IconButton(onClick = { model.client.stop() }) {
                            Icon(Icons.Filled.Stop, contentDescription = "停止", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(
                            enabled = canSend,
                            onClick = {
                                val text = input.trim()
                                if (text.isEmpty() && readyPaths.isEmpty()) return@IconButton
                                input = ""
                                attachments.clear()
                                localCopies.values.forEach { it.delete() }
                                localCopies.clear()
                                scope.launch { runCatching { model.client.submit(text, readyPaths) } }
                            },
                        ) {
                            if (uploading) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (chat != null && messages.isNotEmpty() && !atBottom) {
                SmallFloatingActionButton(onClick = {
                    scope.launch { listState.animateScrollToItem(messages.size - 1) }
                }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "回到最新") }
            }
        },
    ) { padding ->
        if (chat == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("会话已关闭")
            }
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    Box {
                        MessageBubble(
                            message = message,
                            workingDirectory = workingDirectory,
                            branch = branches[message.id],
                            onSwitchBranch = { child, parentKey -> model.client.selectBranch(child, parentKey) },
                            onLongPress = { menuMessage = message },
                        )
                        MessageMenu(
                            message = menuMessage,
                            anchor = message.id,
                            canRetry = runtime?.canRetry == true && runtime?.generating != true,
                            onDismiss = { menuMessage = null },
                            onCopy = { text ->
                                clipboard.setText(AnnotatedString(text))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            },
                            onRetry = { model.client.retry() },
                        )
                    }
                }
                if (runtime?.generating == true && (messages.isEmpty() || messages.last().role != "assistant")) {
                    item { GeneratingDots() }
                }
            }
        }
    }
}

/** 长按消息弹出的菜单（挂在对应那条消息的 Box 里，位置才跟着消息走） */
@Composable
private fun MessageMenu(
    message: ChatMessage?,
    anchor: String,
    canRetry: Boolean,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
) {
    if (message == null || message.id != anchor) return
    val text = message.text
    var expanded by remember(message.id) { mutableStateOf(true) }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false; onDismiss() }) {
        if (text.isNotBlank()) {
            DropdownMenuItem(
                text = { Text("复制文本") },
                onClick = { expanded = false; onDismiss(); onCopy(text) },
            )
        }
        val toolText = message.blocks.mapNotNull { blockTool(it) }
            .joinToString("\n\n") { "${it.title ?: it.name}\n${it.result}".trim() }
        if (toolText.isNotBlank()) {
            DropdownMenuItem(
                text = { Text("复制工具输出") },
                onClick = { expanded = false; onDismiss(); onCopy(toolText) },
            )
        }
        if (canRetry) {
            DropdownMenuItem(
                text = { Text("重试上一轮") },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                onClick = { expanded = false; onDismiss(); onRetry() },
            )
        }
    }
}

/** 排队等着发的（上一轮被停下时是暂停的，点「接着发」才继续） */
@Composable
private fun QueueBar(runtime: Runtime) {
    val model = LocalAppModel.current
    val queue = runtime.queue
    if (queue.isEmpty() && !runtime.queuePaused) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (runtime.queuePaused) "排队已暂停（${queue.size}）" else "排队中（${queue.size}）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (runtime.queuePaused && queue.isNotEmpty()) {
                TextButton(onClick = { model.client.resumeQueue() }) { Text("接着发") }
            }
        }
        queue.forEach { item -> QueuedRow(item) }
    }
}

@Composable
private fun QueuedRow(item: QueuedInput) {
    val model = LocalAppModel.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                item.text.ifBlank { if (item.attachments.isNotEmpty()) "（${item.attachments.size} 个附件）" else "（空）" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
            item.fromLabel?.let {
                Text("来自 $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        IconButton(onClick = { model.client.removeQueued(item.id) }, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Filled.Close, contentDescription = "删掉这条",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    workingDirectory: String?,
    branch: BranchInfo?,
    onSwitchBranch: (child: String, parentKey: String) -> Unit,
    onLongPress: () -> Unit,
) {
    val fromUser = message.role == "user"
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = if (fromUser) 48.dp else 0.dp, end = if (fromUser) 0.dp else 48.dp),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (branch != null && branch.count > 1) {
                BranchSwitcher(branch, onSwitchBranch)
                Spacer(Modifier.width(6.dp))
            }
            message.fromAgent?.let {
                Text(
                    "子 Agent · $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            message.sentBy?.let {
                Text(
                    "助手发送 · 来自 $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp, if (fromUser) 4.dp else 16.dp, if (fromUser) 16.dp else 4.dp, 16.dp))
                .background(
                    if (fromUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            message.blocks.forEach { block -> BlockView(block, workingDirectory) }
            if (message.blocks.none { b -> blockText(b) != null || blockTool(b) != null || blockMedia(b) != null }) {
                Text("（无内容）", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (message.error != null) {
            Text("⚠ ${message.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (message.stoppedByUser) {
            Text("已停止", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        message.stats?.let { stats ->
            if (stats.tokenCount > 0) {
                Text(
                    buildString {
                        append("${kotlin.math.round(stats.tokensPerSecond).toInt()} tok/s · ${stats.tokenCount} tokens")
                        stats.engineName?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 同一条消息有几个分支时左右切（runner.selectBranch） */
@Composable
private fun BranchSwitcher(branch: BranchInfo, onSwitchBranch: (child: String, parentKey: String) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 2.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                val index = (branch.index - 1 + branch.count) % branch.count
                branch.siblings.getOrNull(index)?.let { onSwitchBranch(it, branch.parentKey) }
            },
            modifier = Modifier.size(22.dp),
        ) { Icon(Icons.Filled.ChevronLeft, contentDescription = "上一条分支", modifier = Modifier.size(14.dp)) }
        Text(
            "${branch.index + 1}/${branch.count}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = {
                val index = (branch.index + 1) % branch.count
                branch.siblings.getOrNull(index)?.let { onSwitchBranch(it, branch.parentKey) }
            },
            modifier = Modifier.size(22.dp),
        ) { Icon(Icons.Filled.ChevronRight, contentDescription = "下一条分支", modifier = Modifier.size(14.dp)) }
    }
}

@Composable
fun BlockView(block: MessageBlock, workingDirectory: String? = null) {
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
        ToolCard(tool, workingDirectory)
        return
    }
    val media = blockMedia(block)
    if (media != null) {
        MediaBlockView(media, Modifier.fillMaxWidth())
        return
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
fun ToolCard(tool: ToolEvent, workingDirectory: String? = null) {
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
        // 看图 / 看视频 / 听录音：媒体直接内嵌在卡片里（不用点开）
        ToolMedia(tool, workingDirectory)
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
            if (!tool.preview.isNullOrBlank()) {
                Text(tool.preview!!, style = MaterialTheme.typography.bodySmall)
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
            tool.transcript.forEach { BlockView(it, workingDirectory) }
        }
    }
}

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
