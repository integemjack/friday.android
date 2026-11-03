package com.integemjack.friday.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 主框架：底部三 tab（会话 / 活动 / 设置）。更新的进度与「安装」只在设置 › 版本与更新 里 */
@Composable
fun HomeScaffold(
    tab: Int,
    onTab: (Int) -> Unit,
    sessions: @Composable () -> Unit,
    activity: @Composable () -> Unit,
    settings: @Composable () -> Unit,
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { onTab(0) },
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                    label = { Text("会话") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { onTab(1) },
                    icon = { Icon(Icons.Filled.Bolt, contentDescription = null) },
                    label = { Text("活动") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { onTab(2) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("设置") },
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when (tab) {
                0 -> sessions()
                1 -> activity()
                else -> settings()
            }
        }
    }
}