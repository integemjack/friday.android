package com.integemjack.friday.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.integemjack.friday.proto.ConnState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 侧栏占屏幕宽度的比例（用户要求 3/4，主屏剩下左边 1/4 露在右侧） */
private const val DRAWER_WIDTH_FRACTION = 0.75f

/** 主框架的几页。会话列表不再单独一页 —— 它整段嵌在侧栏里 */
object HomeTabs {
    const val ASSISTANT = 0
    const val ACTIVITY = 1
    const val SETTINGS = 2
}

/** 打开侧栏：页面里通过这个拿到（CompositionLocal 比一层层传参干净） */
val LocalOpenDrawer = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** 收起侧栏：侧栏里点会话时用（进聊天之前先把它收起来） */
val LocalCloseDrawer = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * 主外壳：助手页是主页面，没有底部导航栏。
 *
 * 侧栏不是光秃秃四个菜单项：**助手、活动、然后是会话列表的详细内容（每条会话一行，点就进去）**，
 * 设置钉在最底下。所以主框架里没有单独的「会话列表页」了（[sessions] 只在侧栏里出现）。
 *
 * 手指往右滑时 **主屏跟着一起挪**（不是盖一层遮罩的 drawer，也没有 scrim）：侧栏从左边推进来、
 * 主屏同步右移，两侧都跟着手指走（拖动时每帧把位移喂给 [Animatable]，松手按过半没过半吸附到开 / 关）。
 * 全开时侧栏占左边 3/4，主屏让到右边只剩自己左边的 1/4。
 *
 * 底部 [dock] 只有助手页需要（跟助手说话那行），它跟着主屏一起移动。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScaffold(
    tab: Int,
    onTab: (Int) -> Unit,
    dock: (@Composable () -> Unit)? = null,
    onNewConversation: () -> Unit = {},
    assistant: @Composable () -> Unit,
    sessions: @Composable () -> Unit,
    activity: @Composable () -> Unit,
    settings: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize().background(scheme.surface)) {
        val scope = rememberCoroutineScope()
        val drawerWidthPx = with(LocalDensity.current) { (maxWidth * DRAWER_WIDTH_FRACTION).toPx() }
        val offset = remember { Animatable(0f) }

        fun openDrawer() {
            scope.launch { offset.animateTo(drawerWidthPx, tween(260)) }
        }
        fun closeDrawer() {
            scope.launch { offset.animateTo(0f, tween(220)) }
        }

        // 侧栏：宽度按比例给够，位移 = 已拉出的部分 - 自身宽度（没拉出时整个藏在左边外面）
        // 投影让它在主屏上方浮着，不跟主屏连成一片（不然看着像平板分栏）
        DrawerPanel(
            tab = tab,
            onPick = { picked ->
                onTab(picked)
                closeDrawer()
            },
            closeDrawer = ::closeDrawer,
            onNewConversation = {
                closeDrawer()
                onNewConversation()
            },
            sessions = sessions,
            modifier = Modifier
                .width(maxWidth * DRAWER_WIDTH_FRACTION)
                .fillMaxHeight()
                .offset { IntOffset((offset.value - drawerWidthPx).roundToInt(), 0) }
                .graphicsLayer {
                    val progress = (offset.value / drawerWidthPx).coerceIn(0f, 1f)
                    shadowElevation = progress * 18.dp.toPx()
                },
        )

        // 主屏：整块跟着手指右移；移动时左边给圆角 + 一点投影，看着是被「推开」而不是「滑走」
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .graphicsLayer {
                    val progress = (offset.value / drawerWidthPx).coerceIn(0f, 1f)
                    shape = RoundedCornerShape((progress * 22).dp)
                    clip = progress > 0.01f
                    shadowElevation = progress * 16.dp.toPx()
                }
                // 水平拖动交给它：主屏内容里没有横向滚动，不会跟谁抢。
                // 纵向滚动（列表）由子级消费，不会走到这里
                .pointerInput(drawerWidthPx) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offset.snapTo((offset.value + dragAmount).coerceIn(0f, drawerWidthPx))
                            }
                        },
                        onDragEnd = {
                            val open = offset.value > drawerWidthPx / 2
                            scope.launch {
                                offset.animateTo(if (open) drawerWidthPx else 0f, tween(220))
                            }
                        },
                        onDragCancel = {
                            val open = offset.value > drawerWidthPx / 2
                            scope.launch {
                                offset.animateTo(if (open) drawerWidthPx else 0f, tween(220))
                            }
                        },
                    )
                }
        ) {
            CompositionLocalProvider(
                LocalOpenDrawer provides ::openDrawer,
                LocalCloseDrawer provides ::closeDrawer,
            ) {
                Scaffold(
                    containerColor = scheme.background,
                    bottomBar = {
                        if (dock != null) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .background(scheme.surface)
                                    .windowInsetsPadding(
                                        WindowInsets.safeDrawing
                                            .union(WindowInsets.ime)
                                            .only(WindowInsetsSides.Bottom)
                                    )
                            ) {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = scheme.outlineVariant.copy(alpha = 0.7f),
                                )
                                dock()
                            }
                        }
                    },
                ) { padding ->
                    // consumeWindowInsets：里面那层（助手页自己的 Scaffold）再按 safeDrawing 让位时，
                    // 导航条 / 键盘这段已经扣过了，不会重复让一次、在内容和底栏之间空出一条
                    Column(Modifier.padding(padding).consumeWindowInsets(padding)) {
                        when (tab) {
                            HomeTabs.ASSISTANT -> assistant()
                            HomeTabs.ACTIVITY -> activity()
                            else -> settings()
                        }
                    }
                }
            }
            // 侧栏开着时主屏压暗一层：露出的那 1/4 看成「后面的页面」，不是被切了一半的分栏。
            // 点一下就收起（也吃住了点击，不会点进下面的列表）
            val progress = (offset.value / drawerWidthPx).coerceIn(0f, 1f)
            if (progress > 0.001f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.34f * progress))
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { closeDrawer() }
                )
            }
        }
    }
}

