package com.integemjack.friday.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** 主框架：底部四 tab（助手 / 会话 / 活动 / 设置）。更新的进度与「安装」只在设置 › 版本与更新 里 */
@Composable
fun HomeScaffold(
    tab: Int,
    onTab: (Int) -> Unit,
    assistant: @Composable () -> Unit,
    sessions: @Composable () -> Unit,
    activity: @Composable () -> Unit,
    settings: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
            ) {
                val colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { onTab(0) },
                    colors = colors,
                    icon = { Icon(Icons.Filled.SelfImprovement, contentDescription = null) },
                    label = { Text("助手", style = MaterialTheme.typography.labelMedium) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { onTab(1) },
                    colors = colors,
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                    label = { Text("会话", style = MaterialTheme.typography.labelMedium) },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { onTab(2) },
                    colors = colors,
                    icon = { Icon(Icons.Filled.Bolt, contentDescription = null) },
                    label = { Text("活动", style = MaterialTheme.typography.labelMedium) },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { onTab(3) },
                    colors = colors,
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("设置", style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when (tab) {
                0 -> assistant()
                1 -> sessions()
                2 -> activity()
                else -> settings()
            }
        }
    }
}