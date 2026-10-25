package com.integemjack.friday.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.integemjack.friday.update.SelfUpdate

/** 主框架：更新 banner + 底部三 tab（会话 / 活动 / 设置） */
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
            UpdateBanner()
            when (tab) {
                0 -> sessions()
                1 -> activity()
                else -> settings()
            }
        }
    }
}

/** 自动更新横幅：发现新版 → 自动下载进度 → 一键安装 */
@Composable
fun UpdateBanner() {
    val state by SelfUpdate.updateState.collectAsState()
    val context = LocalContext.current
    when (val s = state) {
        is SelfUpdate.UpdateState.Downloading -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "新版本下载中 ${percent(s.fraction)}%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 12.dp),
                )
                LinearProgressIndicator(
                    progress = { s.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                )
            }
        }
        is SelfUpdate.UpdateState.ReadyToInstall -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "v${s.release.version} 已就绪",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )
                Button(onClick = {
                    if (SelfUpdate.canInstall(context)) {
                        SelfUpdate.installApk(context, s.file)
                    } else {
                        context.startActivity(SelfUpdate.unknownSourcesIntent(context))
                    }
                }) { Text("安装") }
            }
        }
        is SelfUpdate.UpdateState.Failed -> {
            Text(
                "更新：${s.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        else -> {}
    }
}

private fun percent(f: Float): Int = (f * 100).toInt().coerceIn(0, 100)
