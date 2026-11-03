package com.integemjack.friday.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.graphicsLayer
import com.integemjack.friday.proto.JournalEntry
import com.integemjack.friday.ui.theme.LocalFridayExtra
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 助手页（同桌面版 AssistantView.qml，docs/ASSISTANT.md §1.1）：
 * 样子和对话页一样——时间线按天分隔、从下往上排、跟着底部，往上翻按页加载；
 * 用户的话在右边气泡（标来源）、助手的回复在左边、工具卡片、回执行带「打开」和「批准 / 拒绝」；
 * 底部输入框对助手说话，它在出字时按钮是「打断」。关着时是一页说明 +「打开助手」。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(onOpenSession: (String) -> Unit = {}) {
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val assistant = state.assistant
    val entries = state.assistantEntries.filterNot { it.silent }
    // 时间线倒着排（reverseLayout）：最新那条在 index 0 = 屏幕最下面，往上翻就是往更早翻。
    // 正着排的话内容不够一屏时全堆在顶上，输入框上面空一大块
    val timeline = remember(entries) { entries.asReversed() }
    // 哪几条是一天的开头：按日志原顺序算一次，别每条都 indexOfFirst
    val dayStarts = remember(entries) {
        buildSet {
            entries.forEachIndexed { i, e ->
                if (i == 0 || dayOf(entries[i - 1].at) != dayOf(e.at)) add(e.id)
            }
        }
    }
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    var sayError by remember { mutableStateOf<String?>(null) }

    // 进这页才订助手（assistant.subscribe），离开退订：日志和状态板的事件量不小
    LaunchedEffect(state.connected) {
        if (state.connected) runCatching { model.client.subscribeAssistant() }
    }
    DisposableEffect(Unit) {
        onDispose { model.client.leaveAssistant() }
    }
    // 新内容跟着底部：只有本来就贴着底部时才滚，翻历史时不把用户拽走
    LaunchedEffect(entries.size, assistant?.reactingEntryID) {
        if (entries.isEmpty()) return@LaunchedEffect
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }
    // 翻到顶了（= 倒排列表的最后一个 index）再取一页更早的；
    // 内容没占满一屏时不触发，免得刚进来就空转
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last != null && info.totalItemsCount > info.visibleItemsInfo.size &&
                last.index >= info.totalItemsCount - 2
        }.distinctUntilChanged().collect { atTop ->
            if (atTop) {
                val live = model.client.state.value
                if (live.assistantHasOlder && !live.assistantLoading) model.client.loadOlderAssistant()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Column {
                        Text(
                            assistant?.config?.name?.takeIf { it.isNotBlank() } ?: "助手",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val where = state.conn as? com.integemjack.friday.proto.ConnState.Connected
                        Text(
                            when {
                                !state.connected -> "没连上远程机器"
                                assistant?.enabled == true ->
                                    (where?.server?.hostname ?: "") +
                                        (assistant.brainEngineTitle?.let { " · $it" } ?: "")
                                else -> (where?.server?.hostname ?: "") + " · 还没打开"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                actions = {
                    if (state.connected) {
                        Switch(
                            checked = assistant?.enabled == true,
                            onCheckedChange = { on ->
                                scope.launch {
                                    runCatching { model.client.setAssistantEnabled(on) }
                                        .onFailure { sayError = it.message }
                                }
                            },
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                },
            )
        },
        bottomBar = {
            // 和对话页同一个坑：没键盘时也要让出系统导航条，不然输入框压在导航条下面
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            ) {
                val speaking = assistant?.speaking == true
                val direct = directTarget(input)
                if (direct != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "这条直接发给 #$direct，不经过助手",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp)),
                        placeholder = {
                            Text(
                                if (speaking) "想插一句？（空着点右边是打断）" else "跟助手说点什么…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        enabled = state.connected,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                    )
                    Spacer(Modifier.width(6.dp))
                    if (speaking && input.isBlank()) {
                        FilledIconButton(
                            onClick = { model.client.stopAssistant() },
                            modifier = Modifier.size(44.dp),
                            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = "打断", modifier = Modifier.size(20.dp))
                        }
                    } else {
                        FilledIconButton(
                            enabled = state.connected && input.isNotBlank(),
                            onClick = {
                                val text = input.trim()
                                input = ""
                                scope.launch {
                                    runCatching { model.client.sayToAssistant(text) }
                                        .onFailure { sayError = it.message }
                                }
                            },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.connected -> Centered {
                    EmptyState(
                        icon = Icons.Filled.WifiOff,
                        title = "连上远程 Friday 才有助手",
                        hint = "助手表跑在那台机器上：设置里登录 SChat、选一台连上，这里就能看到它在忙什么、也可以直接跟它说话。",
                    )
                }

                state.assistantLoading && assistant == null -> Centered { CircularProgressIndicator() }

                state.assistantError != null && assistant == null -> Centered {
                    EmptyState(
                        icon = Icons.Filled.ErrorOutline,
                        title = "看不了这台的助手",
                        hint = state.assistantError,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }

                assistant != null && !assistant.enabled -> Centered {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        EmptyState(
                            icon = Icons.Filled.SelfImprovement,
                            title = "${assistant.config.name} 还没打开",
                            hint = "打开以后它会盯着这台机器上各段会话的动静：该通知你的通知你，要确认的来问你，也能在这儿直接吩咐它。",
                        )
                        FilledTonalButton(onClick = {
                            scope.launch {
                                runCatching { model.client.setAssistantEnabled(true) }
                                    .onFailure { sayError = it.message }
                            }
                        }) {
                            Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("打开助手")
                        }
                    }
                }

                else -> Column(Modifier.fillMaxSize()) {
                    // 状态板钉在顶栏下面，不跟着时间线滚（原来它是列表第一项，
                    // 倒排以后会跑到最底下）
                    BoardStrip(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
                        running = state.assistantBoard?.running ?: 0,
                        awaiting = state.assistantBoard?.awaiting ?: 0,
                        channels = state.assistantChannels,
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = true,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(timeline, key = { it.id }) { entry ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (entry.id in dayStarts) DayDivider(entry.at)
                                EntryRow(
                                    entry = entry,
                                    onOpenSession = onOpenSession,
                                    onDecide = { machineID, eventID, approved ->
                                        scope.launch {
                                            model.client.resolveAssistantApproval(machineID, eventID, approved)
                                        }
                                    },
                                )
                            }
                        }
                        // 倒排：这两项 index 最大 = 屏幕最上面，正是它们该在的位置
                        if (state.assistantLoading) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("翻更早的…", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (timeline.isEmpty() && !state.assistantLoading) {
                            item {
                                EmptyState(
                                    icon = Icons.Filled.SelfImprovement,
                                    title = "还没有动静",
                                    hint = "它在等这台机器上的会话有变化，或者等你说点什么。",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (sayError != null) {
        AlertDialog(
            onDismissRequest = { sayError = null },
            title = { Text("没送出去") },
            text = { Text(sayError ?: "") },
            confirmButton = { TextButton(onClick = { sayError = null }) { Text("知道了") } },
        )
    }
}

// MARK: 条目

/** 一条日志：按 kind 分五种样子（用户 / 助手 / 回执 / 工具 / 说明） */
@Composable
private fun EntryRow(
    entry: JournalEntry,
    onOpenSession: (String) -> Unit,
    onDecide: (machineID: String, eventID: String, approved: Boolean) -> Unit,
) {
    when (entry.kind) {
        "user" -> UserRow(entry)
        "assistant" -> AssistantRow(entry)
        "receipt" -> ReceiptRow(entry, onOpenSession, onDecide)
        "tool" -> ToolRow(entry)
        else -> NoticeRow(entry)
    }
}

/** 用户的话：右边气泡，标来源（语音 / 哪个渠道） */
@Composable
private fun UserRow(entry: JournalEntry) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            entry.text,
            Modifier
                .clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 13.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        entry.source?.let { Row(verticalAlignment = Alignment.CenterVertically) { SourceChip(it) } }
    }
}

/** 助手的回复：左边一张浅卡，流式时带尾巴，被打断 / 出错标出来 */
@Composable
private fun AssistantRow(entry: JournalEntry) {
    FridayCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.SelfImprovement, size = 22.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "助手",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (entry.streaming) {
                Text("在说…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            } else if (entry.interrupted) {
                Chip("被打断", color = MaterialTheme.colorScheme.tertiary)
            }
            Text(
                clockOf(entry.at),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Text(entry.text, style = MaterialTheme.typography.bodyMedium)
        entry.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 回执行：左边状态图标，中间那段的动静，右边「打开」和批准 / 拒绝 */
@Composable
private fun ReceiptRow(
    entry: JournalEntry,
    onOpenSession: (String) -> Unit,
    onDecide: (machineID: String, eventID: String, approved: Boolean) -> Unit,
) {
    val info = entry.receipt
    val (icon, tint) = receiptVisual(entry.text, info?.status.orEmpty(), info?.level.orEmpty())
    FridayCard(
        color = if (info?.needsDecision == true) {
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.07f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint = tint, size = 26.dp)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    entry.text.ifBlank { info?.event ?: "动静" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                info?.brief?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                clockOf(entry.at),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (info?.conversationID != null) {
                TextButton(onClick = { onOpenSession(info.conversationID) }) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("打开")
                }
            }
            if (info?.needsDecision == true) {
                OutlinedButton(
                    onClick = { onDecide(info.machineID ?: "local", info.approvalEventID!!, false) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text("拒绝") }
                FilledTonalButton(
                    onClick = { onDecide(info.machineID ?: "local", info.approvalEventID!!, true) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                ) { Text("批准") }
            }
        }
    }
}

/** 工具那一条：默认只一行，点开看参数和结果 */
@Composable
private fun ToolRow(entry: JournalEntry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val tool = entry.tool
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { if (expanded) rotationZ = 90f },
            )
            Spacer(Modifier.width(6.dp))
            Text(
                tool?.name?.takeIf { it.isNotBlank() } ?: entry.text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                clockOf(entry.at),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        if (expanded) {
            tool?.arguments?.takeIf { it.isNotBlank() }?.let {
                Text("参数", style = MaterialTheme.typography.labelSmall)
                MonospaceBlock(it.take(2000), maxLines = 12)
            }
            tool?.result?.takeIf { it.isNotBlank() }?.let {
                Text("结果", style = MaterialTheme.typography.labelSmall)
                MonospaceBlock(it.take(2000), maxLines = 12)
            }
        }
    }
}

/** 框架说明（notice）：居中的一行小字 */
@Composable
private fun NoticeRow(entry: JournalEntry) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Campaign,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            entry.text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: 页面零件

/** 顶上一行：这台几个在跑、几个等你确认、渠道在不在线 */
@Composable
private fun BoardStrip(
    modifier: Modifier = Modifier,
    running: Int,
    awaiting: Int,
    channels: List<com.integemjack.friday.proto.AssistantChannel>,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatChip(leading = { Dot(MaterialTheme.colorScheme.primary, 6.dp) }, text = if (running == 0) "没在跑的会话" else "$running 个在跑")
        if (awaiting > 0) {
            StatChip(leading = { Dot(MaterialTheme.colorScheme.tertiary, 6.dp) }, text = "$awaiting 个等你确认")
        }
        channels.firstOrNull()?.let { c ->
            val online = c.status == "online"
            StatChip(
                leading = { Dot(if (online) LocalFridayExtra.current.success else MaterialTheme.colorScheme.outline, 6.dp) },
                text = "${c.botName.ifBlank { c.title }} ${if (online) "在线" else if (c.status == "off") "没开" else c.statusText}",
            )
        }
    }
}

@Composable
private fun StatChip(leading: @Composable () -> Unit, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        leading()
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceChip(source: String) {
    val label = when {
        source == "voice" -> "语音"
        source == "desktop" -> "桌面"
        source.startsWith("schat") || source.contains("bot") -> "SChat"
        else -> source
    }
    Chip(label, color = MaterialTheme.colorScheme.tertiary)
}

@Composable
private fun DayDivider(at: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            dayOf(at),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** 来源、时间：日志的 at 是 ISO，界面上只到分 */
private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年 MM 月 dd 日")

private fun clockOf(iso: String): String = runCatching {
    clockFormatter.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault("")

private fun dayOf(iso: String): String = runCatching {
    val zoned = Instant.parse(iso).atZone(ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    when (zoned.toLocalDate()) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> dayFormatter.format(zoned)
    }
}.getOrDefault(iso.take(10))

/** 输入以「#短号」开头 = 这条不经大脑、原样转给那个会话（同桌面版 AssistantView） */
private fun directTarget(input: String): String? {
    val match = Regex("^#([0-9a-fA-F]{3,32})(?=[\\s:：,，]|$)").find(input.trim()) ?: return null
    return match.groupValues.getOrNull(1)
}

@Composable
private fun receiptVisual(text: String, status: String, level: String): Pair<ImageVector, Color> {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalFridayExtra.current
    val key = (status + " " + text).lowercase()
    return when {
        key.contains("approval") || key.contains("确认") || key.contains("批准") ->
            Icons.Filled.HourglassEmpty to scheme.tertiary
        key.contains("error") || key.contains("失败") -> Icons.Filled.ErrorOutline to scheme.error
        key.contains("done") || key.contains("完成") || key.contains("finished") ->
            Icons.Filled.CheckCircle to extra.success
        key.contains("running") || key.contains("在跑") || key.contains("生成") ->
            Icons.Filled.PlayCircle to scheme.primary
        level == "warn" -> Icons.Filled.ErrorOutline to scheme.tertiary
        else -> Icons.Filled.Bolt to scheme.onSurfaceVariant
    }
}
