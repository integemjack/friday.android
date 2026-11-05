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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SelfImprovement
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
import androidx.compose.material3.FilledTonalButton
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
fun SettingsScreen(onSwitchBot: () -> Unit = {}) {
    val openDrawer = LocalOpenDrawer.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        // 卡片之间 14dp，不同组之间再多一条空隙（下面手动加 Spacer）
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageHeader(
            title = "设置",
            subtitle = "账号、助手、更新与本机缓存",
            onOpenDrawer = openDrawer,
        )
        AccountCard(onSwitchBot)
        BackgroundCard()
        AssistantCard()
        UpdateCard()
        StorageCard()
        AboutCard()
        Spacer(Modifier.height(10.dp))
    }
}

/** 输入框统一样式：圆角 + 浅底 */
@Composable
internal fun FridayField(
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
    val offlineBytes = remember(revision) { dirSize(java.io.File(context.filesDir, "state-cache")) }

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
        Spacer(Modifier.height(14.dp))
        Text(
            "离线内容 ${formatBytes(offlineBytes).ifBlank { "0 B" }}：会话列表、助手的日志、最近看过的 ${com.integemjack.friday.data.StateCache.MAX_CHATS} 段会话",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            enabled = offlineBytes > 0,
            onClick = {
                java.io.File(context.filesDir, "state-cache").deleteRecursively()
                revision++
            },
        ) { Text("清掉离线内容") }
        Spacer(Modifier.height(6.dp))
        Text(
            "没网、远程那台没开的时候先显示这些；连上以后自动换成最新的。退出登录时会一起删掉。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 后台连接：前台服务 + 常驻通知，切到后台、锁屏以后工作通道还连着（待确认的调用、助手的消息照样进来）。
 * 深度省电（Doze）会断网：给一个「不限制电池优化」的入口；国产系统另有「自启动 / 后台运行」要自己去开。
 */
@Composable
private fun BackgroundCard() {
    val context = LocalContext.current
    val model = LocalAppModel.current
    val keepAlive by model.settings.keepAlive.collectAsState()
    val power = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    var revision by remember { mutableStateOf(0) }
    val unrestricted = remember(revision) { power.isIgnoringBatteryOptimizations(context.packageName) }
    // 从系统设置回来时重新看一眼
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    FridayCard {
        SectionTitle(
            icon = Icons.Filled.Sync,
            title = "后台保持连接",
            trailing = {
                androidx.compose.material3.Switch(
                    checked = keepAlive,
                    onCheckedChange = { on ->
                        model.settings.setKeepAlive(on)
                        com.integemjack.friday.ConnectionService.sync(context)
                    },
                )
            },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (keepAlive) {
                "切到后台、锁屏以后也一直连着远程 Friday：待确认的调用、助手的消息照样进来，通知栏有一条常驻的「后台保持连接」。会多耗一点电。"
            } else {
                "关着：切到后台一会儿系统就可能把连接断掉，回到 App 时再自动连上。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (keepAlive) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (unrestricted) LocalFridayExtra.current.success else MaterialTheme.colorScheme.tertiary, size = 7.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (unrestricted) "电池优化：不限制" else "电池优化：受限（深度省电时会断网）",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (!unrestricted) {
                    androidx.compose.material3.TextButton(onClick = { requestUnrestricted(context) }) { Text("去放开") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "小米、华为、OPPO、vivo 等还要在系统设置里给 Friday 打开「自启动」/「允许后台运行」，不然系统还是会把它收掉。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            SpeakRow()
            Spacer(Modifier.height(14.dp))
            IslandRow()
            Spacer(Modifier.height(14.dp))
            WakeRow()
        }
    }
}

/**
 * 语音唤醒（在「后台保持连接」开着时才有：一直听要靠那个前台服务）：默认关，开的时候先要麦克风权限。
 * 开着时写现在在不在听、最近一次听到了什么（没叫醒的话为什么）；小米上写锁屏弹通话页要开的两项权限，
 * Android 14+ 全屏通知没允许时给入口
 */
@Composable
private fun WakeRow() {
    val context = LocalContext.current
    val model = LocalAppModel.current
    val on by model.settings.wakeWord.collectAsState()
    val status by model.wake.status.collectAsState()
    val heard by model.wake.heard.collectAsState()
    val word = com.integemjack.friday.wake.WAKE_WORD
    fun enable(want: Boolean) {
        model.settings.setWakeWord(want)
        com.integemjack.friday.ConnectionService.sync(context)
    }
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            enable(true)
        } else {
            android.widget.Toast.makeText(context, "要用语音唤醒得给麦克风权限", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    fun hasMic() = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("语音唤醒", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(
            checked = on,
            onCheckedChange = { want ->
                if (want && !hasMic()) micPermission.launch(android.Manifest.permission.RECORD_AUDIO) else enable(want)
            },
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        if (on) {
            "说「$word」就跟助手语音通话，锁屏、熄屏也行（听到了手机先震一下）。先停一下再说：" +
                "句子中间的「$word」（今天是$word）不算，但一开口就说「${word}晚上……」也会叫醒。" +
                "麦克风一直开着（状态栏有麦克风标志），会多耗电。"
        } else {
            "关着：不听。打开后说「$word」就能叫助手（语音通话），不用碰手机。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!on) return

    // 从系统设置回来时重新看一眼权限
    var revision by remember { mutableStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    val phase = status.phase
    val good = phase == com.integemjack.friday.wake.WakeWord.Phase.Listening
    val bad = phase in setOf(
        com.integemjack.friday.wake.WakeWord.Phase.NeedsMic,
        com.integemjack.friday.wake.WakeWord.Phase.MicFailed,
        com.integemjack.friday.wake.WakeWord.Phase.Unsupported,
    )
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(
            when {
                good -> LocalFridayExtra.current.success
                bad -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.tertiary
            },
            size = 7.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when (phase) {
                com.integemjack.friday.wake.WakeWord.Phase.Listening -> "在听「$word」"
                com.integemjack.friday.wake.WakeWord.Phase.Starting -> "正在准备…"
                com.integemjack.friday.wake.WakeWord.Phase.Paused -> "暂停：${status.detail ?: ""}"
                com.integemjack.friday.wake.WakeWord.Phase.NeedsMic -> "没有麦克风权限"
                com.integemjack.friday.wake.WakeWord.Phase.NeedsService -> "后台没拿到麦克风：打开一次 Friday 就恢复"
                com.integemjack.friday.wake.WakeWord.Phase.MicFailed -> status.detail ?: "麦克风开不了"
                com.integemjack.friday.wake.WakeWord.Phase.Unsupported -> status.detail ?: "这台手机不支持"
                com.integemjack.friday.wake.WakeWord.Phase.Off -> "关着"
            },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        if (phase == com.integemjack.friday.wake.WakeWord.Phase.NeedsMic) {
            TextButton(onClick = { micPermission.launch(android.Manifest.permission.RECORD_AUDIO) }) { Text("给权限") }
        }
    }
    heard?.let { h ->
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(h.atMs))
        Spacer(Modifier.height(4.dp))
        Text(
            if (h.woke) "$time 听到「${h.keyword}」：叫醒了" else "$time 听到「${h.keyword}」：没叫醒（${h.reason ?: "不算"}）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // 锁屏上弹通话页要的权限
    val fullScreen = remember(revision) { com.integemjack.friday.wake.WakePermissions.canFullScreen(context) }
    if (!fullScreen) {
        Spacer(Modifier.height(8.dp))
        Text(
            "「全屏通知」没允许：锁屏时通话页弹不出来，只有一条通知。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = { com.integemjack.friday.wake.WakePermissions.openFullScreenSettings(context) }) { Text("去允许") }
    }
    if (com.integemjack.friday.wake.WakePermissions.isXiaomi()) {
        val locked = remember(revision) {
            com.integemjack.friday.wake.WakePermissions.miuiAllowed(context, com.integemjack.friday.wake.WakePermissions.MIUI_SHOW_WHEN_LOCKED)
        }
        val popup = remember(revision) {
            com.integemjack.friday.wake.WakePermissions.miuiAllowed(context, com.integemjack.friday.wake.WakePermissions.MIUI_BACKGROUND_START)
        }
        val missing = listOfNotNull(
            "锁屏显示".takeIf { locked != true },
            "后台弹出界面".takeIf { popup != true },
        )
        if (missing.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (locked == null || popup == null) {
                    "小米手机：锁屏、熄屏时要弹出通话页，得在系统的 Friday 权限里打开「锁屏显示」和「后台弹出界面」，再打开「自启动」免得被系统收掉。"
                } else {
                    "小米手机：「${missing.joinToString("」「")}」没开，锁屏、熄屏时通话页弹不出来（声音照样通）。也打开「自启动」免得被系统收掉。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (locked == null || popup == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { com.integemjack.friday.wake.WakePermissions.openMiuiPermissions(context) }) { Text("去打开") }
        }
    }
}

/** 灵动岛：「待确认」通知提升成实时活动（状态栏胶囊 / 小米超级岛），答完才消失；通知上直接答 */
@Composable
private fun IslandRow() {
    val model = LocalAppModel.current
    val island by model.settings.liveIsland.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("灵动岛", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(
            checked = island,
            onCheckedChange = { model.settings.setLiveIsland(it) },
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        if (island) {
            "在后台时有调用等你确认、Friday 在问你：状态栏的胶囊（小米是超级岛）写「在问你」「待确认」，答完才消失。" +
                "展开通知直接点选项、批准 / 拒绝，或者「回答…」写一句（先解锁）。要 Android 16 以上，系统不支持时就是普通通知。"
        } else {
            "关着：普通通知，点开就消失；通知上的按钮照样能直接答。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 后台语音播报（在「后台保持连接」开着时才有：进程活着才念得出来）。
 * 「试听」不管静音 / 勿扰，顺便把引擎起一次：不会说中文、没有引擎就在这里写出来
 */
@Composable
private fun SpeakRow() {
    val context = LocalContext.current
    val model = LocalAppModel.current
    val speak by model.settings.speakInBackground.collectAsState()
    val engine by model.speaker.engine.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("语音播报", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        androidx.compose.material3.TextButton(
            onClick = { model.speaker.speak(com.integemjack.friday.speech.BackgroundSpeaker.SAMPLE, force = true) },
        ) { Text("试听") }
        androidx.compose.material3.Switch(
            checked = speak,
            onCheckedChange = { model.settings.setSpeakInBackground(it) },
        )
    }
    Spacer(Modifier.height(4.dp))
    val problem = when (engine) {
        com.integemjack.friday.speech.BackgroundSpeaker.Engine.NoChinese ->
            "这台手机的文字转语音引擎不会说中文：去系统设置里装中文语音包，或者换一个引擎。"
        com.integemjack.friday.speech.BackgroundSpeaker.Engine.Unavailable ->
            "这台手机没有能用的文字转语音引擎，念不出来。"
        else -> null
    }
    Text(
        problem ?: if (speak) {
            "在后台时有调用等你确认、Friday 在问你，除了通知再念一句。静音或震动、开着勿扰、打电话时不念；音量跟媒体音量走。"
        } else {
            "关着：在后台只弹通知，不出声。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (problem != null) {
        androidx.compose.material3.TextButton(onClick = { openTtsSettings(context) }) { Text("打开文字转语音设置") }
    }
}

/** 系统的「文字转语音」设置页（不是公开 API，各家都认这个 action；打不开就退到系统设置首页） */
private fun openTtsSettings(context: android.content.Context) {
    runCatching { context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS")) }.onFailure {
        runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) }
    }
}

/** 请系统别对 Friday 做电池优化（弹系统的确认框；有的系统不给弹，就打开电池优化列表让用户自己找） */
@android.annotation.SuppressLint("BatteryLife")
private fun requestUnrestricted(context: android.content.Context) {
    val direct = android.content.Intent(
        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        android.net.Uri.parse("package:" + context.packageName),
    )
    runCatching { context.startActivity(direct) }.onFailure {
        runCatching {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

private fun dirSize(dir: java.io.File): Long =
    dir.listFiles()?.sumOf { if (it.isDirectory) dirSize(it) else it.length() } ?: 0L

/**
 * 账号：登录 / 选机器人都在单独的页面（LoginScreen / BotPickerScreen），这里只看现在的状态，
 * 能做的就两件：切换 bot、退出登录。
 */
@Composable
private fun AccountCard(onSwitchBot: () -> Unit) {
    val model = LocalAppModel.current
    val schatState by model.schat.state.collectAsState()
    val conn by model.client.stateFlow.collectAsState()
    val extra = LocalFridayExtra.current

    FridayCard {
        SectionTitle(
            icon = Icons.Filled.AccountCircle,
            title = "SChat 账号",
            trailing = {
                when (schatState.phase) {
                    SchatService.Phase.Online -> Chip("在线", color = extra.success)
                    SchatService.Phase.Failed -> Chip("没连上", color = MaterialTheme.colorScheme.error)
                    SchatService.Phase.LoggingIn, SchatService.Phase.Connecting ->
                        Chip("连接中", color = MaterialTheme.colorScheme.tertiary)
                    else -> Chip("未登录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "${schatState.username.ifBlank { "用户 ${schatState.userId}" }} · " +
                schatState.server.removePrefix("https://").removePrefix("http://"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (schatState.phase == SchatService.Phase.Failed) {
            (schatState.error ?: schatState.status.ifBlank { null })?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = { model.schat.resetFingerprintAndReconnect() }) {
                Text("服务器证书换过？点这里重记证书指纹")
            }
        }

        // 现在连的那台机器人 + 工作通道
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonogramBadge(schatState.lastBotName.ifBlank { "?" }, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    schatState.lastBotName.ifBlank { "还没选机器人" },
                    style = MaterialTheme.typography.titleSmall,
                )
                val (line, tint) = when (val c = conn.conn) {
                    is ConnState.Connected -> "已连上 · ${c.server.displayName} · ${c.server.hostname}" to extra.success
                    is ConnState.Connecting -> "正在连…" to MaterialTheme.colorScheme.tertiary
                    is ConnState.Failed -> c.message to MaterialTheme.colorScheme.error
                    else -> "工作通道没连" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(line, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 2)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onSwitchBot) { Text("切换 bot") }
            OutlinedButton(onClick = { model.logout() }) { Text("退出登录") }
        }
    }
}

/** 助手（同桌面版 设置 › 助手）：开关、名字、推什么、要不要先确认；看的是当前连上那台的 */
@Composable
private fun AssistantCard() {
    val model = LocalAppModel.current
    val state by model.client.stateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val assistant = state.assistant
    var name by remember(assistant?.config?.name) { mutableStateOf(assistant?.config?.name ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    fun patch(key: String, value: kotlinx.serialization.json.JsonPrimitive) {
        scope.launch {
            runCatching { model.client.updateAssistantConfig(key, value) }
                .onFailure { error = it.message }
        }
    }

    FridayCard {
        SectionTitle(
            icon = Icons.Filled.SelfImprovement,
            title = "助手",
            trailing = {
                androidx.compose.material3.Switch(
                    checked = assistant?.enabled == true,
                    enabled = state.connected && assistant != null,
                    onCheckedChange = { on ->
                        scope.launch {
                            runCatching { model.client.setAssistantEnabled(on) }.onFailure { error = it.message }
                        }
                    },
                )
            },
        )
        Spacer(Modifier.height(8.dp))
        when {
            !state.connected -> Text(
                "连上远程 Friday 之后，这里改的是那台机器上的助手（每台各一个）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            assistant == null -> Text(
                "这台上的助手还没读到：去「助手」那一页看一眼就会订回来。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                Text(
                    if (assistant.enabled) "开着：它会盯这台机器上各段会话的动静，有事在「助手」页里说。"
                    else "关着：只记状态板，不回话、也不通知你。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                FridayField(value = name, onValueChange = { name = it }, label = "助手的名字")
                if (name != assistant.config.name) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            patch("name", kotlinx.serialization.json.JsonPrimitive(name.trim()))
                        }) { Text("改名字") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "大脑",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Chip(assistant.brainEngineTitle ?: "跟新对话一样")
                }
                Spacer(Modifier.height(8.dp))
                Text("推什么", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("important" to "重要的事", "all" to "都推", "none" to "都不推").forEach { (value, label) ->
                        val on = assistant.config.pushRule == value
                        Text(
                            label,
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
                                )
                                .clickable { patch("pushRule", kotlinx.serialization.json.JsonPrimitive(value)) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (on) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("破坏性的操作先问我", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "删文件、改系统这类，助手自己也不直接做",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = assistant.config.confirmDestructive,
                        onCheckedChange = { on ->
                            patch("confirmDestructive", kotlinx.serialization.json.JsonPrimitive(on))
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("闲多久提醒我", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "一段会话超过这个时间没动静，助手会去看一眼",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${assistant.config.idleMinutes} 分钟",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (state.assistantChannels.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    HairLine()
                    Spacer(Modifier.height(8.dp))
                    state.assistantChannels.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(
                                if (c.status == "online") LocalFridayExtra.current.success
                                else MaterialTheme.colorScheme.outline,
                                size = 6.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${c.title} · ${c.botName.ifBlank { "没填机器人" }}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (c.status == "online") "在线" else c.statusText.ifBlank { c.status },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (c.owners.isNotEmpty()) {
                            Text(
                                "认的人：" + c.owners.joinToString("、"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
            "Friday Android：远程 Friday 的手机客户端（助手 / 会话 / 设置）。" +
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