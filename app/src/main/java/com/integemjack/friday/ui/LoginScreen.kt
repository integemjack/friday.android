package com.integemjack.friday.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.integemjack.friday.schat.SchatService
import com.integemjack.friday.ui.theme.LocalFridayExtra
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 登录页：没登录时 App 一打开就是这一页（AppRoot 挡在主框架前面）。
 *
 * - 这台手机装着能用的 SChat → 一个大按钮「通过 SChat 登录」：开一次扫码登录会话，把 token 经
 *   `schat://login/<token>` 交给 SChat，在 SChat 里点确认就登上了
 * - 没装（或装的 SChat 还不认这个链接）→ 一个二维码 `SCSH:1:login:<token>`：用另一台手机上的 SChat 扫一扫、确认
 * - 两条都是同一套 login-sessions（Secret-Chat docs/qr_login_plan.md）：确认页显示是哪台设备在登录，
 *   凭据在这边领取时才签发
 * - 最下面折着「其他方式」：改聊天服务器（私有服务器）、账号密码登录（老服务端没有扫码登录时也走这条）
 *
 * 登录成功（三条路之一）→ AppRoot 换成选机器人那一页。
 */
@Composable
fun LoginScreen() {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val schat by model.schat.state.collectAsState()
    val qr by model.schat.qrLogin.collectAsState()
    val scope = rememberCoroutineScope()

    var server by rememberSaveable { mutableStateOf(schat.server.ifBlank { SchatService.DEFAULT_SERVER }) }
    var serverPassword by rememberSaveable { mutableStateOf(schat.serverPassword) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var showQrAnyway by rememberSaveable { mutableStateOf(false) }
    // 从系统设置 / 应用商店回来（刚装上 SChat）时重新看一眼
    var appRevision by remember { mutableStateOf(0) }
    val app = remember(appRevision) { schatApp(context) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) appRevision++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    // 一进来就备好一次扫码会话（改了服务器停 0.6 秒再重开）；离开这页就作废
    LaunchedEffect(server, serverPassword) {
        delay(600)
        model.schat.startQrLogin(server, serverPassword)
    }
    DisposableEffect(Unit) { onDispose { model.schat.cancelQrLogin() } }
    // 服务器没有扫码登录：直接把账号密码那块打开
    LaunchedEffect(qr) {
        if ((qr as? SchatService.QrLogin.Failed)?.unsupported == true) moreOpen = true
    }

    val waiting = qr as? SchatService.QrLogin.Waiting
    val restart = { model.schat.startQrLogin(server, serverPassword) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Brand()
            Spacer(Modifier.height(28.dp))

            val useApp = app == SchatApp.Ready && !showQrAnyway
            if (useApp) {
                SchatButton(
                    qr = qr,
                    onClick = {
                        val w = waiting
                        if (w == null) {
                            restart()
                        } else {
                            runCatching { context.startActivity(schatLoginIntent(w.token, w.server)) }
                                .onFailure { Toast.makeText(context, "打不开 SChat：${it.message}", Toast.LENGTH_SHORT).show() }
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { showQrAnyway = true }) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("用另一台手机扫码登录")
                }
            } else {
                QrPanel(qr = qr, onRefresh = restart)
                if (app == SchatApp.Ready) {
                    TextButton(onClick = { showQrAnyway = false }) {
                        Icon(Icons.Filled.Smartphone, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("用这台手机上的 SChat 登录")
                    }
                } else if (app == SchatApp.Outdated) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "这台手机上的 SChat 还不能一键登录（升级 SChat 后，这里会变成「通过 SChat 登录」）。" +
                            "现在可以用另一台手机上的 SChat 扫这个码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            MoreOptions(
                open = moreOpen,
                onToggle = { moreOpen = !moreOpen },
                server = server,
                onServer = { server = it },
                serverPassword = serverPassword,
                onServerPassword = { serverPassword = it },
                schat = schat,
                onLogin = { user, password ->
                    scope.launch { model.schat.login(server.trim(), user.trim(), password, serverPassword.trim()) }
                },
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Friday · v${com.integemjack.friday.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun Brand() {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    Box(
        Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(scheme.primary, if (dark) Color(0xFFD3C2FF) else Color(0xFF7A56DC)))),
        contentAlignment = Alignment.Center,
    ) {
        Text("F", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onPrimary)
    }
    Spacer(Modifier.height(14.dp))
    Text("登录 Friday", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(6.dp))
    Text(
        "用 SChat 账号登录，再选一台你自己的远程 Friday 连上",
        style = MaterialTheme.typography.bodyMedium,
        color = scheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** 这台手机上有 SChat：一个大按钮，会话备好了就能点（点了跳到 SChat 去确认） */
@Composable
private fun SchatButton(qr: SchatService.QrLogin, onClick: () -> Unit) {
    val waiting = qr as? SchatService.QrLogin.Waiting
    Button(
        onClick = onClick,
        enabled = waiting != null || qr is SchatService.QrLogin.Expired || qr is SchatService.QrLogin.Denied ||
            qr is SchatService.QrLogin.Failed,
        contentPadding = PaddingValues(vertical = 14.dp),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp),
    ) {
        if (qr is SchatService.QrLogin.Starting || qr is SchatService.QrLogin.Idle) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(10.dp))
            Text("准备中…", style = MaterialTheme.typography.titleSmall)
        } else {
            Icon(Icons.Filled.Smartphone, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                when (qr) {
                    is SchatService.QrLogin.Expired, is SchatService.QrLogin.Denied, is SchatService.QrLogin.Failed -> "重新通过 SChat 登录"
                    else -> "通过 SChat 登录"
                },
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    QrStatus(qr, scannedText = "SChat 已收到，在 SChat 里点「确认登录」", waitingText = "会跳到 SChat，在那边点「确认登录」就登上了")
}

/** 没有能用的 SChat：二维码，另一台手机上的 SChat 扫一扫 */
@Composable
internal fun QrPanel(qr: SchatService.QrLogin, onRefresh: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val waiting = qr as? SchatService.QrLogin.Waiting
    Box(
        Modifier
            .size(236.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            waiting != null -> {
                QrCode(
                    waiting.payload,
                    Modifier
                        .size(204.dp)
                        .alpha(if (waiting.scanned) 0.15f else 1f),
                )
                if (waiting.scanned) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF1F7A46), modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(6.dp))
                        Text("已扫描", style = MaterialTheme.typography.titleSmall, color = Color(0xFF1B1B21))
                    }
                }
            }
            qr is SchatService.QrLogin.Expired || qr is SchatService.QrLogin.Denied || qr is SchatService.QrLogin.Failed ->
                Column(
                    Modifier.clickable(onClick = onRefresh).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color(0xFF4C5BD6), modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("重新生成二维码", style = MaterialTheme.typography.titleSmall, color = Color(0xFF4C5BD6))
                }
            else -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
        }
    }
    Spacer(Modifier.height(14.dp))
    Text(
        "打开手机上的 SChat → 扫一扫，扫这个码",
        style = MaterialTheme.typography.titleSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(4.dp))
    QrStatus(qr, scannedText = "已扫描，请在手机上点「确认登录」", waitingText = null)
}

/** 码 / 按钮下面那一行：等扫 / 已扫等确认 / 过期 / 取消了 / 出错 */
@Composable
private fun QrStatus(qr: SchatService.QrLogin, scannedText: String, waitingText: String?) {
    val scheme = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(qr) {
        while (qr is SchatService.QrLogin.Waiting) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val (text, color) = when (qr) {
        is SchatService.QrLogin.Waiting -> if (qr.scanned) {
            scannedText to LocalFridayExtra.current.success
        } else {
            val left = ((qr.expiresAt - now) / 1000).coerceAtLeast(0)
            val clock = "%d:%02d".format(left / 60, left % 60)
            (listOfNotNull(waitingText, "$clock 后过期").joinToString(" · ")) to scheme.onSurfaceVariant
        }
        SchatService.QrLogin.Expired -> "二维码过期了，点一下重新生成" to scheme.tertiary
        SchatService.QrLogin.Denied -> "已在手机上取消了这次登录" to scheme.tertiary
        is SchatService.QrLogin.Failed -> qr.message to scheme.error
        else -> "正在生成…" to scheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, textAlign = TextAlign.Center)
}

/** 折着的「其他方式」：聊天服务器（私有服务器才改）+ 账号密码登录 */
@Composable
private fun MoreOptions(
    open: Boolean,
    onToggle: () -> Unit,
    server: String,
    onServer: (String) -> Unit,
    serverPassword: String,
    onServerPassword: (String) -> Unit,
    schat: SchatService.State,
    onLogin: (String, String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var username by rememberSaveable { mutableStateOf(schat.username) }
    var password by remember { mutableStateOf("") }
    val busy = schat.phase == SchatService.Phase.LoggingIn
    Column(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerLow),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("其他方式", style = MaterialTheme.typography.titleSmall)
                Text(
                    "账号密码登录 · 聊天服务器 ${server.removePrefix("https://").removePrefix("http://")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = scheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FridayField(server, onServer, "聊天服务器（IP:端口，可带 https://）")
                FridayField(
                    serverPassword, onServerPassword, "服务器密码（私有服务器才要填）",
                    options = KeyboardOptions(keyboardType = KeyboardType.Password), password = true,
                )
                Spacer(Modifier.height(6.dp))
                FridayField(username, { username = it }, "账号（用户名 / 手机号）")
                FridayField(
                    password, { password = it }, "密码",
                    options = KeyboardOptions(keyboardType = KeyboardType.Password), password = true,
                )
                schat.error?.takeIf { schat.phase == SchatService.Phase.Failed }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                }
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(
                    enabled = server.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && !busy,
                    onClick = { onLogin(username, password) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (busy) "登录中…" else "用账号密码登录")
                }
            }
        }
    }
}
