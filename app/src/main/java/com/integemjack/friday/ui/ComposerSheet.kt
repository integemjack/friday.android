package com.integemjack.friday.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SubdirectoryArrowLeft
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.integemjack.friday.proto.ContextUsage
import com.integemjack.friday.proto.FsListing
import com.integemjack.friday.proto.RecentDirectory
import com.integemjack.friday.proto.UsageLine
import com.integemjack.friday.ui.theme.LocalFridayExtra

/*
 * 输入框左下角「＋」点出来的那张底部面板：上面一排快捷（附件，助手页还有视频通话），
 * 会话页接着是这段会话的上下文用量（点开展开明细），
 * 下面是这段对话 / 助手的设置——模型、任务模式、思考模式、工作目录，点一行进到它的列表，选完回来。
 * 会话页、助手页用的是同一张面板，只是给的东西不同（ChatComposer / AssistantComposer 拼 [ComposerMenu]）。
 */

/** 面板里要放的东西 */
class ComposerMenu(
    val quick: List<QuickAction>,
    val rows: List<MenuRow>,
    /** 这段会话的上下文用量（会话页才有）：快捷下面、设置上面一行，点开在原地展开明细（[ContextUsageCard]） */
    val context: ContextUsage? = null,
)

/** 上面一排的快捷：点了就做、面板收起 */
class QuickAction(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val tint: Color? = null,
    val onClick: () -> Unit,
)

/** 设置的一行：点进去是一张列表 */
sealed class MenuRow(
    val icon: ImageVector,
    val title: String,
    /** 行右边显示的当前值 */
    val value: String,
    val enabled: Boolean,
    /** 行里标题下面那一句（模型那行：当前这家的余额 / 用量） */
    val note: UsageLine? = null,
)

/** 选一个的那种（模型、任务模式、思考模式） */
class ChoiceRow(
    icon: ImageVector,
    title: String,
    value: String,
    enabled: Boolean = true,
    note: UsageLine? = null,
    val header: String? = null,
    val footnote: String? = null,
    val choices: List<MenuChoice>,
    val emptyText: String = "没有能选的",
    /** 进到这页时做一下（模型那页：查一遍余额） */
    val onOpen: (suspend () -> Unit)? = null,
    val onChoose: (String) -> Unit,
) : MenuRow(icon, title, value, enabled, note)

/** 列表里的一项 */
data class MenuChoice(
    val key: String,
    val title: String,
    val detail: String? = null,
    /** 底下那句彩色的（余额 / 用量：额度用完时提醒色） */
    val note: UsageLine? = null,
    val loading: Boolean = false,
    val selected: Boolean = false,
)

/** 工作目录：默认 / 最近选过的 / 浏览这台机器 */
class DirectoryRow(
    icon: ImageVector,
    title: String,
    value: String,
    enabled: Boolean = true,
    /** 现在的目录（null = 用默认的） */
    val current: String?,
    /** 这台最近选过的；null = 旧服务端没有这一份 */
    val recent: List<RecentDirectory>?,
    val defaultLabel: String,
    val onOpen: suspend () -> Unit,
    val onChoose: (String?) -> Unit,
    val onForget: (String) -> Unit,
    val list: suspend (String?) -> FsListing,
) : MenuRow(icon, title, value, enabled)

