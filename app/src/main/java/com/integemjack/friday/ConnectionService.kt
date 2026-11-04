package com.integemjack.friday

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.integemjack.friday.proto.ConnState
import com.integemjack.friday.schat.SchatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 后台保持连接（设置 › 后台连接，默认开）：一个前台服务 + 一条安静的常驻通知，让系统别把进程收掉，
 * SChat 信令和到远程 Friday 的工作通道一直连着——App 切到后台、锁屏以后，待确认的调用、助手的消息照样进来，
 * 切回来不用重新连。
 *
 * - 锁屏后 CPU 会睡，WebRTC 的心跳（ICE 保活、SCTP）就停了、连接被判死：开着时拿一把 PARTIAL_WAKE_LOCK
 *   （会多耗一点电，设置里说清楚了）。深度省电（Doze）还会断网：设置里另有「不限制电池优化」的入口
 * - 跟助手通话时把服务类型加上麦克风 / 摄像头：切到别的 App，通话照样有声音、有画面
 * - 服务类型是 specialUse（Android 14+）：保持一条到用户自己机器的长连接，没有现成的类型对得上；
 *   dataSync 在 Android 15 上一天只给 6 小时，不合适
 */
class ConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var inCall = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = (application as FridayApp).model
        if (intent?.action == ACTION_TURN_OFF) {
            model.settings.setKeepAlive(false)
            stop()
            return START_NOT_STICKY
        }
        if (!model.settings.keepAlive.value || !model.schat.hasSession()) {
            stop()
            return START_NOT_STICKY
        }
        inCall = model.call.state.value.inCall
        if (!goForeground(model)) {
            stop()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        if (watcher == null) {
            watcher = scope.launch {
                combine(
                    model.client.stateFlow.map { Triple(it.conn, it.lastServer?.hostname, waitingCount(it)) }.distinctUntilChanged(),
                    model.schat.state.map { it.phase }.distinctUntilChanged(),
                    model.call.state.map { it.inCall to it.video }.distinctUntilChanged(),
                ) { _, _, call -> call }.collect { (calling, _) ->
                    if (calling != inCall) {
                        inCall = calling
                        goForeground(model) // 换服务类型（加 / 去掉麦克风、摄像头）
                    } else {
                        notify(model)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun stop() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 进前台（或换类型）；Android 12+ 在后台起不来前台服务会抛异常：那就算了 */
    private fun goForeground(model: AppModel): Boolean = runCatching {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(model), serviceType(model))
        true
    }.getOrElse { false }

    private fun serviceType(model: AppModel): Int {
        var type = 0
        if (Build.VERSION.SDK_INT >= 34) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (inCall && Build.VERSION.SDK_INT >= 30) {
            if (granted(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (model.call.state.value.video && granted(Manifest.permission.CAMERA)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }
        return type
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // 通知开关 / POST_NOTIFICATIONS 关着时 notify 不抛，只是不显示
    private fun notify(model: AppModel) {
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(model)) }
    }

    private fun build(model: AppModel): android.app.Notification {
        val state = model.client.state.value
        val schat = model.schat.state.value
        val host = (state.conn as? ConnState.Connected)?.server?.hostname ?: state.lastServer?.hostname
        val waiting = waitingCount(state)
        val text = when {
            model.call.state.value.inCall -> "正在跟助手通话"
            schat.phase != SchatService.Phase.Online -> "SChat 没连上，网络回来就自动重连"
            state.conn is ConnState.Connected -> buildString {
                append("已连上 ").append(host ?: "远程 Friday")
                if (waiting > 0) append(" · $waiting 个等你确认")
            }
            state.conn is ConnState.Connecting -> "正在连 ${host ?: "远程 Friday"}…"
            else -> "连接断了，自动重连中…"
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val turnOff = PendingIntent.getService(
            this, 1,
            Intent(this, ConnectionService::class.java).setAction(ACTION_TURN_OFF),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, FridayApp.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Friday 后台保持连接")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "不再后台连接", turnOff)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** 锁屏后也别让 CPU 睡死（不然 WebRTC 的保活停了，连接被判断开）；服务停了就放掉 */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "friday:keep-connected").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
    }

    companion object {
        private const val NOTIFICATION_ID = 1003
        private const val ACTION_TURN_OFF = "com.integemjack.friday.KEEP_ALIVE_OFF"

        private fun waitingCount(state: com.integemjack.friday.proto.FridayState): Int {
            val runner = state.runner ?: return 0
            return (runner.pendingApproval + runner.pendingQuestion).toSet().size
        }

        /**
         * 按设置开 / 关服务。要在前台调（App 打开时、改了设置时）：Android 12+ 不让后台起前台服务
         */
        fun sync(context: Context) {
            val model = (context.applicationContext as FridayApp).model
            val intent = Intent(context, ConnectionService::class.java)
            if (model.settings.keepAlive.value && model.schat.hasSession()) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
            } else {
                context.stopService(intent)
            }
        }
    }
}
