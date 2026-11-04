package com.integemjack.friday.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.integemjack.friday.proto.ConnState
import com.integemjack.friday.ui.theme.LocalFridayExtra
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 侧栏占屏幕宽度的比例（用户要求 3/4，主屏剩下左边 1/4 露在右侧） */
private const val DRAWER_WIDTH_FRACTION = 0.75f

/**
 * 主框架的几页，全是**顶级页面**（同一个外壳、同一个侧栏入口、同一个底部输入框位置）。
 * 编号是通知 intent 里用的（[com.integemjack.friday.MainActivity.EXTRA_TAB]），别改已有的数。
 * 会话列表不单独成页 —— 它整段嵌在侧栏里；点一条进的是 [CHAT]。
 * 1 原来是活动页，已经去掉了；不认识的编号一律回 [ASSISTANT]（见 [of]）。
 */
object HomeTabs {
    const val ASSISTANT = 0
    const val SETTINGS = 2
    const val CHAT = 3

    /** 外面传进来的编号（通知 intent、老版本留下的）：认识的照用，不认识的回助手页 */
    fun of(tab: Int): Int = if (tab == SETTINGS || tab == CHAT) tab else ASSISTANT
}

/** 打开侧栏：页面里通过这个拿到（CompositionLocal 比一层层传参干净） */
val LocalOpenDrawer = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** 收起侧栏：侧栏里点会话时用（进聊天之前先把它收起来） */
val LocalCloseDrawer = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * 底部输入框（连同导航条 / 键盘）占掉的高度。输入框背后是透明的，页面内容一直铺到屏幕底，
 * 列表要把这段加进自己的 contentPadding，最后一条才能滚到输入框上面；没有输入框的页是 0
 */
val LocalDockPadding = compositionLocalOf { 0.dp }

/** 输入框焦点的「点哪儿都收起」：[Composer] 报自己有没有焦点、这一下是不是点在框里，[HomeScaffold] 在最外层判断 */
val LocalInputFocus = staticCompositionLocalOf<InputFocusTracker?> { null }

/** 只给触摸判断用的两个标记（不是界面状态，不用触发重组） */
class InputFocusTracker {
    /** 输入框现在有焦点 */
    var focused = false

    /** 这次按下是落在输入框里的（框自己在 Initial 阶段标上，外壳在 Main 阶段读完清掉） */
    var touchedInside = false
}

/** 列表顶上渐隐的那一段高度：内容滚到顶栏下面是慢慢淡掉的，不是一刀切 */
val TopFadeHeight = 20.dp

