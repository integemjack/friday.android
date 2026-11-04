package com.integemjack.friday.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.integemjack.friday.schat.SchatBot
import com.integemjack.friday.schat.SchatService
import kotlinx.coroutines.launch

/**
 * 选一台远程 Friday（SChat 好友里的机器人）：登录完第一次进来必经这一页，设置里「切换 bot」也是它。
 * 点一台就记下、拨它的工作通道，回到主框架。
 *
 * [onBack] 不为空 = 从设置进来的（左上角有返回、底下不放「退出登录」）
 */
@Composable
fun BotPickerScreen(onPicked: () -> Unit, onBack: (() -> Unit)? = null) {
    val model = LocalAppModel.current
    val schat by model.schat.state.collectAsState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        if (schat.phase != SchatService.Phase.Online) return
        loading = true
        scope.launch {
            model.schat.loadBots()
            loading = false
        }
    }
    // 聊天服务器一连上就拉一次好友列表
    LaunchedEffect(schat.phase) { if (schat.phase == SchatService.Phase.Online) refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 盖在主框架上面时：空白处的点击别漏到下面去
            .clickable(interactionSource = null, indication = null) {}
            .systemBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = ::refresh, enabled = schat.phase == SchatService.Phase.Online && !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新列表")
            }
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("选一台远程 Friday", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "SChat 好友里的 Friday 机器人；点一台就连上它。" +
                    (if (schat.username.isNotBlank()) "（${schat.username}）" else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                schat.bots.isNotEmpty() -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(schat.bots, key = { it.id }) { bot ->
                        BotRow(bot, current = bot.id == schat.lastBotId) {
                            model.connectBot(bot.id, bot.name)
                            onPicked()
                        }
                    }
                }
                schat.phase == SchatService.Phase.Failed -> Hint(
                    icon = Icons.Filled.WifiOff,
                    title = "聊天服务器没连上",
                    text = schat.error ?: schat.status.ifBlank { "网络不通，或者服务器没开" },
                    action = "重连" to { model.schat.reconnectNow() },
                )
                schat.phase != SchatService.Phase.Online || loading -> Column(
                    Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (schat.phase == SchatService.Phase.Online) "在读好友里的机器人…" else schat.status.ifBlank { "正在连聊天服务器…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Hint(
                    icon = Icons.Filled.SmartToy,
                    title = "SChat 好友里还没有 Friday 机器人",
                    text = "在那台机器的 Friday 里打开 SChat 机器人（设置 › 助手 › 聊天工具），再在 SChat 里加它为好友，回来点刷新。",
                    action = "刷新" to ::refresh,
                )
            }
        }

        if (onBack == null) {
            TextButton(
                onClick = { model.logout() },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 12.dp),
            ) { Text("换个账号（退出登录）") }
        }
    }
}

@Composable
private fun BotRow(bot: SchatBot, current: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (current) scheme.primaryContainer.copy(alpha = 0.7f) else scheme.surfaceContainerLowest)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonogramBadge(bot.name, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(bot.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "#${bot.id}" + if (bot.botCalls) " · 能视频通话" else "",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (current) {
            Chip("当前", color = scheme.primary)
            Spacer(Modifier.width(6.dp))
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant)
    }
}

@Composable
private fun Hint(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, action: Pair<String, () -> Unit>) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmptyState(icon = icon, title = title, hint = text)
        FilledTonalButton(onClick = action.second) { Text(action.first, textAlign = TextAlign.Center) }
    }
}
