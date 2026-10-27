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
import com.integemjack.friday.data.ServerStore
import com.integemjack.friday.proto.FridayClient
import com.integemjack.friday.proto.MediaLoader
import com.integemjack.friday.ui.mediaCacheDir
import com.integemjack.friday.update.SelfUpdate
import com.integemjack.friday.update.UpdateContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/** 全局状态持有：协议客户端 + 服务器配置 + 媒体缓存 + 自动更新 */
class AppModel(private val app: Application) {
    /** 自己的作用域（不依赖 FridayApp，测试里也能直接造一个） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = FridayClient(scope, BuildConfig.VERSION_NAME)
    val store = ServerStore(app)
    val media = MediaLoader(client, mediaCacheDir(app))

    private var lastAttentionTick = 0L
    private var notifiedReadyVersion: String? = null

    fun start() {
        // 自动重连上次的服务器
        scope.launch {
            store.load()?.let { runCatching { client.connect(it) } }
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
