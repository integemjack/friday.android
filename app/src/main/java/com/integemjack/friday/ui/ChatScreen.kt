package com.integemjack.friday.ui

import android.widget.Toast
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * 会话页：和助手页一样是**顶级页面**——左上角是侧栏入口（不是返回箭头），手指往右滑拉出侧栏，
 * 在侧栏里点别的会话直接换过去。
 *
 * 这里只画顶栏 + 消息流；底部输入框是 [ChatComposer]，由 [HomeScaffold] 放进 dock，
 * 跟助手页用的是同一个 [Composer]。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen() {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val state by model.client.stateFlow.collectAsState()
    val chat = state.chat
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var menuMessage by remember { mutableStateOf<ChatMessage?>(null) }
    val openDrawer = LocalOpenDrawer.current

    val tree = remember(chat) { chat?.tree }
    val messages = tree?.path ?: emptyList()
    val branches = tree?.branches ?: emptyMap()
    val runtime = chat?.runtime
    val workingDirectory = chat?.workingDirectory

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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val generating = runtime?.generating == true
            val place = workingDirectory?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            HomeTopBar(
                title = chat?.title?.ifBlank { "（无标题）" } ?: "会话",
                subtitle = when {
                    chat == null -> "没有打开的会话"
                    generating -> "生成中…"
                    else -> place ?: "已连上"
                },
                subtitleColor = if (generating) MaterialTheme.colorScheme.primary else null,
                dotColor = if (generating) MaterialTheme.colorScheme.primary else null,
            )
        },
        floatingActionButton = {
            if (chat != null && messages.isNotEmpty() && !atBottom) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(messages.size - 1) } },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "回到最新") }
            }
        },
    ) { padding ->
        if (chat == null) {
            // 会话被删了 / 进程被系统回收后回来：给一句话和侧栏入口，不留一页白
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        title = "没有打开的会话",
                        hint = "从侧栏里挑一段会话，或者在那儿新建一段。",
                    )
                    if (openDrawer != null) {
                        FilledTonalButton(onClick = openDrawer) { Text("打开侧栏") }
                    }
                }
            }
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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

// MARK: 输入框

/** 所有会话的草稿（AppRoot 拿着，切页不丢；按会话分开，换到别的会话不会把这段的草稿带过去） */
@Stable
class ChatDrafts {
    private val drafts = mutableMapOf<String, ComposerDraft>()
    fun of(conversationId: String): ComposerDraft = drafts.getOrPut(conversationId) { ComposerDraft() }
}

/**
 * 会话页的输入框：[Composer]，工具行是 ＋附件、模型、任务模式、思考模式（跟助手页同一套），
 * 上面挂排队、重试、附件三样。
 *
 * 跟助手页同一套规则：生成中且输入框空着，右边是「停止」；打了字就是「发送」（远程那边排队，
 * 上面的排队条会把它列出来）。
 *
 * 模型 / 任务模式 / 思考模式改的都是这段对话的（runner.setEngine / setTeamMode / setThinking；
 * 工作中换的下一轮生效），换模型同时记成新对话的默认（同桌面端）。
 */
