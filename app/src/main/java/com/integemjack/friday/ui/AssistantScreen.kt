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
import androidx.compose.material.icons.filled.Bolt
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
import androidx.compose.material3.Switch
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
 * 用户的话在右边气泡（标来源）、助手的回复在左边、工具卡片、回执行带「打开」和「批准 / 拒绝」；
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
            // 和会话页同一条顶栏（左上角侧栏入口、标题 + 一行状态），两页切来切去不跳
            val where = state.conn as? com.integemjack.friday.proto.ConnState.Connected
            HomeTopBar(
                title = assistant?.config?.name?.takeIf { it.isNotBlank() } ?: "助手",
                subtitle = when {
                    !state.connected -> "没连上远程机器"
                    assistant?.enabled == true ->
                        (where?.server?.hostname ?: "") +
                            (assistant.brainEngineTitle?.let { " · $it" } ?: "")
                    else -> (where?.server?.hostname ?: "") + " · 还没打开"
                },
                subtitleColor = if (assistant?.speaking == true) MaterialTheme.colorScheme.primary else null,
                dotColor = when {
                    assistant?.speaking == true -> MaterialTheme.colorScheme.primary
                    assistant?.enabled == true -> LocalFridayExtra.current.success
                    else -> null
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

// MARK: 输入框：跟助手说话

/**
 * 助手页的输入框：跟会话页是**同一个** [Composer]（同样的卡片、左下角「＋」、默认的语音键、
 * 同样的发送 / 停止），由 [HomeScaffold] 放进 dock，所以这里不画背景、也不自己算 inset。
 *
 * 「＋」面板里：图片 / 视频、文件、**视频通话**（直接打给这台的助手，[com.integemjack.friday.call.AssistantCall]），
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
    var sayError by remember { mutableStateOf<Pair<String, String>?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
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
            val text = draft.input.trim()
            val paths = draft.readyPaths
            if (text.isNotEmpty() || paths.isNotEmpty()) {
                draft.clear()
                scope.launch {
                    runCatching { model.client.sayToAssistant(text, paths) }
                        .onFailure {
                            if (draft.input.isEmpty()) draft.input = text
                            sayError = "没送出去" to (it.message ?: "")
                        }
                }
            }
        },
        above = {
            // 「#短号 …」= 这条不经大脑、原样转给那段会话，先说清楚再发
            if (direct != null) {
                ComposerNotice(
                    icon = Icons.Filled.Bolt,
                    text = "这条直接发给 #$direct，不经过助手",
                    tint = MaterialTheme.colorScheme.tertiary,
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
