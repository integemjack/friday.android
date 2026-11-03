package com.integemjack.friday.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.integemjack.friday.BuildConfig
import com.integemjack.friday.proto.ConnState
import com.integemjack.friday.schat.SchatService
import com.integemjack.friday.update.SelfUpdate
import kotlinx.coroutines.launch

/** 设置：SChat 账号与远程 Friday、连接状态、版本与更新 */
@Composable
fun SettingsScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SchatCard()
        UpdateCard()
        StorageCard()
        AboutCard()
    }
}

/** 媒体缓存：看过的图片 / 录音 / 视频存在本机，第二次不再走网络 */
@Composable
private fun StorageCard() {
    val context = LocalContext.current
    var revision by remember { mutableStateOf(0) }
    val mediaBytes = remember(revision) { dirSize(mediaCacheDir(context)) }
    val uploadBytes = remember(revision) { dirSize(java.io.File(context.cacheDir, "uploads")) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("缓存", style = MaterialTheme.typography.titleMedium)
            Text(
                "看过的媒体 ${formatBytes(mediaBytes).ifBlank { "0 B" }}" +
                    (if (uploadBytes > 0) " · 待传附件 ${formatBytes(uploadBytes)}" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                enabled = mediaBytes > 0 || uploadBytes > 0,
                onClick = {
                    mediaCacheDir(context).listFiles()?.forEach { it.delete() }
                    java.io.File(context.cacheDir, "uploads").listFiles()?.forEach { it.delete() }
                    revision++
                },
            ) { Text("清掉缓存") }
            Text(
                "清掉后再看同一张图会重新从远程机器拉一次，不影响远程的文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun dirSize(dir: java.io.File): Long =
    dir.listFiles()?.sumOf { if (it.isDirectory) dirSize(it) else it.length() } ?: 0L

@Composable
private fun SchatCard() {
    val model = LocalAppModel.current
    val schatState by model.schat.state.collectAsState()
    val conn by model.client.stateFlow.collectAsState()
    val saved = remember { model.schatStore.load() }
    var server by remember { mutableStateOf(saved?.server?.ifEmpty { null } ?: SchatService.DEFAULT_SERVER) }
    var username by remember { mutableStateOf(saved?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var serverPassword by remember { mutableStateOf(saved?.serverPassword.orEmpty()) }
    var showAdvanced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SChat 账号", style = MaterialTheme.typography.titleMedium)
            // 信令状态
            when (val phase = schatState.phase) {
                SchatService.Phase.NotLoggedIn ->
                    Text("未登录：登录 SChat 账号后，就能呼叫好友里的远程 Friday", style = MaterialTheme.typography.bodySmall)
                SchatService.Phase.LoggingIn ->
                    Text(schatState.status.ifEmpty { "登录中…" }, style = MaterialTheme.typography.bodySmall)
                SchatService.Phase.Connecting ->
                    Text(schatState.status.ifEmpty { "正在连聊天服务器…" }, style = MaterialTheme.typography.bodySmall)
                SchatService.Phase.Online ->
                    Text(
                        "已连上 · ${schatState.username}（用户 ${schatState.userId}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                SchatService.Phase.Failed ->
                    Text(
                        "SChat ${schatState.status}".trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
            }
            schatState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (!schatState.hasSession) {
                OutlinedTextField(
                    value = server, onValueChange = { server = it },
                    label = { Text("服务器（IP:端口，可带 https://）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("账号（用户名 / 手机号）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (showAdvanced) {
                    OutlinedTextField(
                        value = serverPassword, onValueChange = { serverPassword = it },
                        label = { Text("服务器密码（私有服务器才要填）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                } else {
                    TextButton(onClick = { showAdvanced = true }) { Text("＋ 私有服务器密码") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = server.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                model.schat.login(server.trim(), username.trim(), password, serverPassword.trim())
                                busy = false
                            }
                        },
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("登录")
                    }
                }
            } else {
                Text(
                    "服务器：${schatState.server.ifEmpty { saved?.server.orEmpty() }} · 账号：${schatState.username.ifEmpty { saved?.username.orEmpty() }}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = schatState.phase != SchatService.Phase.Online,
                        onClick = { model.schat.reconnectNow() },
                    ) { Text("重连聊天服务器") }
                    OutlinedButton(onClick = { model.schat.logout() }) { Text("退出登录") }
                }

                // 远程 Friday：好友里的机器人
                Text("远程 Friday（SChat 好友里的机器人）", style = MaterialTheme.typography.titleSmall)
                when {
                    schatState.bots.isEmpty() ->
                        Text(
                            "还没有机器人。在那台电脑上把 Friday 加到 SChat 里、并和这个账号成为好友，再回来刷新。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    else -> schatState.bots.forEach { bot ->
                        val selected = bot.id == schatState.lastBotId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { model.connectBot(bot.id, bot.name) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(if (selected) "●" else "○", color = MaterialTheme.colorScheme.primary)
                            Text(bot.name.ifBlank { "机器人 ${bot.id}" }, style = MaterialTheme.typography.bodyMedium)
                            Text("#${bot.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { scope.launch { model.schat.loadBots() } }) { Text("刷新列表") }
                    val botId = schatState.lastBotId
                    if (botId > 0) {
                        OutlinedButton(
                            onClick = {
                                model.connectBot(
                                    botId,
                                    schatState.bots.firstOrNull { it.id == botId }?.name ?: schatState.lastBotName,
                                )
                            },
                        ) { Text("重新拨号") }
                    }
                }
            }

            // 工作通道状态
            val connLabel = when (val c = conn.conn) {
                is ConnState.Connected ->
                    "工作通道已连上：${c.server.displayName} · ${c.server.user}@${c.server.hostname}（${c.server.platform}）"
                is ConnState.Connecting -> "呼叫中：${c.message}"
                is ConnState.Failed -> "通道断开：${c.message}"
                else -> "工作通道未连接"
            }
            Text(
                connLabel,
                style = MaterialTheme.typography.bodySmall,
                color = when (conn.conn) {
                    is ConnState.Connected -> MaterialTheme.colorScheme.primary
                    is ConnState.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (conn.connected) {
                OutlinedButton(onClick = { model.client.disconnect() }) { Text("断开工作通道") }
            }
            Text(
                "和工作台一样走 SChat 的 WebRTC 工作通道（P2P + TURN 中继）：要连的那台机器上装着 Friday 桌面端，" +
                    "并在 SChat 里把它的机器人加为好友。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            schatState.phase.let { phase ->
                if (phase == SchatService.Phase.Failed && saved != null) {
                    TextButton(onClick = { model.schat.resetFingerprintAndReconnect() }) {
                        Text("服务器证书换过？点这里重记证书指纹")
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val updateState by SelfUpdate.updateState.collectAsState()
    val scope = rememberCoroutineScope()

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("版本与更新", style = MaterialTheme.typography.titleMedium)
            Text("当前版本 v${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）", style = MaterialTheme.typography.bodySmall)
            when (val s = updateState) {
                is SelfUpdate.UpdateState.Idle, is SelfUpdate.UpdateState.Checking -> {
                    Text("检查更新中…", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { scope.launch { SelfUpdate.checkForUpdate(BuildConfig.VERSION_NAME) } }) {
                        Text("立即检查")
                    }
                }
                is SelfUpdate.UpdateState.UpToDate -> {
                    Text("已是最新版本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = { scope.launch { SelfUpdate.checkForUpdate(BuildConfig.VERSION_NAME) } }) {
                        Text("再查一次")
                    }
                }
                is SelfUpdate.UpdateState.Available -> {
                    Text("发现新版本 v${s.release.version}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { scope.launch { SelfUpdate.downloadApk(s.release) } }) { Text("下载") }
                }
                is SelfUpdate.UpdateState.Downloading -> {
                    Text("下载中 ${(s.fraction * 100).toInt()}%（${s.read / 1024} / ${if (s.total > 0) "${s.total / 1024} KB" else "?"}）", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
                }
                is SelfUpdate.UpdateState.ReadyToInstall -> {
                    Text("v${s.release.version} 已下载好", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        if (SelfUpdate.canInstall(context)) SelfUpdate.installApk(context, s.file)
                        else context.startActivity(SelfUpdate.unknownSourcesIntent(context))
                    }) { Text("安装更新") }
                }
                is SelfUpdate.UpdateState.Failed -> {
                    Text("更新失败：${s.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { scope.launch { SelfUpdate.checkForUpdate(BuildConfig.VERSION_NAME) } }) { Text("重试") }
                }
            }
            Text(
                "自动更新：打开应用和每 6 小时自动检查 GitHub 最新 Release，自动下载后一键安装。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutCard() {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("关于", style = MaterialTheme.typography.titleMedium)
            Text(
                "Friday Android：远程 Friday 的手机客户端（会话 / 活动总览 / 待确认）。" +
                    "协议按 Friday/qt/docs/REMOTE.md 实现；对应桌面版 friday-desktop-work。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "github.com/${SelfUpdate.REPO_OWNER}/${SelfUpdate.REPO_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
