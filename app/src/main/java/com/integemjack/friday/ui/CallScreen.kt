package com.integemjack.friday.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.integemjack.friday.call.AssistantCall
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * 跟助手的通话那一页（盖在整个界面上）：铺满的是助手的画面（它的光环），右上角是自己的预览，
 * 底下一排：静音、摄像头、翻转、外放、挂断。画面还没到时放一个会呼吸的光环占位。
 *
 * 返回键不挂断（误触太容易）：挂断只认红色按钮。
 */
@Composable
fun CallScreen(call: AssistantCall) {
    val state by call.state.collectAsState()
    if (!state.inCall) return
    BackHandler { /* 通话中返回键不挂断 */ }
    DarkSystemBars()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.phase) {
        while (state.phase == AssistantCall.Phase.Active) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0C12))
            // 吃掉点击，别点到下面的界面；结束了点任意处收起
            .clickable(enabled = true, indication = null, interactionSource = null) {
                if (state.phase == AssistantCall.Phase.Ended) call.dismiss()
            },
    ) {
        if (state.video && state.remoteVideo) {
            VideoView(call, remote = true, modifier = Modifier.fillMaxSize())
        } else {
            HaloPlaceholder(thinking = state.phase != AssistantCall.Phase.Active, modifier = Modifier.align(Alignment.Center))
        }

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.peerName.ifBlank { "助手" },
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (state.phase) {
                            AssistantCall.Phase.Dialing -> if (state.video) "正在呼叫（视频）…" else "正在呼叫…"
                            AssistantCall.Phase.Connecting -> "正在接通…"
                            AssistantCall.Phase.Active -> call.durationText(now).ifEmpty { "已接通" }
                            AssistantCall.Phase.Ended -> state.message ?: "通话结束"
                            AssistantCall.Phase.Idle -> ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.78f),
                    )
                    if (state.phase != AssistantCall.Phase.Ended && state.message != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(state.message.orEmpty(), style = MaterialTheme.typography.labelMedium, color = Color(0xFFFFC46B))
                    }
                }
                if (state.video && state.cameraOn && state.phase != AssistantCall.Phase.Ended) {
                    VideoView(
                        call,
                        remote = false,
                        mirror = state.frontCamera,
                        modifier = Modifier
                            .width(104.dp)
                            .height(184.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (state.phase == AssistantCall.Phase.Ended) {
                Text(
                    "点任意处关闭",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { call.dismiss() }
                        .padding(16.dp),
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CallButton(
                        icon = if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        label = if (state.muted) "已静音" else "静音",
                        active = state.muted,
                    ) { call.setMuted(!state.muted) }
                    if (state.video) {
                        CallButton(
                            icon = if (state.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                            label = if (state.cameraOn) "关摄像头" else "开摄像头",
                            active = !state.cameraOn,
                        ) { call.setCamera(!state.cameraOn) }
                        CallButton(icon = Icons.Filled.Cameraswitch, label = "翻转", active = false) { call.switchCamera() }
                    }
                    CallButton(
                        icon = if (state.speaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        label = if (state.speaker) "外放" else "听筒",
                        active = !state.speaker,
                    ) { call.setSpeaker(!state.speaker) }
                    CallButton(
                        icon = Icons.Filled.CallEnd,
                        label = "挂断",
                        active = false,
                        container = Color(0xFFE5484D),
                    ) { call.hangup() }
                }
            }
        }
    }
}

/**
 * 通话页是深色底：盖着的这段时间状态栏、导航条的图标换成浅色（浅色主题下本来是深色图标，压在深底上看不见），
 * 收起时还原
 */
@Composable
private fun DarkSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        var context = view.context
        while (context is android.content.ContextWrapper && context !is android.app.Activity) context = context.baseContext
        val window = (context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val status = controller?.isAppearanceLightStatusBars
        val navigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (controller != null) {
                status?.let { controller.isAppearanceLightStatusBars = it }
                navigation?.let { controller.isAppearanceLightNavigationBars = it }
            }
        }
    }
}

/** 一个 SurfaceViewRenderer：远端铺满（裁边）、本端预览（前摄像头镜像） */
@Composable
private fun VideoView(
    call: AssistantCall,
    remote: Boolean,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
) {
    val renderer = remember { mutableRendererHolder() }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).also { view ->
                view.init(call.egl.eglBaseContext, null)
                view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                view.setEnableHardwareScaler(true)
                if (!remote) view.setZOrderMediaOverlay(true)
                renderer.view = view
                if (remote) call.setRemoteSink(view) else call.setLocalSink(view)
            }
        },
        update = { view -> view.setMirror(mirror) },
    )
    DisposableEffect(Unit) {
        onDispose {
            if (remote) call.setRemoteSink(null) else call.setLocalSink(null)
            renderer.view?.release()
            renderer.view = null
        }
    }
}

private class RendererHolder {
    var view: SurfaceViewRenderer? = null
}

private fun mutableRendererHolder() = RendererHolder()

/** 画面还没到 / 语音通话时：深色底上一圈会呼吸的光环（同助手那头的「流光光环」意思一下） */
@Composable
private fun HaloPlaceholder(thinking: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "halo")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(if (thinking) 900 else 1800), RepeatMode.Reverse),
        label = "haloPulse",
    )
    Box(modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(200.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0x664C5BD6), Color(0x337A56DC), Color.Transparent),
                    )
                )
        )
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF4C5BD6), Color(0xFF7A56DC)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.SelfImprovement, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
    }
}

@Composable
private fun CallButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    container: Color = if (active) Color.White else Color.White.copy(alpha = 0.16f),
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(container)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (active) Color(0xFF0B0C12) else Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
    }
}
