package com.integemjack.friday

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.integemjack.friday.data.SchatStore
import com.integemjack.friday.proto.ConnState
import com.integemjack.friday.proto.FridayClient
import com.integemjack.friday.proto.MediaLoader
import com.integemjack.friday.proto.RemoteTarget
import com.integemjack.friday.proto.WorkPeer
import com.integemjack.friday.schat.SchatService
import com.integemjack.friday.ui.mediaCacheDir
import com.integemjack.friday.update.SelfUpdate
import com.integemjack.friday.update.UpdateContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class FridayApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var model: AppModel

    /** 在前台的 Activity 数：>0 时不弹系统通知（界面里已经有提醒了） */
    @Volatile
    var foregroundCount = 0
        private set

    val inForeground: Boolean get() = foregroundCount > 0

    override fun onCreate() {
        super.onCreate()
        UpdateContext.cacheDir = cacheDir
        model = AppModel(this)
        createNotificationChannels()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) { foregroundCount++ }
            override fun onActivityStopped(activity: android.app.Activity) { foregroundCount-- }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        model.start()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATE, "应用更新", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "新版本下载完成等提醒"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ATTENTION, "待确认", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "远程会话里有等你确认的调用"
            }
        )
    }

    companion object {
        const val CHANNEL_UPDATE = "updates"
        const val CHANNEL_ATTENTION = "attention"
    }
}

/** 全局状态持有：SChat 会话 + 协议客户端 + 媒体缓存 + 自动更新 */
class AppModel(private val app: Application) {
    /** 自己的作用域（不依赖 FridayApp，测试里也能直接造一个） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** SChat 账号 / 上次连的机器人（密码和 token 都是加密存的） */
    val schatStore = SchatStore(app)

    /** SChat 那一头：登录、schat-ws 信令、机器人列表、ICE */
    val schat = SchatService(schatStore, scope, deviceName = deviceName())

    val client = FridayClient(scope, BuildConfig.VERSION_NAME, schat)
    val media = MediaLoader(client, mediaCacheDir(app))

    private var lastAttentionTick = 0L
    private var notifiedReadyVersion: String? = null

    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val name = listOf(manufacturer, model).filter { it.isNotEmpty() }.joinToString(" ")
        return name.ifEmpty { "Android" }
    }

    /** 连一台远程 Friday（走 SChat 信令拨它的工作通道） */
    fun connectBot(botId: Long, botName: String) {
        schat.selectBot(botId, botName)
        scope.launch { runCatching { client.connect(RemoteTarget(botId, botName)) } }
    }

    fun start() {
        // JVM 单测（Robolectric）里没有 org.webrtc 的 so：这里失败不影响其他功能，真机才用得上
        runCatching { WorkPeer.initialize(app) }
        // 本地有 SChat 凭据就直接连
        schat.restore()
        // SChat 一连上，就把上次连的那台远程 Friday 也拨回来
        scope.launch {
            schat.state.map { it.phase }.distinctUntilChanged().collect { phase ->
                if (phase != SchatService.Phase.Online) return@collect
                if (client.state.value.connected || client.state.value.conn is ConnState.Connecting) return@collect
                val botId = schatStore.load()?.botId ?: 0L
                if (botId <= 0) return@collect
                val live = schat.state.value
                val name = live.bots.firstOrNull { it.id == botId }?.name
                    ?: live.lastBotName.ifEmpty { "机器人 $botId" }
                runCatching { client.connect(RemoteTarget(botId, name)) }
            }
        }
        // 自动更新：启动 8 秒后查一次，之后每 6 小时
        scope.launch {
            kotlinx.coroutines.delay(8_000)
            while (true) {
                runCatching { SelfUpdate.checkForUpdate(BuildConfig.VERSION_NAME) }
                kotlinx.coroutines.delay(6 * 60 * 60 * 1000L)
            }
        }
        // 下载完成通知 + 待确认通知
        scope.launch {
            launch {
                SelfUpdate.updateState.collect { state ->
                    if (state is SelfUpdate.UpdateState.ReadyToInstall && notifiedReadyVersion != state.release.tag) {
                        notifiedReadyVersion = state.release.tag
                        notifyUpdateReady(state.release.version)
                    }
                }
            }
            launch {
                client.stateFlow.collect { s ->
                    if (s.attentionTick > lastAttentionTick) {
                        lastAttentionTick = s.attentionTick
                        notifyAttention()
                    }
                }
            }
        }
    }

    /** 点开通知直达的那一页（活动页） */
    private fun activityTabIntent(): PendingIntent {
        val intent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_ACTIVITY)
        }
        return PendingIntent.getActivity(
            app, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Android 13+ 要 POST_NOTIFICATIONS；用户关了通知就不弹（也不该抛 SecurityException） */
    private fun canNotify(): Boolean {
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return true
    }

    /** 发一条通知。canNotify() 已经查过通知开关和 POST_NOTIFICATIONS，lint 看不穿这层封装 */
    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(app).notify(id, notification) }
    }

    private fun notifyUpdateReady(version: String) {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(app, FridayApp.CHANNEL_UPDATE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("新版本 v$version 已下载")
            .setContentText("点开应用完成安装")
            .setContentIntent(activityTabIntent())
            .setAutoCancel(true)
            .build()
        post(1001, notification)
    }

    private fun notifyAttention() {
        val friday = app as? FridayApp ?: return
        // App 在前台：界面里已经有提醒，不再弹系统通知
        if (friday.inForeground) return
        if (!canNotify()) return
        val approvals = client.state.value.activities.flatMap { activity ->
            activity.approvals.map { activity.title to it }
        }
        val (title, text) = when {
            approvals.isEmpty() -> "Friday 等你确认" to "远程会话里有调用在等你批准或回答"
            approvals.size == 1 -> {
                val (session, approval) = approvals[0]
                val head = if (approval.isQuestion) "Friday 在问你" else "Friday 等你确认"
                val what = approval.title.ifBlank { approval.tool }
                head to "${session.ifBlank { "会话" }}：$what"
            }
            else -> "Friday 等你确认（${approvals.size} 项）" to
                approvals.joinToString("、") { (_, approval) -> approval.title.ifBlank { approval.tool } }.take(120)
        }
        val notification = NotificationCompat.Builder(app, FridayApp.CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(activityTabIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        post(1002, notification)
    }
}