/** 底部面板本体（Modal）。[onDismiss] 收起 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerSheet(menu: ComposerMenu, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ComposerMenuContent(menu, onClose = onDismiss)
    }
}

/** 面板里的内容（单独拿出来：界面测试里不开 Modal 窗口也能验） */
@Composable
fun ComposerMenuContent(menu: ComposerMenu, onClose: () -> Unit) {
    // null = 首页；否则是 rows 的下标；browsing = 工作目录那页里正在浏览
    var page by remember { mutableStateOf<Int?>(null) }
    var browsing by remember { mutableStateOf(false) }
    // 返回键先退一层（浏览 → 目录页 → 首页），在首页才收起面板
    androidx.activity.compose.BackHandler(enabled = page != null) {
        if (browsing) browsing = false else page = null
    }
    AnimatedContent(
        targetState = page to browsing,
        transitionSpec = {
            val forward = (targetState.first != null && initialState.first == null) || (targetState.second && !initialState.second)
            val dir = if (forward) 1 else -1
            (slideInHorizontally { it * dir / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 4 } + fadeOut())
        },
        label = "composerMenu",
    ) { (index, browse) ->
        val row = index?.let { menu.rows.getOrNull(it) }
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            when {
                row == null -> MainPage(menu, onOpenRow = { page = it }, onClose = onClose)
                row is ChoiceRow -> ChoicePage(row, onBack = { page = null })
                row is DirectoryRow && browse -> BrowsePage(
                    row,
                    onBack = { browsing = false },
                    onPicked = {
                        browsing = false
                        page = null
                    },
                )
                row is DirectoryRow -> DirectoryPage(row, onBack = { page = null }, onBrowse = { browsing = true })
            }
        }
    }
}

// MARK: 首页

@Composable
private fun MainPage(menu: ComposerMenu, onOpenRow: (Int) -> Unit, onClose: () -> Unit) {
    // 上下文明细展开以后（横屏、矮屏）可能放不下：首页能滚
    Column(Modifier.verticalScroll(rememberScrollState())) {
        MainPageContent(menu, onOpenRow, onClose)
    }
}

@Composable
private fun MainPageContent(menu: ComposerMenu, onOpenRow: (Int) -> Unit, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    if (menu.quick.isNotEmpty()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            menu.quick.forEach { action ->
                QuickTile(action) {
                    onClose()
                    action.onClick()
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    menu.context?.let {
        ContextUsageCard(it)
        Spacer(Modifier.height(10.dp))
    }
    if (menu.rows.isNotEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(scheme.surfaceContainerLowest),
        ) {
            menu.rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(
                        Modifier.padding(start = 58.dp),
                        thickness = 0.5.dp,
                        color = scheme.outlineVariant.copy(alpha = 0.7f),
                    )
                }
                SettingRow(row) { onOpenRow(index) }
            }
        }
    }
}