/** 顶边渐隐：把内容本身的透明度从顶上 0 渐变到 [height] 处的 1（离屏合成后按 DstIn 抠），不依赖背后是什么颜色 */
fun Modifier.fadeTopEdge(height: Dp = TopFadeHeight): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = height.toPx()
        drawRect(
            brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = h),
            size = Size(size.width, h),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * 主外壳：助手页是主页面，会话页跟它平级，都没有底部导航栏。
 *
 * 手指往右滑时 **主屏跟着一起挪**（不是盖一层遮罩的 drawer）：侧栏从左边推进来、主屏同步右移，
 * 两侧都跟着手指走（拖动时每帧把位移喂给 [Animatable]，松手按过半没过半吸附到开 / 关）。
 * 全开时侧栏占左边 3/4，主屏让到右边只剩自己左边的 1/4，压暗一点、带圆角和投影，看着是「后面那页」。
 *
 * 底部 [dock] 是输入框（助手页、会话页都是同一个 [Composer]），它跟着主屏一起移动，
 * 由这里统一让位导航条和键盘，两页的输入框位置、留白一模一样。
 * 输入框背后不垫底色：页面内容一直铺到屏幕底（导航条底下也是内容），
 * 输入框占掉的高度经 [LocalDockPadding] 交给各页的列表自己让。
 *
 * 输入框有焦点时，在屏幕上**任何地方**按下（滚列表、拉侧栏、点按钮……只要不是点在输入框里）就把焦点收掉、键盘收起。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScaffold(
    tab: Int,
    onTab: (Int) -> Unit,
    dock: (@Composable () -> Unit)? = null,
    sessions: @Composable () -> Unit,
    onNewConversation: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val inputFocus = remember { InputFocusTracker() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(drawerBackground())
            // 只看不吃：Main 阶段在最外层，子级（输入框）的 Initial 已经先跑过、标好了是不是点在框里
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.changes.any { it.changedToDownIgnoreConsumed() }) {
                            if (inputFocus.focused && !inputFocus.touchedInside) focusManager.clearFocus()
                            inputFocus.touchedInside = false
                        }
                    }
                }
            }
    ) {
        val scope = rememberCoroutineScope()
        val drawerWidthPx = with(LocalDensity.current) { (maxWidth * DRAWER_WIDTH_FRACTION).toPx() }
        val offset = remember { Animatable(0f) }
        val drawerOpen by remember { derivedStateOf { offset.targetValue > 1f || offset.value > 1f } }

        fun openDrawer() {
            scope.launch { offset.animateTo(drawerWidthPx, tween(260)) }
        }
        fun closeDrawer() {
            scope.launch { offset.animateTo(0f, tween(220)) }
        }
        fun settle() {
            val open = offset.value > drawerWidthPx / 2
            scope.launch { offset.animateTo(if (open) drawerWidthPx else 0f, tween(220)) }
        }

        // 侧栏开着时返回键先收侧栏（比 AppRoot 里「回助手页」的那个后注册，所以先生效）
        BackHandler(enabled = drawerOpen) { closeDrawer() }

        // 侧栏：宽度按比例给够，位移 = 已拉出的部分 - 自身宽度（没拉出时整个藏在左边外面）；
        // 拉出过程中带一点视差（比主屏慢一点进来），不是生硬地平移
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
                .offset {
                    val progress = (offset.value / drawerWidthPx).coerceIn(0f, 1f)
                    IntOffset(((progress - 1f) * drawerWidthPx * 0.35f).roundToInt(), 0)
                }
                .graphicsLayer {
                    alpha = (offset.value / drawerWidthPx).coerceIn(0f, 1f).let { 0.35f + 0.65f * it }
                },
        )

        // 主屏：整块跟着手指右移；移动时左边给圆角 + 投影 + 压暗，看着是被「推开」而不是「滑走」
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .graphicsLayer {
                    val progress = (offset.value / drawerWidthPx).coerceIn(0f, 1f)
                    shape = RoundedCornerShape((progress * 26).dp)
                    clip = progress > 0.01f
                    shadowElevation = progress * 18.dp.toPx()
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
                        onDragEnd = ::settle,
                        onDragCancel = ::settle,
                    )
                }
        ) {
            CompositionLocalProvider(
                LocalOpenDrawer provides ::openDrawer,
                LocalCloseDrawer provides ::closeDrawer,
                LocalInputFocus provides inputFocus,
            ) {
                Scaffold(
                    containerColor = scheme.background,
                    bottomBar = {
                        if (dock != null) {
                            // 输入框所在的那块：背后透明（不垫一整条底色），没键盘时让出导航条、
                            // 键盘弹起时取键盘高度（safeDrawing ∪ ime 的底部，两者不会叠着算）
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(
                                        WindowInsets.safeDrawing
                                            .union(WindowInsets.ime)
                                            .only(WindowInsetsSides.Bottom)
                                    )
                            ) {
                                dock()
                            }
                        }
                    },
                ) { padding ->
                    // 有输入框时内容不在底下让位：一直铺到屏幕底（Scaffold 把 bottomBar 画在内容上面），
                    // 让多少交给各页列表（LocalDockPadding）。
                    // consumeWindowInsets：里面那层（各页自己的 Scaffold）再按 safeDrawing 让位时，
                    // 导航条 / 键盘这段已经扣过了，不会重复让一次
                    val layoutDirection = LocalLayoutDirection.current
                    val dockPadding = if (dock != null) padding.calculateBottomPadding() else 0.dp
                    Column(
                        Modifier
                            .padding(
                                start = padding.calculateStartPadding(layoutDirection),
                                top = padding.calculateTopPadding(),
                                end = padding.calculateEndPadding(layoutDirection),
                                bottom = padding.calculateBottomPadding() - dockPadding,
                            )
                            .consumeWindowInsets(padding)
                    ) {
                        CompositionLocalProvider(LocalDockPadding provides dockPadding) {
                            content()
                        }
                    }
                }
            }
            // 侧栏开合时主屏压暗一层（按拉出的比例）；开着的时候点主屏任意处收起
            if (offset.value > 1f) {
                val progress = (offset.value / drawerWidthPx).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.10f * progress))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { closeDrawer() }
                )
            }
        }
    }
}

/** 侧栏底色：比页面背景深一档，主屏推开后浮在它上面才分得出前后 */
@Composable
private fun drawerBackground(): Color = MaterialTheme.colorScheme.surfaceContainer

/** 顶栏高度：比 Material 默认的 64dp 矮一截（助手页、会话页只有一行标题） */
private val HomeTopBarHeight = 52.dp

/**
 * 顶级页面共用的顶栏：左上角侧栏入口（有待确认的调用时带个小红点），标题（设置下面再带一行小字；
 * 助手页、会话页只有标题）。切来切去，顶栏的高度、字号、按钮位置都不动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopBar(
    title: String,
    subtitle: String? = null,
    subtitleColor: Color? = null,
    dotColor: Color? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val openDrawer = LocalOpenDrawer.current
    TopAppBar(
        expandedHeight = HomeTopBarHeight,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = scheme.background,
            scrolledContainerColor = scheme.background,
        ),
        navigationIcon = {
            if (openDrawer != null) DrawerButton(openDrawer)
        },
        title = {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (dotColor != null) {
                            Dot(dotColor, 6.dp)
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = subtitleColor ?: scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        actions = actions,
    )
}

/**
 * 汉堡按钮：有调用 / 提问等你确认时右上角挂一个提醒色小点，在哪一页都看得到。
 * 顶级页面（助手、会话、设置）左上角都是它。
 */