@Composable
fun ChatComposer(drafts: ChatDrafts) {
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    val chat = state.chat
    val runtime = chat?.runtime
    val scope = rememberCoroutineScope()
    val draft = drafts.of(chat?.id ?: "")
    val pickers = rememberAttachmentPickers(draft)
    var sendError by remember { mutableStateOf<Pair<String, String>?>(null) }

    val uploading = draft.uploading
    val readyPaths = draft.readyPaths
    val generating = runtime?.generating == true
    val empty = draft.isEmpty

    // 这段对话现在的模型 / 任务模式 / 思考档位
    val summary = state.summaries.firstOrNull { it.id == chat?.id }
    val engines = state.usableEngines
    val engineID = summary?.engineID?.takeIf { it.isNotEmpty() } ?: runtime?.engineID ?: state.defaultEngineID
    val engine = engines.firstOrNull { it.id == engineID }
    val teamMode = summary?.teamMode ?: state.defaultTeamMode
    val levels = runtime?.thinkingLevels ?: ALL_THINKING_LEVELS
    val thinking = summary?.thinking ?: runtime?.thinkingLevel

    fun change(what: String, block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { sendError = "没换成$what" to (it.message ?: "") } }
    }

    Composer(
        value = draft.input,
        onValueChange = { draft.input = it },
        placeholder = when {
            chat == null -> "先在侧栏里选一段会话"
            uploading -> "附件上传中…"
            generating -> "接着说会排队（空着点右边是停止）"
            else -> "发消息…"
        },
        enabled = chat != null,
        canSend = !empty && !uploading,
        busy = uploading && !generating,
        onStop = if (generating && empty) ({ model.client.stop() }) else null,
        stopDescription = "停止",
        onSend = {
            val text = draft.input.trim()
            if (text.isNotEmpty() || readyPaths.isNotEmpty()) {
                val target = draft
                target.clear()
                scope.launch {
                    runCatching { model.client.submit(text, readyPaths) }
                        .onFailure {
                            // 没送出去：把话放回去（附件已经在远程了，路径丢了也只能重选）
                            if (target.input.isEmpty()) target.input = text
                            sendError = "没送出去" to (it.message ?: "发送失败")
                        }
                }
            }
        },
        tools = {
            val id = chat?.id
            AttachmentButton(
                enabled = chat != null,
                onPickMedia = pickers.pickMedia,
                onPickFile = pickers.pickFile,
            )
            ModelPicker(
                engines = engines,
                selectedID = engine?.id,
                label = engine?.label ?: "默认模型",
                enabled = id != null,
                footnote = "只换这段对话的，同时记成新对话的默认；正在跑的下一轮生效",
                onChoose = { picked -> if (id != null) change("模型") { model.client.setEngine(id, picked) } },
                modifier = Modifier.weight(1f, fill = false),
            )
            TeamModePicker(
                current = normalizeTeamMode(teamMode),
                enabled = id != null,
                footnote = "只换这段对话的，同时记成新对话的默认",
                onChoose = { mode -> if (id != null) change("任务模式") { model.client.setTeamMode(id, mode) } },
            )
            ThinkingPicker(
                levels = levels,
                current = thinking,
                enabled = id != null,
                onChoose = { level -> if (id != null) change("思考模式") { model.client.setThinking(id, level) } },
            )
        },
        above = {
            runtime?.let { rt -> QueueBar(rt) }
            if (runtime?.canRetry == true && !generating) {
                ComposerNotice(
                    icon = Icons.Filled.ErrorOutline,
                    text = "上一轮没成",
                    tint = MaterialTheme.colorScheme.error,
                    trailing = {
                        TextButton(
                            onClick = { model.client.retry() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重试")
                        }
                    },
                )
            }
            AttachmentChips(draft.attachments.toList(), onRemove = { id -> draft.remove(id) })
        },
    )

    sendError?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = { sendError = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { sendError = null }) { Text("知道了") } },
        )
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
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.34f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Queue,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                if (runtime.queuePaused) "排队已暂停（${queue.size}）" else "排队中（${queue.size}）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            if (runtime.queuePaused && queue.isNotEmpty()) {
                TextButton(
                    onClick = { model.client.resumeQueue() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) { Text("接着发", style = MaterialTheme.typography.labelLarge) }
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
    val extra = com.integemjack.friday.ui.theme.LocalFridayExtra.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = if (fromUser) 56.dp else 0.dp, end = if (fromUser) 0.dp else 0.dp),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (branch != null && branch.count > 1) {
                BranchSwitcher(branch, onSwitchBranch)
                Spacer(Modifier.width(6.dp))
            }
            message.fromAgent?.let {
                Chip("子 Agent · $it", color = MaterialTheme.colorScheme.tertiary)
            }
            message.sentBy?.let {
                Chip("来自 $it", color = MaterialTheme.colorScheme.tertiary)
            }
        }
        if (branch != null && branch.count > 1) Spacer(Modifier.height(4.dp))
        Column(
            Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomEnd = if (fromUser) 6.dp else 20.dp,
                        bottomStart = if (fromUser) 20.dp else 6.dp,
                    )
                )
                .background(
                    if (fromUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            message.blocks.forEach { block -> BlockView(block, workingDirectory) }
            if (message.blocks.none { b -> blockText(b) != null || blockTool(b) != null || blockMedia(b) != null }) {
                Text("（无内容）", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (message.error != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    message.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (message.stoppedByUser) {
            Text(
                "已停止",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        message.stats?.let { stats ->
            if (stats.tokenCount > 0) {
                Row(
                    Modifier.padding(top = 5.dp, start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = extra.success.copy(alpha = 0.8f),
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
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
}

/** 同一条消息有几个分支时左右切（runner.selectBranch） */
@Composable
private fun BranchSwitcher(branch: BranchInfo, onSwitchBranch: (child: String, parentKey: String) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                val index = (branch.index - 1 + branch.count) % branch.count
                branch.siblings.getOrNull(index)?.let { onSwitchBranch(it, branch.parentKey) }
            },
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = "上一条分支",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "下一条分支",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun BlockView(block: MessageBlock, workingDirectory: String? = null) {
    val text = blockText(block)
    if (text != null) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
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
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Psychology,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (expanded) "思考 ▾" else "思考 …",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded && content.isNotBlank()) {
            Text(
                content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ToolCard(tool: ToolEvent, workingDirectory: String? = null) {
    var expanded by remember { mutableStateOf(false) }
    val extra = com.integemjack.friday.ui.theme.LocalFridayExtra.current
    val (tint, label) = when (tool.status) {
        "awaitingApproval" -> MaterialTheme.colorScheme.tertiary to "待确认"
        "running" -> MaterialTheme.colorScheme.primary to "运行中"
        "succeeded" -> extra.success to "完成"
        "failed" -> MaterialTheme.colorScheme.error to "失败"
        "denied" -> MaterialTheme.colorScheme.onSurfaceVariant to "已拒绝"
        else -> MaterialTheme.colorScheme.outline to tool.status
    }
    val icon = when (tool.status) {
        "succeeded" -> Icons.Filled.CheckCircle
        "failed" -> Icons.Filled.ErrorOutline
        "awaitingApproval" -> Icons.Filled.Queue
        else -> Icons.Filled.Terminal
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f))
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                tool.title?.ifBlank { tool.name } ?: tool.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (tool.status == "running") {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            Chip(label, color = tint)
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
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            if (!tool.preview.isNullOrBlank()) {
                Text(tool.preview!!, style = MaterialTheme.typography.bodySmall)
            }
            if (tool.arguments.isNotBlank() && tool.arguments != "{}") {
                MonospaceBlock(tool.arguments.take(2000))
            }
            if (tool.result.isNotBlank()) {
                MonospaceBlock(tool.result.take(4000))
            }
            // 子 Agent 运行记录（递归一层）
            tool.transcript.forEach { BlockView(it, workingDirectory) }
        }
    }
}

@Composable
private fun GeneratingDots() {
    Row(
        Modifier.padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            "思考中…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
