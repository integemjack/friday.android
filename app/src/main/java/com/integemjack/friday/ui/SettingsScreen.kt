package com.integemjack.friday.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.integemjack.friday.BuildConfig
import com.integemjack.friday.proto.ConnState
import com.integemjack.friday.schat.SchatService
import com.integemjack.friday.update.SelfUpdate
import com.integemjack.friday.ui.theme.LocalFridayExtra
import kotlinx.coroutines.launch

/** 设置：SChat 账号与远程 Friday、连接状态、版本与更新 */
@Composable
fun SettingsScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
        SchatCard()
        UpdateCard()
        StorageCard()
        AboutCard()
        Spacer(Modifier.height(8.dp))
    }
}

/** 输入框统一样式：圆角 + 浅底 */
@Composable
private fun FridayField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: KeyboardOptions? = null,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = options ?: KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f),
        ),
    )
}

/** 媒体缓存：看过的图片 / 录音 / 视频存在本机，第二次不再走网络 */
@Composable
private fun StorageCard() {
    val context = LocalContext.current
    var revision by remember { mutableStateOf(0) }
    val mediaBytes = remember(revision) { dirSize(mediaCacheDir(context)) }
    val uploadBytes = remember(revision) { dirSize(java.io.File(context.cacheDir, "uploads")) }

    FridayCard {
        SectionTitle(icon = Icons.Filled.CleaningServices, title = "缓存")
        Spacer(Modifier.height(8.dp))
        Text(
            "看过的媒体 ${formatBytes(mediaBytes).ifBlank { "0 B" }}" +
                (if (uploadBytes > 0) " · 待传附件 ${formatBytes(uploadBytes)}" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            enabled = mediaBytes > 0 || uploadBytes > 0,
            onClick = {
                mediaCacheDir(context).listFiles()?.forEach { it.delete() }
                java.io.File(context.cacheDir, "uploads").listFiles()?.forEach { it.delete() }
                revision++
            },
        ) { Text("清掉缓存") }
        Spacer(Modifier.height(6.dp))
        Text(
            "清掉后再看同一张图会重新从远程机器拉一次，不影响远程的文件。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

    FridayCard {
        SectionTitle(
            icon = Icons.Filled.AccountCircle,
            title = "SChat 账号",
            trailing = {
                when (schatState.phase) {
                    SchatService.Phase.Online -> Chip("在线", color = LocalFridayExtra.current.success)
                    SchatService.Phase.Failed -> Chip("出错", color = MaterialTheme.colorScheme.error)
                    SchatService.Phase.LoggingIn, SchatService.Phase.Connecting ->
                        Chip("连接中", color = MaterialTheme.colorScheme.tertiary)
                    else -> Chip("未登录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        when (schatState.phase) {
            SchatService.Phase.NotLoggedIn ->
                Text(
                    "未登录：登录 SChat 账号后，就能呼叫好友里的远程 Friday",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            SchatService.Phase.LoggingIn ->
                Text(schatState.status.ifEmpty { "登录中…" }, style = MaterialTheme.typography.bodySmall)
            SchatService.Phase.Connecting ->
                Text(schatState.status.ifEmpty { "正在连聊天服务器…" }, style = MaterialTheme.typography.bodySmall)
            SchatService.Phase.Online ->
                Text(
                    "已连上 · ${schatState.username}（用户 ${schatState.userId}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalFridayExtra.current.success,
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
            Spacer(Modifier.height(4.dp))
            FridayField(server, { server = it }, "服务器（IP:端口，可带 https://）")
            FridayField(username, { username = it }, "账号（用户名 / 手机号）")
            FridayField(
                password, { password = it }, "密码",
                options = KeyboardOptions(keyboardType = KeyboardType.Password), password = true,
            )
            if (showAdvanced) {
                FridayField(
                    serverPassword, { serverPassword = it }, "服务器密码（私有服务器才要填）",
                    options = KeyboardOptions(keyboardType = KeyboardType.Password), password = true,
                )
            } else {
                TextButton(onClick = { showAdvanced = true }) { Text("＋ 私有服务器密码") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = server.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            model.schat.login(server.trim(), username.trim(), password, serverPassword.trim())
                            busy = false
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("登录")
                }
            }
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                "服务器：${schatState.server.ifEmpty { saved?.server.orEmpty() }} · 账号：${schatState.username.ifEmpty { saved?.username.orEmpty() }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = schatState.phase != SchatService.Phase.Online,
                    onClick = { model.schat.reconnectNow() },
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重连聊天服务器")
                }
                OutlinedButton(onClick = { model.schat.logout() }) { Text("退出登录") }
            }

            // 远程 Friday：好友里的机器人
            Spacer(Modifier.height(6.dp))
            SectionTitle(icon = Icons.Filled.SmartToy, title = "远程 Friday（SChat 好友里的机器人）")
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
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f)
                            )
                            .clickable { model.connectBot(bot.id, bot.name) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.SmartToy,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                bot.name.ifBlank { "机器人 ${bot.id}" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "#${bot.id}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) Chip("当前", color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { scope.launch { model.schat.loadBots() } }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("刷新列表")
                }
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
        Spacer(Modifier.height(6.dp))
        val connLabel = when (val c = conn.conn) {
            is ConnState.Connected ->
                "工作通道已连上：${c.server.displayName} · ${c.server.user}@${c.server.hostname}（${c.server.platform}）"
            is ConnState.Connecting -> "呼叫中：${c.message}"
            is ConnState.Failed -> "通道断开：${c.message}"
            else -> "工作通道未连接"
        }
        val connTint = when (conn.conn) {
            is ConnState.Connected -> LocalFridayExtra.current.success
            is ConnState.Failed -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(connTint.copy(alpha = 0.10f))
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.padding(top = 5.dp)) {
                Dot(connTint, size = 7.dp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                connLabel,
                style = MaterialTheme.typography.bodySmall,
                color = connTint,
                modifier = Modifier.weight(1f),
            )
        }
        if (conn.connected) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { model.client.disconnect() }) { Text("断开工作通道") }
        }
        Spacer(Modifier.height(6.dp))
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

@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val updateState by SelfUpdate.updateState.collectAsState()
    val scope = rememberCoroutineScope()

    FridayCard {
        SectionTitle(icon = Icons.Filled.SystemUpdate, title = "版本与更新")
        Spacer(Modifier.height(8.dp))
        Text(
            "当前版本 v${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        when (val s = updateState) {
            is SelfUpdate.UpdateState.Idle, is SelfUpdate.UpdateState.Checking -> {
                Text("检查更新中…", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch { SelfUpdate.checkForUpdate(BuildConfig.VERSION_NAME) } }) {
                    Text("立即检查")
                }
            }
            is SelfUpdate.UpdateState.UpToDate -> {
                Chip("已是最新", color = LocalFridayExtra.current.success)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch { SelfUpdate.checkForUpdate(BuildConfig.VERSION_NAME) } }) {
                    Text("再查一次")
                }
            }
            is SelfUpdate.UpdateState.Available -> {
                Text("发现新版本 v${s.release.version}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch { SelfUpdate.downloadApk(s.release) } }) { Text("下载") }
            }
            is SelfUpdate.UpdateState.Downloading -> {
                Text(
                    "下载中 ${(s.fraction * 100).toInt()}%（${s.read / 1024} / ${if (s.total > 0) "${s.total / 1024} KB" else "?"}）",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
            }
            is SelfUpdate.UpdateState.ReadyToInstall -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip("v${s.release.version} 已下载好", color = LocalFridayExtra.current.success)
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    if (SelfUpdate.canInstall(context)) SelfUpdate.installApk(context, s.file)
                    else context.startActivity(SelfUpdate.unknownSourcesIntent(context))
                }) { Text("安装更新") }
            }
            is SelfUpdate.UpdateState.Failed -> {
                Text(
                    "更新失败：${s.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch { SelfUpdate.checkForUpdate(BuildConfig.VERSION_NAME) } }) {
                    Text("重试")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "自动更新：打开应用和每 6 小时自动检查 GitHub 最新 Release，自动下载后一键安装。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutCard() {
    FridayCard {
        SectionTitle(icon = Icons.Filled.Info, title = "关于")
        Spacer(Modifier.height(8.dp))
        Text(
            "Friday Android：远程 Friday 的手机客户端（会话 / 活动总览 / 待确认）。" +
                "协议按 Friday/qt/docs/REMOTE.md 实现；对应桌面版 friday-desktop-work。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "github.com/${SelfUpdate.REPO_OWNER}/${SelfUpdate.REPO_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}