@Composable
fun DrawerButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    val waiting = waitingCount(state)
    IconButton(onClick = onClick, modifier = modifier) {
        Box {
            Icon(Icons.Filled.Menu, contentDescription = "打开侧边栏", modifier = Modifier.size(22.dp))
            if (waiting > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 0.dp)
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.tertiary)
                )
            }
        }
    }
}

/** 有几段会话在等你确认（调用批准 / ask_user 提问，同一段只算一次） */
private fun waitingCount(state: com.integemjack.friday.proto.FridayState): Int {
    val runner = state.runner ?: return 0
    return (runner.pendingApproval + runner.pendingQuestion).toSet().size
}

/**
 * 侧栏那一列：品牌 + 连接状态（点它进助手页）→ 会话（标题 + 新建 + 列表，占满剩下的高度，自己滚）
 * → 设置钉在底部（版本号挂在它右边）。
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
    val scheme = MaterialTheme.colorScheme
    // 侧栏不在 Surface 里：没写颜色的字默认是纯黑，深色模式下就看不见了。这里给一个默认前景色
    CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
        Column(
            modifier
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
        ) {
            // 顶上的 Friday 就是助手页的入口（不再单列一个「助手」）
            DrawerBrand(
                conn = state.conn,
                lastServer = state.lastServer,
                selected = tab == HomeTabs.ASSISTANT,
                speaking = state.assistant?.speaking == true,
                onClick = { onPick(HomeTabs.ASSISTANT) },
            )
            Spacer(Modifier.height(4.dp))

            // 会话：小标题 + 计数 + 新建，下面是真列表（点一条直接进会话页）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 2.dp, top = 18.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "会话",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
                if (state.summaries.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${state.summaries.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                }
                Spacer(Modifier.weight(1f))
                NewConversationButton(enabled = state.connected, onClick = onNewConversation)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // 侧栏里的会话行点了要先把侧栏收起来
                CompositionLocalProvider(LocalCloseDrawer provides closeDrawer) {
                    sessions()
                }
            }

            // 设置固定在底部，不跟会话列表滚；版本号挂在它右边
            HorizontalDivider(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                thickness = 0.5.dp,
                color = scheme.outlineVariant.copy(alpha = 0.8f),
            )
            DrawerItem(
                icon = Icons.Filled.Settings,
                label = "设置",
                selected = tab == HomeTabs.SETTINGS,
                trailing = {
                    Text(
                        "v${com.integemjack.friday.BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                },
            ) { onPick(HomeTabs.SETTINGS) }
        }
    }
}

/**
 * 侧栏顶上：渐变图标 + 名字 + 连着哪台机器（带状态灯）。整块点进助手页；
 * 在助手页时垫一层淡主色底（同侧栏里选中的那一项），助手在说话时右边挂「在说」
 */
@Composable
private fun DrawerBrand(
    conn: ConnState,
    lastServer: com.integemjack.friday.proto.ServerInfo?,
    selected: Boolean,
    speaking: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val (dot, line) = when (conn) {
        is ConnState.Connected ->
            LocalFridayExtra.current.success to conn.server.displayName.ifBlank { conn.server.hostname }
        is ConnState.Connecting -> scheme.tertiary to (lastServer?.let { "正在连 ${it.hostname}…" } ?: "连接中…")
        is ConnState.Failed -> scheme.error to (lastServer?.let { "离线 · ${it.hostname}，自动重连中" } ?: "连接失败")
        ConnState.Disconnected -> scheme.outline to (lastServer?.let { "离线 · ${it.hostname}" } ?: "没连上远程机器")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) scheme.primaryContainer.copy(alpha = 0.75f) else Color.Transparent)
            .clickable(role = Role.Button, onClickLabel = "打开助手", onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            scheme.primary,
                            if (dark) Color(0xFFD3C2FF) else Color(0xFF7A56DC),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "F",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = scheme.onPrimary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Friday", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(dot, size = 7.dp)
                Spacer(Modifier.width(5.dp))
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (speaking) {
            Spacer(Modifier.width(6.dp))
            Chip("在说", color = scheme.primary)
        }
    }
}

/** 侧栏「会话」标题右边的新建：小胶囊（＋ 新建），没连上时灰着 */
@Composable
private fun NewConversationButton(enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (enabled) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.45f)
    Row(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 8.dp, end = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(3.dp))
        Text("新建", style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/**
 * 侧栏里的一项：左边圆角方块里的图标（选中时主色实心），中间名字，右边可挂状态胶囊。
 * 选中时整条淡主色底。
 */
@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) scheme.primaryContainer.copy(alpha = 0.75f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (selected) scheme.primary else scheme.surfaceContainerHighest.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            trailing()
        }
    }
}
