package com.integemjack.friday.ui

import androidx.compose.runtime.key
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * 用户的话在右边小气泡（标来源）；助手的回复、回执、工具都不包卡片，一行行铺满（一屏多看几条），
 * 回执整行点进那段会话、要批准的给「批准 / 拒绝」；
 * 底部输入框对助手说话，它在出字时按钮是「打断」。关着时是一页说明 +「打开助手」。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    onOpenSession: (String) -> Unit = {},
) {
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val assistant = state.assistant
    val entries = state.assistantEntries.filterNot { it.silent }
    // 哪几条是一天的开头：按日志原顺序算一次，别每条都 indexOfFirst
    val dayStarts = remember(entries) {
        buildSet {
            entries.forEachIndexed { i, e ->
                if (i == 0 || dayOf(entries[i - 1].at) != dayOf(e.at)) add(e.id)
            }
        }
    }
    // 连着的工具调用收成一行（一天的开头隔开）
    val timeline = remember(entries, dayStarts) { groupJournal(entries, dayStarts) }
    // 时间线从上往下排（同会话页）：点开工具时那一行留在原地往下长，不再一跳一跳。
    // 一打开就停在最底下（最后一项、偏移给到很大）；日志第一次到手时重建一份，第一帧就在底下
    val listState = rememberSaveable(timeline.isEmpty(), saver = LazyListState.Saver) {
        LazyListState((timeline.size - 1).coerceAtLeast(0), LIST_END_OFFSET)
    }
    // 贴底跟随：在最底下时来了新的、回复流式变长就跟到底；往上翻了、点开了什么，就不跟（FollowBottom.kt）
    val follow = rememberFollowBottom(listState, Unit)
    var sayError by remember { mutableStateOf<String?>(null) }

    // 进这页才订助手（assistant.subscribe），离开退订：日志和状态板的事件量不小
    LaunchedEffect(state.connected) {
        if (state.connected) runCatching { model.client.subscribeAssistant() }
    }
    DisposableEffect(Unit) {
        onDispose { model.client.leaveAssistant() }
    }
    // 翻到顶了再取一页更早的（插在上面，列表按 key 留在原处，不跳）；
    // 内容没占满一屏时不触发，免得刚进来就空转
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()
            first != null && info.totalItemsCount > info.visibleItemsInfo.size && first.index <= 1
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
            // 和会话页同一条顶栏（左上角侧栏入口 + 标题），两页切来切去不跳。
            // 只有标题：连没连上看侧栏顶上，开关助手在设置里
            HomeTopBar(title = assistant?.config?.name?.takeIf { it.isNotBlank() } ?: "助手")
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                // 没连上、也没有存下来的日志：才说「要先连上」；有的话照样摆出来（顶上标离线）
                !state.connected && timeline.isEmpty() -> Centered {
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
                    // 倒排以后会跑到最底下）；离线时换成一条「显示的是哪会儿的」
                    if (state.connected) {
                        BoardStrip(
                            Modifier.padding(horizontal = 12.dp),
                            running = state.assistantBoard?.running ?: 0,
                            awaiting = state.assistantBoard?.awaiting ?: 0,
                            channels = state.assistantChannels,
                        )
                    } else {
                        OfflineStrip(
                            Modifier.padding(horizontal = 12.dp),
                            connecting = state.conn is com.integemjack.friday.proto.ConnState.Connecting,
                            cachedAt = state.cachedAt,
                        )
                    }
                    // 列表一直铺到屏幕底（输入框背后透明），底下让出输入框那段；顶上渐隐
                    CompositionLocalProvider(LocalFollowBottom provides follow) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().fadeTopEdge(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = TopFadeHeight,
                            bottom = 8.dp + LocalDockPadding.current,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 最上面：翻更早的、还没有动静
                        if (state.assistantLoading) {
                            item(key = "friday.ui:older") {
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
                            item(key = "friday.ui:empty") {
                                EmptyState(
                                    icon = Icons.Filled.SelfImprovement,
                                    title = "还没有动静",
                                    hint = "它在等这台机器上的会话有变化，或者等你说点什么。",
                                )
                            }
                        }
                        items(timeline, key = { it.key }) { item ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (item.first.id in dayStarts) DayDivider(item.first.at)
                                when (item) {
                                    is JournalItem.Tools -> ToolGroupRow(item)
                                    is JournalItem.Single -> EntryRow(
                                        entry = item.entry,
                                        onOpenSession = onOpenSession,
                                        onDecide = { machineID, eventID, approved ->
                                            scope.launch {
                                                model.client.resolveAssistantApproval(machineID, eventID, approved)
                                            }
                                        },
                                    )
                                }
                            }
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

/** 用户的话：右边一个小气泡，下面一行小字标来源（语音 / 哪个渠道） */
@Composable
private fun UserRow(entry: JournalEntry) {
    Column(
        Modifier.fillMaxWidth().padding(start = 48.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            entry.text,
            Modifier
                .clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        entry.source?.let {
            Text(
                sourceLabel(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

/**
 * 助手的回复：不包卡片、不要「助手」那行抬头，字直接铺满整行（同会话页的智能体回复），
 * 下面一行小字是时间和「在说…」/「被打断」
 */
@Composable
private fun AssistantRow(entry: JournalEntry) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (entry.text.isNotBlank()) Text(entry.text, style = MaterialTheme.typography.bodyMedium)
        entry.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                clockOf(entry.at),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            val status = when {
                entry.streaming -> "在说…" to MaterialTheme.colorScheme.primary
                entry.interrupted -> "被打断" to MaterialTheme.colorScheme.tertiary
                else -> null
            }
            status?.let { (text, color) ->
                Spacer(Modifier.width(6.dp))
                Text(text, style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
    }
}

/**
 * 回执（某段会话的动静）：小图标 + 那段的动静 + 时间，不包卡片；能打开的整行点进去（右边一个 ›）。
 * 要你批准的才垫一层淡底、下面给「拒绝 / 批准」
 */
@Composable
private fun ReceiptRow(
    entry: JournalEntry,
    onOpenSession: (String) -> Unit,
    onDecide: (machineID: String, eventID: String, approved: Boolean) -> Unit,
) {
    val info = entry.receipt
    val (icon, tint) = receiptVisual(entry.text, info?.status.orEmpty(), info?.level.orEmpty())
    val needsDecision = info?.needsDecision == true
    val conversation = info?.conversationID
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (needsDecision) Modifier.background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f))
                else Modifier
            )
            .then(
                if (conversation != null) Modifier.clickable(onClickLabel = "打开这段会话") { onOpenSession(conversation) }
                else Modifier
            )
            .padding(horizontal = if (needsDecision) 10.dp else 0.dp, vertical = if (needsDecision) 8.dp else 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.width(LineIndent).padding(top = 4.dp)) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    entry.text.ifBlank { info?.event ?: "动静" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                info?.brief?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                clockOf(entry.at),
                style = MaterialTheme.typography.labelSmall,
                color = muted.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
            if (conversation != null) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = muted.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 2.dp).size(18.dp),
                )
            }
        }
        if (needsDecision) {
            val machineID = info?.machineID ?: "local"
            val eventID = info?.approvalEventID.orEmpty()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(
                    onClick = { onDecide(machineID, eventID, false) },
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) { Text("拒绝") }
                FilledTonalButton(
                    onClick = { onDecide(machineID, eventID, true) },
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) { Text("批准") }
            }
        }
    }
}

/**
 * 连着的几条工具调用收成一行：「› search_memory ×2、say   23:41」（时间是最后一条的）；
 * 点开一行一条，那一行留在原地、往下长
 */
@Composable
private fun ToolGroupRow(group: JournalItem.Tools) {
    var expanded by remember(group.key) { mutableStateOf(false) }
    val holdPosition = rememberHoldPosition()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable {
                    holdPosition()
                    expanded = !expanded
                }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(LineIndent)) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = muted,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { if (expanded) rotationZ = 90f },
                )
            }
            Text(
                group.kinds,
                style = MaterialTheme.typography.labelLarge,
                color = muted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                clockOf(group.entries.last().at),
                style = MaterialTheme.typography.labelSmall,
                color = muted.copy(alpha = 0.7f),
            )
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp)
                    .leftRule(MaterialTheme.colorScheme.outlineVariant),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                group.entries.forEach { entry -> key(entry.id) { ToolRow(entry) } }
            }
        }
    }
}

