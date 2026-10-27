package com.integemjack.friday.ui

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
import androidx.compose.ui.unit.dp
import com.integemjack.friday.BuildConfig
import com.integemjack.friday.proto.SshConfig
import com.integemjack.friday.update.SelfUpdate
import kotlinx.coroutines.launch

/** 设置：SSH 服务器、连接状态、版本与更新 */
@Composable
fun SettingsScreen() {
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ServerCard()
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
private fun ServerCard() {
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    val saved = remember { model.store.load() }
    var host by remember { mutableStateOf(saved?.host ?: "") }
    var port by remember { mutableStateOf((saved?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(saved?.user ?: "") }
    var password by remember { mutableStateOf(saved?.password ?: "") }
    var key by remember { mutableStateOf(saved?.privateKey ?: "") }
    var showKey by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("远程机器（SSH）", style = MaterialTheme.typography.titleMedium)
            when (val conn = state.conn) {
                is com.integemjack.friday.proto.ConnState.Connected ->
                    Text(
                        "已连接：${conn.server.displayName} · ${conn.server.user}@${conn.server.hostname}（${conn.server.platform}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                is com.integemjack.friday.proto.ConnState.Connecting ->
                    Text("连接中：${conn.message}", style = MaterialTheme.typography.bodySmall)
                is com.integemjack.friday.proto.ConnState.Failed ->
                    Text("连接失败：${conn.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> Text("未连接", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("主机") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = user, onValueChange = { user = it }, label = { Text("用户名") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it }, label = { Text("端口") },
                    modifier = Modifier.width(100.dp),
                )
            }
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("密码（可选）") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (showKey) {
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("私钥（OpenSSH 文本，可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            } else {
                TextButton(onClick = { showKey = true }) { Text("＋ 用私钥认证") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = host.isNotBlank() && user.isNotBlank() && (password.isNotBlank() || key.isNotBlank()) && !connecting,
                    onClick = {
                        connecting = true
                        error = null
                        val cfg = SshConfig(
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 22,
                            user = user.trim(),
                            password = password.takeIf { it.isNotBlank() },
                            privateKey = key.takeIf { it.isNotBlank() },
                        )
                        scope.launch {
                            model.store.save(cfg)
                            runCatching { model.client.connect(cfg) }
                                .onFailure { error = it.message ?: "连接失败" }
                            connecting = false
                        }
                    },
                ) {
                    if (connecting) {
                        CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("连接")
                }
                if (state.connected) {
                    OutlinedButton(onClick = { model.client.disconnect() }) { Text("断开") }
                }
            }
            Text(
                "连的那台机器上要装着 Friday（桌面端或服务端）；连上后就能看它的会话、发消息、批确认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
