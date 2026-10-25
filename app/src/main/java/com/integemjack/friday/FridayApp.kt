package com.integemjack.friday

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.integemjack.friday.data.ServerStore
import com.integemjack.friday.proto.FridayClient
import com.integemjack.friday.update.SelfUpdate
import com.integemjack.friday.update.UpdateContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FridayApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var model: AppModel

    override fun onCreate() {
        super.onCreate()
        UpdateContext.cacheDir = cacheDir
        model = AppModel(this)
        createNotificationChannels()
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

/** 全局状态持有：协议客户端 + 服务器配置 + 自动更新 */
class AppModel(private val app: Application) {
    val client = FridayClient(app.applicationScope(), BuildConfig.VERSION_NAME)
    val store = ServerStore(app)

    private var lastAttentionTick = 0L
    private var notifiedReadyVersion: String? = null

    fun start() {
        // 自动重连上次的服务器
        app.applicationScope().launch {
            store.load()?.let { runCatching { client.connect(it) } }
        }
        // 自动更新：启动 8 秒后查一次，之后每 6 小时
        app.applicationScope().launch {
            kotlinx.coroutines.delay(8_000)
            while (true) {
                runCatching { SelfUpdate.checkForUpdate(BuildConfig.VERSION_NAME) }
                kotlinx.coroutines.delay(6 * 60 * 60 * 1000L)
            }
        }
        // 下载完成通知 + 待确认通知
        app.applicationScope().launch {
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

    private fun notifyUpdateReady(version: String) {
        val notification = NotificationCompat.Builder(app, FridayApp.CHANNEL_UPDATE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("新版本 v$version 已下载")
            .setContentText("点开应用完成安装")
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(1001, notification) }
    }

    private fun notifyAttention() {
        val notification = NotificationCompat.Builder(app, FridayApp.CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Friday 等你确认")
            .setContentText("远程会话里有调用在等你批准或回答")
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(1002, notification) }
    }
}

private fun Application.applicationScope(): CoroutineScope =
    (this as FridayApp).appScope
