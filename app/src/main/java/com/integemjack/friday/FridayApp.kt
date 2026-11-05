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
import kotlinx.coroutines.flow.debounce
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
            override fun onActivityStarted(activity: android.app.Activity) {
                foregroundCount++
                if (foregroundCount == 1) model.onForeground(activity)
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                foregroundCount--
                // 切到后台：快照马上落盘（不等防抖），进程被收掉也不丢
                if (foregroundCount == 0) model.onBackground()
            }
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
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, "后台连接", NotificationManager.IMPORTANCE_LOW).apply {
                description = "后台保持连接时常驻的那条（不响、不震）"
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_UPDATE = "updates"
        const val CHANNEL_ATTENTION = "attention"
        const val CHANNEL_CONNECTION = "connection"
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

    /** 跟助手打视频 / 语音电话（打给连着的那台 Friday 的机器人） */
    val call = com.integemjack.friday.call.AssistantCall(app, schat, scope)

    /** 本地快照：断网 / 重开 App 时先把上次的摆出来（按 SChat 账号 + 机器人分开存） */
    val stateCache = com.integemjack.friday.data.StateCache(app)

    /** 输入框里打了一半的话（App 关掉再开还在） */
    val drafts = com.integemjack.friday.data.DraftStore(app)

    /** 这台手机上的开关（后台保持连接、语音播报） */
    val settings = com.integemjack.friday.data.AppSettings(app)

    /** 后台语音播报：在后台时有调用等你确认，跟通知一起念一句 */
    val speaker = com.integemjack.friday.speech.BackgroundSpeaker(app, inAssistantCall = { call.state.value.inCall })

    /** 「待确认」通知：灵动岛（实时活动）+ 通知上直接答 */
    val attention = com.integemjack.friday.notify.AttentionNotifier(
        app,
        island = { settings.liveIsland.value },
        pending = { com.integemjack.friday.speech.pendingApprovals(client.state.value.activities) },
        resolve = { eventID, approved, answers, reply -> client.resolveApproval(eventID, approved, answers, reply) },
        canNotify = { canNotify() },
        contentIntent = { assistantTabIntent() },
    )

    /** 通知上的按钮点了（广播里调）：发给那台，最多等 9 秒（广播要在 10 秒内收尾） */
    fun answerFromNotification(intent: Intent, done: () -> Unit) {
        scope.launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(9_000) { attention.handle(intent) }
            } finally {
                done()
            }
        }
    }

    /** 快照归哪个 SChat 账号（换账号不串） */
    private fun account(): Long = schat.state.value.userId.takeIf { it > 0 } ?: schatStore.load()?.userId ?: 0L

    init {
        client.cache = object : FridayClient.Cache {
            override fun load(botId: Long) = stateCache.load(account(), botId)?.toState()
            override fun loadChat(botId: Long, conversationId: String) = stateCache.loadChat(account(), botId, conversationId)
            override fun removeChat(botId: Long, conversationId: String) = stateCache.removeChat(account(), botId, conversationId)
        }
    }

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
        // 先把上次那台的快照摆出来：没网、远程那台没开时也看得到会话列表、助手的日志
        schatStore.load()?.botId?.takeIf { it > 0 }?.let { client.showCached(it) }
        persistSnapshots()
        watchNetwork()
        // 本地有 SChat 凭据就直接连
        schat.restore()
        // 登录 / 退出登录时（App 在前台）跟着开 / 关后台连接：不然刚登录的这次要等下次打开 App 才开
        scope.launch {
            schat.state.map { it.hasSession }.distinctUntilChanged().collect {
                if ((app as? FridayApp)?.inForeground == true) ConnectionService.sync(app)
            }
        }
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
            // 等着的那几项变了（别处答了、activity 比 attention 晚到）：通知还在就跟着换 / 收掉
            launch {
                client.stateFlow
                    .map { com.integemjack.friday.speech.pendingApprovals(it.activities) }
                    .distinctUntilChanged()
                    .collect { attention.onPendingChanged(it) }
            }
        }
    }

    // MARK: 本地快照

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var savedRefs: List<Any?> = emptyList()
    private var savedChat: com.integemjack.friday.proto.ChatState? = null

    /** 实时状态变了就存（1.5 秒防抖；只存实时的，摆着的快照本身不回写） */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun persistSnapshots() {
        io.launch {
            client.stateFlow.debounce(1_500).collect { persist(it) }
        }
    }

    @Synchronized
    private fun persist(state: com.integemjack.friday.proto.FridayState) {
        val botId = client.dataBotId
        val account = account()
        if (botId <= 0 || account <= 0) return
        // 只存连着时拿到的（离线摆着的快照、断线后留着的都是旧的，不用再写一遍）
        if (state.cachedAt != null || state.conn !is ConnState.Connected) return
        val server = (state.conn as ConnState.Connected).server
        val refs = listOf(
            state.summaries, state.engines, state.defaultEngineID, state.defaultTeamMode,
            state.directories, state.assistant, state.assistantEntries, server,
        )
        if (refs.size != savedRefs.size || refs.indices.any { refs[it] !== savedRefs[it] }) {
            runCatching { stateCache.save(account, botId, com.integemjack.friday.data.StateCache.Snapshot.of(state, server)) }
            savedRefs = refs
        }
        val chat = state.chat
        if (chat != null && chat !== savedChat && chat.runtime?.generating != true) {
            // 在出字的不存（每一帧都在变）；停下来的那一刻会再触发一次
            runCatching { stateCache.saveChat(account, botId, chat) }
            savedChat = chat
        }
    }

    /** 切到后台：马上存一次（不等防抖） */
    fun onBackground() {
        io.launch { persist(client.state.value) }
    }

    /** 回到前台：后台连接按设置开 / 关；连接断着就马上重连（不等退避） */
    fun onForeground(context: Context) {
        ConnectionService.sync(context)
        nudgeReconnect()
    }

    /** 退出 SChat 登录：凭据、快照、草稿都清掉，后台连接停掉 */
    fun logout() {
        schat.logout()
        client.forget()
        io.launch { runCatching { stateCache.clearAll() } }
        drafts.clearAll()
        savedRefs = emptyList()
        savedChat = null
        runCatching { app.stopService(Intent(app, ConnectionService::class.java)) }
    }

    // MARK: 网络

    /** 网络回来 / 换了网络：SChat 和工作通道马上重连（不等 10–120 秒的退避） */
    private fun watchNetwork() {
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    scope.launch {
                        // 刚切过来的网络 DNS / 路由还没好：稍等一下再连
                        kotlinx.coroutines.delay(800)
                        nudgeReconnect()
                    }
                }
            })
        }
    }

    private fun nudgeReconnect() {
        if (schat.hasSession() && !schat.busyOrOnline) schat.reconnectNow()
        // SChat 已经在线、只是工作通道断了：直接拨（SChat 还没上来的话，它上来时 start() 里的收集会拨）
        if (schat.state.value.phase == SchatService.Phase.Online) client.reconnectNow()
    }

    /** 点开通知直达的那一页（助手页，主页面） */
    private fun assistantTabIntent(): PendingIntent {
        val intent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_ASSISTANT)
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
            .setContentIntent(assistantTabIntent())
            .setAutoCancel(true)
            .build()
        post(1001, notification)
    }

    private fun notifyAttention() {
        val friday = app as? FridayApp ?: return
        // App 在前台：界面里已经有提醒，不再弹系统通知、不念
        if (friday.inForeground) return
        val approvals = com.integemjack.friday.speech.pendingApprovals(client.state.value.activities)
        // 通知（灵动岛 + 直接答）先发：提示音先响
        val notified = attention.onAttention()
        // 语音播报跟通知开关无关（各管各的）；静音、勿扰、打电话时它自己不念。
        // 通知有提示音就等它响完再念（提示音也占音频焦点，一起响的话开头被压低）
        if (settings.speakInBackground.value) {
            speaker.speak(
                com.integemjack.friday.speech.attentionSpeech(approvals),
                delayMs = if (notified && attentionChimes()) com.integemjack.friday.speech.BackgroundSpeaker.AFTER_CHIME_MS else 0,
            )
        }
    }

    /** 「待确认」这个通知渠道响不响（用户可能在系统设置里把它调成静默） */
    private fun attentionChimes(): Boolean {
        val channel = NotificationManagerCompat.from(app).getNotificationChannel(FridayApp.CHANNEL_ATTENTION) ?: return true
        return channel.importance >= NotificationManager.IMPORTANCE_DEFAULT && channel.sound != null
    }
}
