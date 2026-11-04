package com.integemjack.friday.ui

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.runtime.saveable.rememberSaveable
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
    // 每段会话一份滚动位置：侧栏里换一段，新的那段从最底下开始看，不沿用上一段翻到的地方
    val listState = rememberSaveable(chat?.id, saver = LazyListState.Saver) { LazyListState() }
    val clipboard = LocalClipboardManager.current
    var menuMessage by remember { mutableStateOf<ChatMessage?>(null) }
    val openDrawer = LocalOpenDrawer.current

    val tree = remember(chat) { chat?.tree }
    val messages = tree?.path ?: emptyList()
    val branches = tree?.branches ?: emptyMap()
    val runtime = chat?.runtime
    val workingDirectory = chat?.workingDirectory

    // 消息流倒着排（reverseLayout，同助手页）：最新那条在 index 0 = 屏幕最下面。
    // 一打开就停在最底下；最后一条再长（工具卡片、图片后加载）也是贴着底往上长，不会把位置顶跑。
    // 正着排时只能 scrollToItem(最后一条)，那是把最后一条的「顶」对齐屏幕顶：最后一条一长就看不到底
    val timeline = remember(messages) { messages.asReversed() }
    val showDots = runtime?.generating == true && (messages.isEmpty() || messages.last().role != "assistant")
    // 底下冒出新的一条（新消息 / 「思考中…」）时跟到底；本来就贴着底部才跟，往上翻着看的时候不抢滚动条。
    // 最后一条流式变长不用管：贴着底时它自己往上长
    val bottomKey = if (showDots) GENERATING_KEY else messages.lastOrNull()?.id
    LaunchedEffect(listState, bottomKey) {
        if (bottomKey != null && listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }
    val atBottom by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // 只有标题（跟助手页一样）：在不在生成看消息流底下的「思考中…」和输入框的停止键
            HomeTopBar(title = chat?.title?.ifBlank { "（无标题）" } ?: "会话")
        },
        floatingActionButton = {
            if (chat != null && messages.isNotEmpty() && !atBottom) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    // 内容铺到了输入框后面：按钮得浮在输入框上面
                    modifier = Modifier.padding(bottom = LocalDockPadding.current),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "回到最新") }
            }
        },
    ) { padding ->
        if (chat == null) {
            // 会话被删了 / 进程被系统回收后回来：给一句话和侧栏入口，不留一页白
            Box(
                Modifier.fillMaxSize().padding(padding).padding(bottom = LocalDockPadding.current),
                contentAlignment = Alignment.Center,
            ) {
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
            // 消息一直铺到屏幕底（输入框背后透明），底下让出输入框那段；顶上渐隐。
            // 倒着排但 Arrangement 靠上：消息不够一屏时还是从顶上排下来，跟原来一样
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().fadeTopEdge(),
                reverseLayout = true,
                // 左右只留 12dp：智能体的回复不包气泡、铺满整行，一行多放几个字
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = TopFadeHeight,
                    bottom = 12.dp + LocalDockPadding.current,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top),
            ) {
                // 倒排：先写的 index 小 = 在下面。「思考中…」在最底下
                if (showDots) {
                    item(key = GENERATING_KEY) { GeneratingDots() }
                }
                items(timeline, key = { it.id }) { message ->
                    Box {
                        MessageItem(
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
            }
        }
    }
}

/** 消息流里「思考中…」那一项的 key（消息 id 是服务端给的，不会撞上这个） */
private const val GENERATING_KEY = "friday.ui:generating"

// MARK: 输入框

/**
 * 所有会话的草稿（AppRoot 拿着，切页不丢；按会话分开，换到别的会话不会把这段的草稿带过去）。
 * 给了 [store] 时字也存到本地：App 关掉再开、换到这段会话，打了一半的话还在
 */
@Stable
class ChatDrafts(private val store: com.integemjack.friday.data.DraftStore? = null) {
    private val drafts = mutableMapOf<String, ComposerDraft>()
    fun of(conversationId: String): ComposerDraft = drafts.getOrPut(conversationId) {
        if (store == null || conversationId.isEmpty()) {
            ComposerDraft()
        } else {
            val key = com.integemjack.friday.data.DraftStore.chat(conversationId)
            ComposerDraft(store.load(key)) { store.save(key, it) }
        }
    }
}

/**
 * 会话页的输入框：[Composer]，左下角「＋」点出底部面板（附件、模型、任务模式、思考模式、工作目录），
 * 上面挂排队、重试、附件三样。框里空着时右边默认是语音键。
 *
 * 在跑的时候：红色停止一直在；框里有话时旁边多一颗「补充给智能体」——不打断它，做完当前这一步就把这句交给它
 * （runner.submit 不带 whenBusy，服务端 == 输入框；同桌面端 / Qt 的「补充给智能体」双键），
 * 补进去的那条消息在会话里标「工作中补充」。
 *
 * 模型 / 任务模式 / 思考模式 / 工作目录改的都是这段对话的（runner.setEngine / setTeamMode / setThinking /
 * setWorkingDirectory；工作中换的下一轮生效），换模型同时记成新对话的默认（同桌面端）。
 * 模型列表里每个下面写着那家的余额 / 套餐用量（services.usage，打开那页时查）。
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
    val voice = rememberVoiceInput(draft)
    var sendError by remember { mutableStateOf<Pair<String, String>?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }

    val uploading = draft.uploading
    val readyPaths = draft.readyPaths
    val generating = runtime?.generating == true
    val empty = draft.isEmpty
    val id = chat?.id

    // 这段对话现在的模型 / 任务模式 / 思考档位 / 工作目录
    val summary = state.summaries.firstOrNull { it.id == id }
    val engines = state.usableEngines
    val engineID = summary?.engineID?.takeIf { it.isNotEmpty() } ?: runtime?.engineID ?: state.defaultEngineID
    val engine = engines.firstOrNull { it.id == engineID }
    val teamMode = normalizeTeamMode(summary?.teamMode ?: state.defaultTeamMode)
    val levels = runtime?.thinkingLevels ?: ALL_THINKING_LEVELS
    val thinking = effectiveThinking(summary?.thinking ?: runtime?.thinkingLevel, levels)
    val directory = chat?.workingDirectory?.takeIf { it.isNotBlank() }
    val server = (state.conn as? com.integemjack.friday.proto.ConnState.Connected)?.server

    fun change(what: String, block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { sendError = "没换成$what" to (it.message ?: "") } }
    }

    val modelLabel = engine?.label ?: "默认模型"
    val teamLabel = TEAM_MODES.firstOrNull { it.raw == teamMode }?.short ?: teamMode
    val thinkingLabel = when {
        levels.isEmpty() -> "不能调"
        thinking == null -> "默认"
        else -> thinkingTitle(thinking, levels)
    }

    Composer(
        value = draft.input,
        onValueChange = { draft.input = it },
        placeholder = when {
            chat == null -> "先在侧栏里选一段会话"
            !state.connected -> "离线：连上远程机器以后才能发（打的字会留着）"
            uploading -> "附件上传中…"
            generating -> "补充给智能体：做完当前这一步就交给它"
            else -> "发消息…"
        },
        // 离线看的是存下来的内容：不能发、不能改设置；打了一半的字还在
        enabled = chat != null && state.connected,
        canSend = !empty && !uploading,
        busy = uploading && !generating,
        onStop = if (generating) ({ model.client.stop() }) else null,
        stopDescription = "停止",
        runningSend = RunningSend.Supplement,
        voice = voice,
        onMore = { sheetOpen = true },
        summary = if (chat == null) null else "$modelLabel · $teamLabel · 思考 $thinkingLabel",
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
            AttachmentChips(draft.attachments.toList(), onRemove = { attachment -> draft.remove(attachment) })
        },
    )

    if (sheetOpen && id != null) {
        val menu = ComposerMenu(
            quick = listOf(
                QuickAction(Icons.Filled.Image, "图片 / 视频", onClick = pickers.pickMedia),
                QuickAction(Icons.Filled.Description, "文件", onClick = pickers.pickFile),
            ),
            rows = listOf(
                ChoiceRow(
                    icon = Icons.Filled.AutoAwesome,
                    title = "模型",
                    value = modelLabel,
                    note = engine?.let { state.usage[it.usageKey]?.line },
                    footnote = "只换这段对话的，同时记成新对话的默认；正在跑的下一轮生效",
                    choices = engines.map { e ->
                        val usage = state.usage[e.usageKey]
                        MenuChoice(
                            key = e.id,
                            title = e.label,
                            detail = e.title.takeIf { it.isNotBlank() && it != e.label },
                            note = usage?.line,
                            loading = usage?.loading == true,
                            selected = e.id == engine?.id,
                        )
                    },
                    emptyText = "这台机器上还没有能用的模型",
                    onOpen = { model.client.refreshUsage() },
                    onChoose = { picked -> change("模型") { model.client.setEngine(id, picked) } },
                ),
                ChoiceRow(
                    icon = Icons.Filled.AccountTree,
                    title = "任务模式",
                    value = teamLabel,
                    footnote = "只换这段对话的，同时记成新对话的默认",
                    choices = TEAM_MODES.map { MenuChoice(it.raw, it.title, it.detail, selected = it.raw == teamMode) },
                    onChoose = { mode -> change("任务模式") { model.client.setTeamMode(id, mode) } },
                ),
                ChoiceRow(
                    icon = Icons.Filled.Psychology,
                    title = "思考模式",
                    value = thinkingLabel,
                    enabled = levels.isNotEmpty(),
                    choices = levels.map { MenuChoice(it, thinkingTitle(it, levels), selected = it == thinking) },
                    emptyText = "这个模型不能调",
                    onChoose = { level -> change("思考模式") { model.client.setThinking(id, level) } },
                ),
                DirectoryRow(
                    icon = Icons.Filled.Folder,
                    title = "工作目录",
                    value = directory?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\') ?: "默认",
                    current = directory,
                    recent = state.directories,
                    defaultLabel = server?.defaultWorkingDirectory?.let { "这台的默认：$it" } ?: "这台的默认目录",
                    onOpen = { model.client.refreshDirectories() },
                    onChoose = { path -> change("工作目录") { model.client.setWorkingDirectory(id, path) } },
                    onForget = { path -> scope.launch { model.client.forgetDirectory(path) } },
                    list = { path -> model.client.listDirectory(path) },
                ),
            ),
        )
        ComposerSheet(menu, onDismiss = { sheetOpen = false })
    }

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
            // 输入框背后透明：先垫页面底色，淡色才不会透出后面滚动的字
            .background(MaterialTheme.colorScheme.background)
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
                when {
                    runtime.queuePaused -> "排队已暂停（${queue.size}）"
                    runtime.generating -> "做完这一步就交给它（${queue.size}）"
                    else -> "排队中（${queue.size}）"
                },
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

/**
 * 一条消息。你说的话是右边一个小气泡；智能体的回复**不包气泡**，直接铺满整行（同桌面端），
 * 字、思考、工具一条条往下排，一屏能多看几行。长按整条弹菜单（复制 / 重试）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(
    message: ChatMessage,
    workingDirectory: String?,
    branch: BranchInfo?,
    onSwitchBranch: (child: String, parentKey: String) -> Unit,
    onLongPress: () -> Unit,
) {
    val fromUser = message.role == "user"
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = if (fromUser) 48.dp else 0.dp),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
    ) {
        val hasBranches = branch != null && branch.count > 1
        if (hasBranches || message.fromAgent != null || message.sentBy != null || message.interjected) {
            Row(
                Modifier.padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (hasBranches) BranchSwitcher(branch!!, onSwitchBranch)
                message.fromAgent?.let { Chip("子 Agent · $it", color = scheme.tertiary) }
                message.sentBy?.let { Chip("来自 $it", color = scheme.tertiary) }
                // 智能体工作时补充进去的：做完当时那一步就交给了它（同桌面端 MessageItem「· 工作中补充」）
                if (message.interjected) Chip("工作中补充", color = scheme.primary)
            }
        }
        if (fromUser) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp))
                    .background(scheme.primaryContainer)
                    .combinedClickable(onClick = {}, onLongClick = onLongPress)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { MessageBlocks(message, workingDirectory) }
        } else {
            // 没有气泡也就没有按下去的那块底色：长按不画水波纹，弹出菜单就是反馈
            Column(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = onLongPress,
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { MessageBlocks(message, workingDirectory) }
        }
        if (message.error != null) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = scheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(message.error, style = MaterialTheme.typography.bodySmall, color = scheme.error)
            }
        }
        // 「已停止」和用量并成一行小字
        val stats = message.stats?.takeIf { it.tokenCount > 0 }
        if (message.stoppedByUser || stats != null) {
            Text(
                listOfNotNull(
                    "已停止".takeIf { message.stoppedByUser },
                    stats?.let { s ->
                        buildString {
                            append("${kotlin.math.round(s.tokensPerSecond).toInt()} tok/s · ${s.tokenCount} tokens")
                            s.engineName?.let { append(" · $it") }
                        }
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MessageBlocks(message: ChatMessage, workingDirectory: String?) {
    message.blocks.forEach { block -> BlockView(block, workingDirectory) }
    if (message.blocks.none { b -> blockText(b) != null || blockTool(b) != null || blockMedia(b) != null }) {
        Text(
            "（无内容）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/** 思考：默认只一行「思考 …」（没有底色），点开在下面露出内容，左边一道竖线 */
@Composable
private fun ThinkingBlock(content: String) {
    var expanded by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Psychology,
                contentDescription = null,
                tint = muted.copy(alpha = 0.8f),
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(LineIndent - 14.dp))
            Text(
                if (expanded) "思考 ▾" else "思考 …",
                style = MaterialTheme.typography.labelMedium,
                color = muted,
            )
        }
        if (expanded && content.isNotBlank()) {
            Text(
                content,
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier
                    .padding(start = 6.dp, top = 4.dp)
                    .leftRule(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/**
 * 工具：默认一行（状态图标 + 名字；没成 / 等确认才在右边写一个字样），下面一行结果的第一行。
 * 不包卡片，跟正文靠颜色分开；点一下展开参数、结果和子 Agent 的运行记录
 */
@Composable
fun ToolCard(tool: ToolEvent, workingDirectory: String? = null) {
    var expanded by remember { mutableStateOf(false) }
    val extra = com.integemjack.friday.ui.theme.LocalFridayExtra.current
    val scheme = MaterialTheme.colorScheme
    // 成功的不写「完成」：绿勾就够了；在跑的转圈。只有要你注意的才多一个字样
    val (tint, label) = when (tool.status) {
        "awaitingApproval" -> scheme.tertiary to "待确认"
        "running" -> scheme.primary to null
        "succeeded" -> extra.success to null
        "failed" -> scheme.error to "失败"
        "denied" -> scheme.onSurfaceVariant to "已拒绝"
        else -> scheme.outline to tool.status.ifBlank { null }
    }
    val icon = when (tool.status) {
        "succeeded" -> Icons.Filled.CheckCircle
        "failed" -> Icons.Filled.ErrorOutline
        "awaitingApproval" -> Icons.Filled.Queue
        else -> Icons.Filled.Terminal
    }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(LineIndent)) {
                    if (tool.status == "running") {
                        CircularProgressIndicator(Modifier.padding(1.dp).size(12.dp), strokeWidth = 1.5.dp)
                    } else {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                    }
                }
                Text(
                    tool.title?.ifBlank { tool.name } ?: tool.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (label != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
            if (!expanded) {
                val brief = tool.result.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                if (!brief.isNullOrBlank()) {
                    Text(
                        brief,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = LineIndent),
                    )
                }
            }
        }
        // 看图 / 看视频 / 听录音：媒体直接内嵌（不用点开）
        ToolMedia(tool, workingDirectory)
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, top = 4.dp)
                    .leftRule(scheme.outlineVariant),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
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