/** 工具那一条：默认只一行（› + 名字 + 时间，不包卡片），点开看参数和结果；那一行留在原地、往下长 */
@Composable
private fun ToolRow(entry: JournalEntry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val holdPosition = rememberHoldPosition()
    val tool = entry.tool
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                holdPosition()
                expanded = !expanded
            }
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(LineIndent)) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { if (expanded) rotationZ = 90f },
                )
            }
            Text(
                tool?.name?.takeIf { it.isNotBlank() } ?: entry.text,
                style = MaterialTheme.typography.labelLarge,
                color = muted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                clockOf(entry.at),
                style = MaterialTheme.typography.labelSmall,
                color = muted.copy(alpha = 0.7f),
            )
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp)
                    .leftRule(MaterialTheme.colorScheme.outlineVariant),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 参数不贴 JSON：跑命令的「$ 命令」，别的「键: 值」一行一个
                tool?.arguments?.let { com.integemjack.friday.proto.toolArgumentsText(it) }?.let {
                    Text("参数", style = MaterialTheme.typography.labelSmall, color = muted)
                    MonospaceBlock(it.take(2000), maxLines = 12)
                }
                tool?.result?.takeIf { it.isNotBlank() }?.let {
                    Text("结果", style = MaterialTheme.typography.labelSmall, color = muted)
                    MonospaceBlock(it.take(2000), maxLines = 12)
                }
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
            .padding(vertical = 3.dp),
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