/**
 * 侧栏那一列：品牌 → 助手 / 活动 → 会话列表的详细内容（占满剩下的高度，自己滚） → 设置钉在底部。
 */
@Composable
private fun DrawerPanel(
    tab: Int,
    onPick: (Int) -> Unit,
    closeDrawer: () -> Unit,
    onNewConversation: () -> Unit,
    sessions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    Column(
        modifier
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
    ) {
        DrawerBrand()
        Spacer(Modifier.height(12.dp))
        DrawerItem(Icons.Filled.SelfImprovement, "助手", tab == HomeTabs.ASSISTANT) {
            onPick(HomeTabs.ASSISTANT)
        }
        DrawerItem(Icons.Filled.Bolt, "活动", tab == HomeTabs.ACTIVITY) {
            onPick(HomeTabs.ACTIVITY)
        }

        // 会话列表整段就长在侧栏里：小标题 + 计数 + 「+」（新会话），下面是真列表
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "会话",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.summaries.isNotEmpty()) {
                Spacer(Modifier.width(7.dp))
                Chip(
                    "${state.summaries.size}",
                    color = MaterialTheme.colorScheme.primary,
                    container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.weight(1f))
            // 新会话：放在这里而不是悬浮按钮 —— 悬浮按钮会盖住列表最后几条
            IconButton(onClick = onNewConversation, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "新会话",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // 侧栏里的会话行点了要先把侧栏收起来
            CompositionLocalProvider(LocalCloseDrawer provides closeDrawer) {
                sessions()
            }
        }

        // 设置固定在底部，不跟会话列表滚
        HorizontalDivider(
            Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        )
        DrawerItem(Icons.Filled.Settings, "设置", tab == HomeTabs.SETTINGS) {
            onPick(HomeTabs.SETTINGS)
        }
        Text(
            "Friday · v${com.integemjack.friday.BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 4.dp),
        )
    }
}

/** 侧栏顶上：图标 + 名字 + 连着哪台机器 */
@Composable
private fun DrawerBrand() {
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    val server = (state.conn as? ConnState.Connected)?.server
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "F",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("Friday", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (server != null) {
                    Dot(com.integemjack.friday.ui.theme.LocalFridayExtra.current.success, size = 7.dp)
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    server?.displayName?.ifBlank { server.hostname } ?: "没连上",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 侧栏里的一项：选中时整条淡主色底 + 主色图标文字 */
@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (selected) scheme.primary else scheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) scheme.primaryContainer.copy(alpha = 0.65f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(13.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) scheme.primary else scheme.onSurface,
        )
    }
}