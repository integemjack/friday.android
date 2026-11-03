package com.integemjack.friday.schat

import com.integemjack.friday.data.SchatSession
import com.integemjack.friday.data.SchatStore
import com.integemjack.friday.proto.WorkSignaling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SChat 那一头：账号会话、schat-ws 连接（信令通道）、机器人列表、ICE 服务器。
 *
 * REMOTE.md §2「WebRTC 工作通道」：手机登录 SChat（主人账号）→ schat-ws 上呼机器人
 * `p2p_request{callType:"work"}` → DataChannel `friday-work` 上跑 JSON-RPC。
 * 和桌面端 friday-desktop-work 0.3 的做法一致（`protocol/schat/SchatService.ts`）。
 */
class SchatService(
    private val store: SchatStore,
    private val scope: CoroutineScope,
    private val deviceName: String,
) {

    enum class Phase { NotLoggedIn, LoggingIn, Connecting, Online, Failed }

    data class State(
        val phase: Phase = Phase.NotLoggedIn,
        val status: String = "",
        val error: String? = null,
        val userId: Long = 0,
        val bots: List<SchatBot> = emptyList(),
        val server: String = DEFAULT_SERVER,
        val username: String = "",
        val serverPassword: String = "",
        /** 有登录凭据（能直接连，不用重新输密码） */
        val hasSession: Boolean = false,
        /** 上次连的机器 */
        val lastBotId: Long = 0,
        val lastBotName: String = "",
    ) {
        val online: Boolean get() = phase == Phase.Online
    }

    /** 一条工作通道（一台远程 Friday）在信令层要收的帧 */
    interface WorkRoute {
        val botId: Long

        /** 这条通道现在这次拨号的 requestId（空闲为空） */
        fun currentRequestId(): String

        /** 命中这条通道的 p2p_* 帧 */
        fun onWorkFrame(event: String, data: JsonObject)

        /** schat-ws 断了（信令没了，通道跟着断） */
        fun onSignalingOffline(reason: String)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var session: SchatSession? = null
    private var client: SchatWs? = null
    private var wantedOnline = false
    private var retryIndex = 0
    private var retryJob: Job? = null
    private var connectJob: Job? = null
    private var reloginTried = false
    private var handlingKick = false
    private var iceCache: Pair<List<IceServerConfig>, Long>? = null
    private val routes = CopyOnWriteArrayList<WorkRoute>()

    // MARK: 启动 / 恢复

    /** 启动时读本地会话：有就拿去连，没有就只把表单预填上 */
    fun restore() {
        val stored = store.load() ?: return
        _state.value = _state.value.copy(
            server = stored.server,
            username = stored.username,
            serverPassword = stored.serverPassword,
            userId = stored.userId,
            lastBotId = stored.botId,
            lastBotName = stored.botName,
        )
        if (stored.token.isBlank()) return
        session = stored
        _state.value = _state.value.copy(hasSession = true)
        wantedOnline = true
        connectWs()
    }

    // MARK: 登录 / 退出

    suspend fun login(serverRaw: String, username: String, password: String, serverPassword: String) {
        val server = normalizeServer(serverRaw)
        if (server.isEmpty() || username.isBlank() || password.isEmpty()) {
            _state.value = _state.value.copy(phase = Phase.Failed, status = "", error = "服务器、账号、密码都要填")
            return
        }
        val name = username.trim()
        wantedOnline = true
        reloginTried = false
        retryJob?.cancel()
        retryJob = null
        _state.value = _state.value.copy(
            phase = Phase.LoggingIn,
            status = "登录中…",
            error = null,
            server = server,
            username = name,
            serverPassword = serverPassword,
        )
        val stored = store.load()
        val sameServer = stored != null && stored.server == server
        val deviceId = stored?.takeIf { sameServer }?.deviceId?.ifEmpty { null } ?: UUID.randomUUID().toString()
        val knownFingerprint = stored?.takeIf { sameServer }?.fingerprint?.ifEmpty { null }
        try {
            val (payload, fingerprint) = SchatHttp.login(
                server = server,
                username = name,
                password = password,
                deviceId = deviceId,
                deviceName = this.deviceName,
                serverPassword = serverPassword.ifBlank { null },
                fingerprint = knownFingerprint,
            )
            val fresh = SchatSession(
                server = server,
                username = name,
                password = password,
                serverPassword = serverPassword,
                userId = payload.userId,
                token = payload.token,
                refreshToken = payload.refreshToken,
                fingerprint = fingerprint.ifEmpty { knownFingerprint.orEmpty() },
                deviceId = deviceId,
                deviceName = this.deviceName,
                botId = if (sameServer) stored?.botId ?: 0 else 0,
                botName = if (sameServer) stored?.botName.orEmpty() else "",
            )
            session = fresh
            store.save(fresh)
            _state.value = _state.value.copy(
                phase = Phase.Connecting,
                status = "登录成功，连接聊天服务器…",
                error = null,
                userId = fresh.userId,
                hasSession = true,
                lastBotId = fresh.botId,
                lastBotName = fresh.botName,
            )
            runConnect()
        } catch (error: Throwable) {
            val message = error.message ?: "登录失败"
            _state.value = _state.value.copy(phase = Phase.Failed, status = "", error = message)
        }
    }

    fun logout() {
        wantedOnline = false
        retryJob?.cancel()
        retryJob = null
        connectJob?.cancel()
        connectJob = null
        val previous = client
        client = null
        previous?.close()
        session = null
        iceCache = null
        store.clear()
        routes.forEach { it.onSignalingOffline("已退出登录") }
        _state.value = State()
    }

    /** 手动重连（比如网络换了 / 被顶掉之后用户自己点） */
    fun reconnectNow() {
        if (session == null) return
        wantedOnline = true
        reloginTried = false
        retryIndex = 0
        retryJob?.cancel()
        retryJob = null
        connectWs()
    }

    /** 证书指纹和记着的不一样（服务端换了证书）：清掉重记（用户确认后调） */
    fun resetFingerprintAndReconnect() {
        val live = session ?: return
        val cleared = live.copy(fingerprint = "")
        session = cleared
        store.save(cleared)
        reconnectNow()
    }

    // MARK: 连接

    private fun connectWs() {
        connectJob?.cancel()
        connectJob = scope.launch { runConnect() }
    }

    private suspend fun runConnect() {
        var live = session ?: return
        val previous = client
        client = null
        previous?.close()
        _state.value = _state.value.copy(phase = Phase.Connecting, status = "连接聊天服务器…", error = null)

        // 先刷凭据（rotation：旧的作废，新的写回）
        if (live.refreshToken.isNotEmpty()) {
            runCatching { SchatHttp.refresh(live.server, live.refreshToken, live.fingerprint.ifEmpty { null }) }
                .onSuccess { (payload, fingerprint) ->
                    live = live.copy(
                        userId = if (payload.userId > 0) payload.userId else live.userId,
                        token = payload.token.ifEmpty { live.token },
                        refreshToken = payload.refreshToken.ifEmpty { live.refreshToken },
                        fingerprint = fingerprint.ifEmpty { live.fingerprint },
                    )
                    session = live
                    store.save(live)
                }
        }

        val holder = arrayOfNulls<SchatWs>(1)
        val ws = SchatWs(
            fingerprint = live.fingerprint.ifEmpty { null },
            serverPassword = live.serverPassword,
            onEvent = { envelope -> onEnvelope(envelope) },
            onState = { state, detail -> onWsState(holder[0], state, detail) },
        )
        holder[0] = ws
        client = ws
        try {
            ws.connect(SchatWs.wsUrl(live.server), live.token, live.deviceId, live.deviceName.ifEmpty { deviceName })
            val observed = ws.observedFingerprint
            if (!observed.isNullOrEmpty() && observed != live.fingerprint) {
                live = live.copy(fingerprint = observed)
                session = live
                store.save(live)
            }
            retryIndex = 0
            reloginTried = false
            handlingKick = false
            _state.value = _state.value.copy(
                phase = Phase.Online,
                status = "",
                error = null,
                userId = live.userId,
                hasSession = true,
            )
            loadBots()
        } catch (error: Throwable) {
            if (!identical(ws)) return
            val message = error.message ?: "连不上聊天服务器"
            if (ws.kicked) {
                handleKicked(message)
            } else {
                _state.value = _state.value.copy(phase = Phase.Connecting, status = message, error = null)
                scheduleRetry()
            }
        }
    }

    private fun identical(ws: SchatWs): Boolean = client === ws

    private fun onWsState(ws: SchatWs?, state: SchatWs.State, detail: String?) {
        if (ws != null && !identical(ws)) return
        when (state) {
            SchatWs.State.Online -> Unit
            SchatWs.State.Kicked -> handleKicked(detail ?: "凭据已失效")
            SchatWs.State.Offline -> {
                if (!wantedOnline) return
                if (_state.value.phase == Phase.Online || _state.value.phase == Phase.Connecting) {
                    routes.forEach { it.onSignalingOffline(detail ?: "聊天服务器断开了") }
                    _state.value = _state.value.copy(phase = Phase.Connecting, status = detail ?: "连接断了，重连中…")
                    scheduleRetry()
                }
            }
            SchatWs.State.Connecting -> Unit
        }
    }

    private fun handleKicked(detail: String) {
        if (handlingKick) return
        handlingKick = true
        routes.forEach { it.onSignalingOffline(detail) }
        val live = session
        // 手机上有账号密码：静默重登一次（桌面端扫码的那套做不到）
        if (live != null && live.password.isNotEmpty() && !reloginTried) {
            reloginTried = true
            _state.value = _state.value.copy(phase = Phase.Connecting, status = "凭据失效，正在用记住的账号重新登录…")
            scope.launch {
                try {
                    login(live.server, live.username, live.password, live.serverPassword)
                } finally {
                    handlingKick = false
                }
            }
            return
        }
        wantedOnline = false
        retryJob?.cancel()
        retryJob = null
        handlingKick = false
        _state.value = _state.value.copy(phase = Phase.Failed, status = "", error = "凭据已失效，请重新登录（$detail）")
    }

    private fun scheduleRetry() {
        if (!wantedOnline || retryJob != null) return
        val delayMs = BACKOFF_MS[minOf(retryIndex, BACKOFF_MS.size - 1)]
        retryIndex += 1
        _state.value = _state.value.copy(status = "连接断了，${delayMs / 1000} 秒后重连…")
        retryJob = scope.launch {
            delay(delayMs)
            retryJob = null
            if (!wantedOnline || session == null) return@launch
            connectWs()
        }
    }

    // MARK: 机器人（远程 Friday）

    suspend fun loadBots() {
        val live = session ?: return
        try {
            val bots = SchatHttp.friends(live.server, live.token, live.fingerprint.ifEmpty { null })
            _state.value = _state.value.copy(bots = bots)
        } catch (error: Throwable) {
            _state.value = _state.value.copy(status = "读机器人列表失败：${error.message ?: ""}")
        }
    }

    fun selectBot(botId: Long, botName: String) {
        store.saveBot(botId, botName)
        _state.value = _state.value.copy(lastBotId = botId, lastBotName = botName)
    }

    fun botName(botId: Long): String {
        return _state.value.bots.firstOrNull { it.id == botId }?.name
            ?: _state.value.lastBotName.ifEmpty { "机器人 $botId" }
    }

    // MARK: 信令

    /** 工作通道的信令宿主（WorkSignaling 用；没在线就返回 null） */
    fun workHost(): WorkSignaling.Host? {
        val live = session ?: return null
        val ws = client ?: return null
        if (!ws.isOnline) return null
        return object : WorkSignaling.Host {
            override fun accountId(): Long = live.userId

            override fun sendCallFrame(botId: Long, event: String, data: JsonObject) {
                client?.takeIf { it.isOnline }?.emit(event, data)
            }
        }
    }

    fun addRoute(route: WorkRoute) {
        routes.removeAll { it.botId == route.botId }
        routes += route
    }

    fun removeRoute(route: WorkRoute) {
        routes.remove(route)
    }

    /** 信令进来的帧：p2p_* 里属于某条工作通道的转给它 */
    private fun onEnvelope(envelope: JsonObject) {
        val event = envelope.str("e") ?: return
        val data = envelope["d"] as? JsonObject ?: return
        if (event != "p2p_response" && event != "p2p_signaling" && event != "p2p_request") return
        val from = data.long("fromUserId") ?: 0
        val route = routes.firstOrNull { it.botId == from } ?: return
        if (event == "p2p_request") {
            if (data.str("callType") == "work") route.onWorkFrame(event, data)
            return
        }
        // 只认自己这次拨号的 requestId，别把同一台机器的语音通话信令截走
        val requestId = data.str("requestId").orEmpty()
        if (requestId.isNotEmpty() && requestId == route.currentRequestId()) route.onWorkFrame(event, data)
    }

    // MARK: ICE

    /** 内置 STUN + 账号的 TURN（失败就只用内置 STUN；按 (server, 账号) 缓存，过半即重拉） */
    suspend fun iceServers(): List<IceServerConfig> {
        val live = session ?: return SchatIce.BUILTIN_STUN
        val now = System.currentTimeMillis()
        iceCache?.let { (servers, until) -> if (now < until) return SchatIce.withBuiltin(servers) }
        var expiresIn = 300L
        val turn = SchatHttp.iceServers(live.server, live.token, live.fingerprint.ifEmpty { null }) {
            if (it > 0) expiresIn = it
        }
        iceCache = turn to (now + expiresIn * 1000 / 2)
        return SchatIce.withBuiltin(turn)
    }

    companion object {
        /** 默认的 chatserver（私服地址，用户在设置里能改） */
        const val DEFAULT_SERVER = "https://47.100.98.3:2012"

        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 20_000, 30_000)
    }
}