/** 离线时顶上那条：在连 / 断着，下面是哪会儿存下来的 */
@Composable
private fun OfflineStrip(modifier: Modifier = Modifier, connecting: Boolean, cachedAt: Long?) {
    val time = cachedAt?.let { clockFormatter.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (connecting) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        } else {
            Icon(Icons.Filled.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(7.dp))
        Text(
            (if (connecting) "正在连接…" else "离线") + (time?.let { " · 下面是 $it 存下来的，连上后自动换成最新的" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
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

/** 用户那句话是从哪儿来的 */
private fun sourceLabel(source: String): String = when {
    source == "voice" -> "语音"
    source == "desktop" -> "桌面"
    source.startsWith("schat") || source.contains("bot") -> "SChat"
    else -> source
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

/** 居中摆一块（在输入框上面那段里居中，不是整屏） */
@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(bottom = LocalDockPadding.current),
        contentAlignment = Alignment.Center,
    ) { content() }
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

// MARK: 输入框：跟助手说话

/**
 * 助手页的输入框：跟会话页是**同一个** [Composer]（同样的卡片、左下角「＋」、默认的语音键、
 * 同样的发送 / 停止），由 [HomeScaffold] 放进 dock，所以这里不画背景、也不自己算 inset。
 *
 * 「＋」面板里：图片 / 视频、文件、**位置**（定一次位，输入框上面出一条「位置：…」，跟这句话一起发；
 * 协议里没有位置字段，就写成一段话：地址、WGS-84 坐标、精度、地图链接，见 [SharedLocation]）、
 * **视频通话**（直接打给这台的助手，[com.integemjack.friday.call.AssistantCall]），
 * 下面三行改的是助手这边对应的设置：
 * - 模型：助手下次开会话、往会话里插话用的（AssistantConfig.sessionEngineID，ASSISTANT.md §16.10）；
 *   「跟新对话一样」= 清掉，不动新对话的默认；每个模型下面写着那家的余额 / 用量
 * - 任务模式：这台新对话的默认（settings.update teamMode），助手新开的会话按它
 * - 思考模式：助手大脑开不开深度思考（AssistantConfig.brainThinking，只有开 / 关）
 *
 * 附件先 file.upload 传到那台，再带着服务端路径 assistant.say（REMOTE.md §6「助手」）。
 * 助手在出字时：红色「打断」一直在；框里有话时旁边多一颗「发送」（插一句：打断它、带上这句重来）。
 * 草稿由调用方（AppRoot）拿着：切到别的页再回来，打了一半的话和附件还在。
 */
@Composable
fun AssistantComposer(draft: ComposerDraft, onVideoCall: () -> Unit = {}) {
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val pickers = rememberAttachmentPickers(draft)
    val voice = rememberVoiceInput(draft)
    val shareLocation = rememberLocationShare(draft)
    var sayError by remember { mutableStateOf<Pair<String, String>?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    // 助手名下的定时任务（助手那里 /loop 建的）：输入框上面一行，点开面板
    val background = assistantBackground(state)
    var backgroundOpen by remember { mutableStateOf(false) }
    val assistant = state.assistant
    val speaking = assistant?.speaking == true
    val direct = directTarget(draft.input)
    val uploading = draft.uploading
    val empty = draft.isEmpty
    val usable = state.connected && assistant != null

    // 助手能选的模型：这台的引擎列表（旧服务端 hello 不带时退回助手自己给的那份）
    val engines = state.usableEngines.ifEmpty {
        assistant?.engines.orEmpty().map { com.integemjack.friday.proto.EngineInfo(it.id, it.title, it.model) }
    }
    val chosenID = assistant?.config?.sessionEngineID?.takeIf { it.isNotEmpty() }
    val chosen = engines.firstOrNull { it.id == chosenID }
    val defaultEngine = engines.firstOrNull { it.id == state.defaultEngineID } ?: engines.firstOrNull()
    val shown = chosen ?: defaultEngine
    val teamMode = normalizeTeamMode(state.defaultTeamMode)
    val thinking = if (assistant?.config?.brainThinking == true) "high" else "off"

    fun change(what: String, block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { sayError = "没换成$what" to (it.message ?: "") } }
    }

    val modelLabel = shown?.label ?: "默认模型"
    val teamLabel = TEAM_MODES.firstOrNull { it.raw == teamMode }?.short ?: teamMode
    val thinkingLabel = thinkingTitle(thinking, ASSISTANT_THINKING_LEVELS)

    Composer(
        value = draft.input,
        onValueChange = { draft.input = it },
        placeholder = when {
            !state.connected -> "连上远程机器才能跟助手说话"
            uploading -> "附件上传中…"
            speaking -> "想插一句？打了字点发送就打断它重来"
            else -> "跟助手说点什么…"
        },
        enabled = state.connected,
        canSend = !empty && !uploading,
        busy = uploading && !speaking,
        onStop = if (speaking) ({ model.client.stopAssistant() }) else null,
        stopDescription = "打断",
        runningSend = RunningSend.Send,
        voice = voice,
        onMore = { sheetOpen = true },
        summary = if (usable) "$modelLabel · $teamLabel · 思考 $thinkingLabel" else null,
        onSend = {
            val typed = draft.input.trim()
            val paths = draft.readyPaths
            val location = draft.location
            // 位置写在前面、你打的话跟在后面（「#短号」直发时位置放后面，免得把短号挤到第二行认不出来）
            val text = when {
                location == null -> typed
                typed.isEmpty() -> location.toMessage()
                directTarget(typed) != null -> typed + "\n\n" + location.toMessage()
                else -> location.toMessage() + "\n\n" + typed
            }
            if (text.isNotEmpty() || paths.isNotEmpty()) {
                draft.clear()
                scope.launch {
                    runCatching { model.client.sayToAssistant(text, paths) }
                        .onFailure {
                            if (draft.input.isEmpty()) draft.input = typed
                            if (draft.location == null) draft.location = location
                            sayError = "没送出去" to (it.message ?: "")
                        }
                }
            }
        },
        above = {
            BackgroundBar(background, onOpen = { backgroundOpen = true })
            // 「#短号 …」= 这条不经大脑、原样转给那段会话，先说清楚再发
            if (direct != null) {
                ComposerNotice(
                    icon = Icons.Filled.Bolt,
                    text = "这条直接发给 #$direct，不经过助手",
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
            // 要一起发的位置：正在定 / 定好了（看得到发的是哪儿，不想发就 ×）
            if (draft.locating) {
                ComposerNotice(
                    icon = Icons.Filled.MyLocation,
                    text = "正在定位…",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            draft.location?.let { location ->
                ComposerNotice(
                    icon = Icons.Filled.LocationOn,
                    text = "位置：" + listOfNotNull(location.label, location.accuracyText).joinToString(" · "),
                    tint = LocalFridayExtra.current.success,
                    trailing = {
                        IconButton(onClick = { draft.location = null }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "不发位置",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                )
            }
            AttachmentChips(draft.attachments.toList(), onRemove = { id -> draft.remove(id) })
        },
    )

    if (sheetOpen) {
        val menu = ComposerMenu(
            quick = listOf(
                QuickAction(Icons.Filled.Image, "图片 / 视频", enabled = state.connected, onClick = pickers.pickMedia),
                QuickAction(Icons.Filled.Description, "文件", enabled = state.connected, onClick = pickers.pickFile),
                QuickAction(Icons.Filled.LocationOn, "位置", enabled = state.connected, onClick = shareLocation),
                QuickAction(
                    Icons.Filled.Videocam,
                    "视频通话",
                    enabled = state.connected && model.client.target != null,
                    tint = LocalFridayExtra.current.success,
                    onClick = onVideoCall,
                ),
            ),
            rows = if (!usable) emptyList() else listOf(
                ChoiceRow(
                    icon = Icons.Filled.AutoAwesome,
                    title = "模型",
                    value = modelLabel,
                    note = shown?.let { state.usage[it.usageKey]?.line },
                    header = "助手开会话、插话用的模型",
                    footnote = "只改助手的，不动新对话的默认",
                    choices = buildList {
                        add(
                            MenuChoice(
                                key = FOLLOW_DEFAULT_ENGINE,
                                title = "跟新对话一样",
                                detail = defaultEngine?.let { "现在是 ${it.label}" } ?: "跟这台新对话的默认走",
                                selected = chosenID == null,
                            )
                        )
                        engines.forEach { e ->
                            val usage = state.usage[e.usageKey]
                            add(
                                MenuChoice(
                                    key = e.id,
                                    title = e.label,
                                    detail = e.title.takeIf { it.isNotBlank() && it != e.label },
                                    note = usage?.line,
                                    loading = usage?.loading == true,
                                    selected = e.id == chosenID,
                                )
                            )
                        }
                    },
                    onOpen = { model.client.refreshUsage() },
                    onChoose = { picked ->
                        change("模型") {
                            model.client.setAssistantSessionEngine(picked.takeIf { it != FOLLOW_DEFAULT_ENGINE })
                        }
                    },
                ),
                ChoiceRow(
                    icon = Icons.Filled.AccountTree,
                    title = "任务模式",
                    value = teamLabel,
                    header = "任务模式（新开的会话）",
                    footnote = "改的是这台新对话的默认：助手新开的会话、你新建的会话都按它",
                    choices = TEAM_MODES.map { MenuChoice(it.raw, it.title, it.detail, selected = it.raw == teamMode) },
                    onChoose = { mode -> change("任务模式") { model.client.setDefaultTeamMode(mode) } },
                ),
                ChoiceRow(
                    icon = Icons.Filled.Psychology,
                    title = "思考模式",
                    value = thinkingLabel,
                    header = "思考模式（助手自己）",
                    footnote = "助手想事情、回你话时开不开深度思考",
                    choices = ASSISTANT_THINKING_LEVELS.map {
                        MenuChoice(it, thinkingTitle(it, ASSISTANT_THINKING_LEVELS), selected = it == thinking)
                    },
                    onChoose = { level -> change("思考模式") { model.client.setAssistantBrainThinking(level != "off") } },
                ),
            ),
        )
        ComposerSheet(menu, onDismiss = { sheetOpen = false })
    }

    if (backgroundOpen) {
        fun act(what: String, block: suspend () -> Unit) {
            scope.launch { runCatching { block() }.onFailure { sayError = what to (it.message ?: "") } }
        }
        BackgroundSheet(
            tasks = background,
            actions = BackgroundActions(
                setLoopPaused = { loopID, paused -> act(if (paused) "没暂停" else "没恢复") { model.client.setLoopPaused(loopID, paused) } },
                runLoopNow = { loopID -> act("没跑起来") { model.client.runLoopNow(loopID) } },
            ),
            onDismiss = { backgroundOpen = false },
        )
    }

    sayError?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = { sayError = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { sayError = null }) { Text("知道了") } },
        )
    }
}

/** 助手大脑的思考只有开 / 关（brainThinking 是布尔）：两档时菜单写「开启 / 关闭」 */
private val ASSISTANT_THINKING_LEVELS = listOf("off", "high")