@Composable
private fun QuickTile(action: QuickAction, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = action.tint ?: scheme.primary
    Column(
        Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = action.enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (action.enabled) tint.copy(alpha = 0.13f) else scheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                action.icon,
                contentDescription = null,
                tint = if (action.enabled) tint else scheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            action.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (action.enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SettingRow(row: MenuRow, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = row.enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(row.icon, tint = if (row.enabled) scheme.primary else scheme.outline, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(10.dp))
                Text(
                    row.value,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (row.enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            row.note?.let { UsageText(it, Modifier.padding(top = 2.dp)) }
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun UsageText(line: UsageLine, modifier: Modifier = Modifier) {
    Text(
        line.text,
        style = MaterialTheme.typography.labelSmall,
        color = if (line.warning) MaterialTheme.colorScheme.tertiary else LocalFridayExtra.current.success,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

// MARK: 子页

@Composable
private fun SheetHeader(title: String, subtitle: String? = null, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ListCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .verticalScroll(rememberScrollState()),
        content = content,
    )
}

@Composable
private fun ChoicePage(row: ChoiceRow, onBack: () -> Unit) {
    LaunchedEffect(row.title) { row.onOpen?.invoke() }
    SheetHeader(row.header ?: row.title, onBack = onBack)
    ListCard {
        if (row.choices.isEmpty()) {
            Text(
                row.emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        row.choices.forEach { choice ->
            ChoiceItem(choice) {
                if (!choice.selected) row.onChoose(choice.key)
                onBack()
            }
        }
    }
    row.footnote?.let { Footnote(it) }
}

@Composable
private fun ChoiceItem(choice: MenuChoice, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                choice.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (choice.selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (choice.selected) scheme.primary else scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            choice.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 3)
            }
            when {
                choice.note != null -> UsageText(choice.note, Modifier.padding(top = 2.dp))
                choice.loading -> Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(5.dp))
                    Text("在查余额…", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
        if (choice.selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, contentDescription = "当前", tint = scheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
    )
}

// MARK: 工作目录

@Composable
private fun DirectoryPage(row: DirectoryRow, onBack: () -> Unit, onBrowse: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { row.onOpen() }
    SheetHeader(row.title, subtitle = row.current ?: row.defaultLabel, onBack = onBack)
    ListCard {
        DirItem(
            icon = Icons.Filled.Home,
            title = "默认目录",
            detail = row.defaultLabel,
            selected = row.current == null,
        ) {
            if (row.current != null) row.onChoose(null)
            onBack()
        }
        val recent = row.recent.orEmpty()
        if (recent.isNotEmpty()) {
            Text(
                "最近选过的",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
            )
            recent.forEach { dir ->
                DirItem(
                    icon = Icons.Filled.Folder,
                    title = dir.path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { dir.path },
                    detail = if (dir.exists) dir.path else "${dir.path}（不在了）",
                    selected = dir.path == row.current,
                    enabled = dir.exists,
                    onForget = { row.onForget(dir.path) },
                ) {
                    if (dir.path != row.current) row.onChoose(dir.path)
                    onBack()
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    FilledTonalButton(
        onClick = onBrowse,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("浏览这台机器上的目录…")
    }
    Footnote("只换这段对话的；正在跑的下一轮生效。文件都在远程那台上")
}

@Composable
private fun DirItem(
    icon: ImageVector,
    title: String,
    detail: String?,
    selected: Boolean,
    enabled: Boolean = true,
    onForget: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 16.dp, end = if (onForget != null) 4.dp else 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    selected -> scheme.primary
                    enabled -> scheme.onSurface
                    else -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "当前", tint = scheme.primary, modifier = Modifier.size(20.dp))
        }
        if (onForget != null) {
            IconButton(onClick = onForget, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "从最近里去掉", tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 浏览这台机器的目录（fs.list）：一层层点进去，「选这个目录」就用当前这层 */
@Composable
private fun BrowsePage(row: DirectoryRow, onBack: () -> Unit, onPicked: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var path by remember { mutableStateOf(row.current) }
    var listing by remember { mutableStateOf<FsListing?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        loading = true
        error = null
        runCatching { row.list(path) }
            .onSuccess { listing = it }
            .onFailure { error = it.message ?: "读不了这个目录" }
        loading = false
    }
    val here = listing?.path.orEmpty()
    SheetHeader("选工作目录", subtitle = here.ifEmpty { "…" }, onBack = onBack)
    ListCard {
        listing?.roots?.takeIf { it.isNotEmpty() }?.let { roots ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                roots.take(4).forEach { root ->
                    Chip(
                        root.name,
                        color = if (root.path == here) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.clickable { path = root.path },
                    )
                }
            }
        }
        when {
            loading && listing == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            error != null -> Text(
                error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
                modifier = Modifier.padding(16.dp),
            )
            else -> {
                listing?.parent?.let { parent ->
                    DirItem(icon = Icons.Filled.SubdirectoryArrowLeft, title = "上一层", detail = parent, selected = false) { path = parent }
                }
                val entries = listing?.entries.orEmpty()
                if (entries.isEmpty()) {
                    Text(
                        "这里没有子目录",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                entries.forEach { entry ->
                    DirItem(icon = Icons.Filled.Folder, title = entry.name, detail = null, selected = false) { path = entry.path }
                }
                if (listing?.truncated == true) Footnote("太多了，只列了前面一部分")
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    FilledTonalButton(
        enabled = here.isNotEmpty() && !loading,
        onClick = {
            row.onChoose(here)
            onPicked()
        },
        contentPadding = PaddingValues(vertical = 12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) { Text("用这个目录") }
}
