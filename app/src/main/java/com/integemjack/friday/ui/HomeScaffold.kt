package com.integemjack.friday.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 主框架：底部四 tab（助手 / 会话 / 活动 / 设置）。
 *
 * 底栏是一整块 dock，不是「输入框 + 导航栏」两条叠着：
 * - 同一个背景、同一条顶边、一起让位系统导航条；
 * - 助手页时 [dock] 那行（输入框）在上、tab 行在下，看着是一个组件；
 * - 键盘弹起时 tab 行收起，只留输入框贴在键盘上方（省出屏幕，也不用担心误切页）；
 * - 让位取「键盘」和「导航条」里大的那个，两者不叠加。
 *
 * 更新的进度与「安装」只在设置 › 版本与更新 里。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScaffold(
    tab: Int,
    onTab: (Int) -> Unit,
    dock: (@Composable () -> Unit)? = null,
    assistant: @Composable () -> Unit,
    sessions: @Composable () -> Unit,
    activity: @Composable () -> Unit,
    settings: @Composable () -> Unit,
) {
    val imeVisible = WindowInsets.isImeVisible
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    // 键盘弹起时整块 dock 坐到键盘上；没键盘时让出系统导航条
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing
                            .union(WindowInsets.ime)
                            .only(WindowInsetsSides.Bottom)
                    )
            ) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                )
                dock?.invoke()
                AnimatedVisibility(
                    visible = !imeVisible,
                    enter = expandVertically(animationSpec = tween(180)) + fadeIn(tween(180)),
                    exit = shrinkVertically(animationSpec = tween(140)) + fadeOut(tween(100)),
                ) {
                    DockTabs(tab = tab, onTab = onTab)
                }
            }
        },
    ) { padding ->
        // consumeWindowInsets：里面那层（各页自己的 Scaffold）再按 safeDrawing 让位时，
        // 导航条 / 键盘这段已经扣过了，不会重复让一次、在内容和 dock 之间空出一条
        Column(Modifier.padding(padding).consumeWindowInsets(padding)) {
            when (tab) {
                0 -> assistant()
                1 -> sessions()
                2 -> activity()
                else -> settings()
            }
        }
    }
}

/** dock 下半截：四个 tab。自己画而不用 NavigationBar，好和上面的输入框行共用一个容器、一套间距 */
@Composable
private fun DockTabs(tab: Int, onTab: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockTab(Icons.Filled.SelfImprovement, "助手", tab == 0) { onTab(0) }
        DockTab(Icons.AutoMirrored.Filled.Chat, "会话", tab == 1) { onTab(1) }
        DockTab(Icons.Filled.Bolt, "活动", tab == 2) { onTab(2) }
        DockTab(Icons.Filled.Settings, "设置", tab == 3) { onTab(3) }
    }
}

@Composable
private fun RowScope.DockTab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (selected) scheme.primary else scheme.onSurfaceVariant
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (selected) scheme.primaryContainer.copy(alpha = 0.7f) else Color.Transparent)
                .padding(horizontal = 18.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.height(21.dp), tint = tint)
        }
        Spacer(Modifier.height(1.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
        )
    }
}
