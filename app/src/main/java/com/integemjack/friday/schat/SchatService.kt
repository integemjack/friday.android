package com.integemjack.friday.schat

import com.integemjack.friday.data.SavedServer
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
        /** 登录着的（没登录时是上次用的）那台聊天服务器；没有默认值，登录前要先选 / 加一台 */
        val server: String = "",
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

    /**
     * 信令层的一条线：一条工作通道（一台远程 Friday），或者打给它助手的一通电话。
     * 同一个机器人上可以同时有一条工作通道和一通电话（各用各的 PeerConnection，按 requestId 分帧）
     */
    interface WorkRoute {
        val botId: Long

        /** `work` / `video` / `audio`：同一个机器人上同一种只留一条 */
        val callType: String get() = "work"

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

    // MARK: 聊天服务器列表（登录前先选一台）

    private val _servers = MutableStateFlow(store.loadServers())

    /** 加过的聊天服务器：选服务器那一页列它们，登录页只登选中的那台 */
    val servers: StateFlow<List<SavedServer>> = _servers.asStateFlow()

    /**
     * 加一台（[replacing] 不为空 = 改那一台）。同一个地址已经有了就改它的名字和密码。
     * 地址没变时证书指纹、设备 id 留着；改了地址就当是另一台服务器
     */
    fun saveServer(addressRaw: String, name: String, serverPassword: String, replacing: String? = null): SavedServer? {
        val address = normalizeServer(addressRaw)
        if (address.isEmpty()) return null
        val current = store.loadServers()
        val same = current.firstOrNull { it.address == address }
        val entry = SavedServer(
            address = address,
            name = name.trim(),
            serverPassword = serverPassword.trim(),
            fingerprint = same?.fingerprint.orEmpty(),
            deviceId = same?.deviceId.orEmpty(),
        )
        // 改的那台（或同地址的那台）原地换掉；新的排最后
        val slot = current.indexOfFirst { it.address == (replacing ?: address) }
        val list = current.filterNot { it.address == address || it.address == replacing }.toMutableList()
        list.add(if (slot < 0) list.size else minOf(slot, list.size), entry)
        store.saveServers(list)
        _servers.value = list
        return entry
    }

    fun removeServer(address: String) {
        val list = store.loadServers().filterNot { it.address == address }
        store.saveServers(list)
        _servers.value = list
    }

    /** 加服务器时先探一下：连得上返回 null，连不上 / 被拒了返回一句原因 */
    suspend fun probeServer(addressRaw: String, serverPassword: String): String? {
        val address = normalizeServer(addressRaw)
        if (address.isEmpty()) return "先填服务器地址"
        return try {
            val result = SchatHttp.get(address, "/server-info", serverPassword = serverPassword.trim().ifBlank { null })
            when (result.status) {
                401, 403 -> "服务器拒绝了（HTTP ${result.status}），服务器密码对吗？"
                else -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            "连不上这台服务器：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 这台手机在 [server] 上的身份：设备 id + 记着的证书指纹。
     * 先看登录态里的（同一台），再看服务器列表里记着的，都没有就新起一个设备 id、指纹等第一次见到再记
     */
    private fun identity(server: String): Pair<String, String?> {
        val stored = store.load()?.takeIf { it.server == server }
        val saved = store.loadServers().firstOrNull { it.address == server }
        val deviceId = stored?.deviceId?.ifEmpty { null } ?: saved?.deviceId?.ifEmpty { null } ?: UUID.randomUUID().toString()
        val fingerprint = stored?.fingerprint?.ifEmpty { null } ?: saved?.fingerprint?.ifEmpty { null }
        return deviceId to fingerprint
    }

    // MARK: 启动 / 恢复

    /** 登录着（有凭据；不管现在连没连上）：后台保持连接只在这时候开 */
    fun hasSession(): Boolean = session != null

    /** 现在在线 / 正在连：网络回来时不用再催 */
    val busyOrOnline: Boolean
        get() = _state.value.phase == Phase.Online || _state.value.phase == Phase.Connecting ||
            _state.value.phase == Phase.LoggingIn

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
        val (deviceId, knownFingerprint) = identity(server)
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
            adopt(fresh)
        } catch (error: Throwable) {
            val message = error.message ?: "登录失败"
            _state.value = _state.value.copy(phase = Phase.Failed, status = "", error = message)
        }
    }

    /**
     * 拿到一套登录态之后（账号密码 / 扫码 / 通过 SChat）：存下来、连 schat-ws。
     * 连 ws 放在服务自己的作用域里（connectWs）：登录页在 hasSession 一变真就离开界面，
     * 它的协程会被取消——要是在调用方的协程里连，刚登上就被掐断（「StandaloneCoroutine was cancelled」）
     */
    private fun adopt(fresh: SchatSession) {
        session = fresh
        store.save(fresh)
        _servers.value = store.loadServers()
        _state.value = _state.value.copy(
            phase = Phase.Connecting,
            status = "登录成功，连接聊天服务器…",
            error = null,
            server = fresh.server,
            username = fresh.username,
            serverPassword = fresh.serverPassword,
            userId = fresh.userId,
            hasSession = true,
            lastBotId = fresh.botId,
            lastBotName = fresh.botName,
        )
        connectWs()
    }

    // MARK: 扫码登录 / 通过 SChat 登录（同一套 login-sessions：码面 SCSH:1:login:<token>）

    /** 扫码登录走到哪了（登录页照着画） */
    sealed interface QrLogin {
        data object Idle : QrLogin
        data object Starting : QrLogin

        /** 码出来了，在等手机扫 / 等手机上点确认（[scanned]） */
        data class Waiting(
            val token: String,
            val server: String,
            val expiresAt: Long,
            val scanned: Boolean = false,
        ) : QrLogin {
            /** 二维码里写的（SChat 扫一扫认这一串） */
            val payload: String get() = "SCSH:1:login:$token"
        }

        data object Expired : QrLogin
        data object Denied : QrLogin

        /** [unsupported]：这个聊天服务器没有扫码登录（老版本），只能账号密码登录 */
        data class Failed(val message: String, val unsupported: Boolean = false) : QrLogin
    }

    private val _qrLogin = MutableStateFlow<QrLogin>(QrLogin.Idle)
    val qrLogin: StateFlow<QrLogin> = _qrLogin.asStateFlow()
    private var qrJob: Job? = null

    /**
     * 开一次扫码登录：先问服务器支不支持（/server-info 的 qrLogin），建会话出码，每 2 秒问一次，
     * 手机上确认了就拿到和账号密码登录同型的登录态，走同一条「登录成功」的路。
     * 同一台手机上装着 SChat 时，登录页把同一个 token 经 `schat://login/<token>` 交给 SChat 去确认。
     */
    fun startQrLogin(serverRaw: String, serverPassword: String) {
        qrJob?.cancel()
        qrJob = scope.launch { runQrLogin(normalizeServer(serverRaw), serverPassword) }
    }

    fun cancelQrLogin() {
        qrJob?.cancel()
        qrJob = null
        _qrLogin.value = QrLogin.Idle
    }

    private suspend fun runQrLogin(server: String, serverPassword: String) {
        if (server.isEmpty()) {
            _qrLogin.value = QrLogin.Failed("先填聊天服务器的地址")
            return
        }
        _qrLogin.value = QrLogin.Starting
        val stored = store.load()
        val sameServer = stored != null && stored.server == server
        val (deviceId, known) = identity(server)
        val password = serverPassword.ifBlank { null }
        try {
            val (info, seen) = SchatHttp.serverInfo(server, password, known)
            if (info.bool("qrLogin") != true) {
                _qrLogin.value = QrLogin.Failed("这个聊天服务器还不支持扫码登录，用账号密码登录", unsupported = true)
                return
            }
            // 证书指纹第一次见就记下（TOFU），之后这一轮都按它认
            val pinned = known ?: seen.ifEmpty { null }
            val (session, _) = SchatHttp.createLoginSession(server, deviceId, deviceName, password, pinned)
            _qrLogin.value = QrLogin.Waiting(session.token, server, session.expiresAt)
            while (true) {
                delay(session.intervalMs)
                if (System.currentTimeMillis() > session.expiresAt + 5_000) {
                    _qrLogin.value = QrLogin.Expired
                    return
                }
                val poll = try {
                    SchatHttp.pollLoginSession(server, session.token, deviceId, password, pinned)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    continue // 网络抖了一下：接着问，码过期了上面会收尾
                }
                when (poll) {
                    SchatHttp.LoginPoll.Pending -> Unit
                    SchatHttp.LoginPoll.Scanned -> (_qrLogin.value as? QrLogin.Waiting)?.let {
                        _qrLogin.value = it.copy(scanned = true)
                    }
                    SchatHttp.LoginPoll.Denied -> {
                        _qrLogin.value = QrLogin.Denied
                        return
                    }
                    SchatHttp.LoginPoll.Expired -> {
                        _qrLogin.value = QrLogin.Expired
                        return
                    }
                    is SchatHttp.LoginPoll.Approved -> {
                        val payload = poll.session
                        val name = SchatHttp.me(server, payload.token, pinned) ?: "用户 ${payload.userId}"
                        // 同一台服务器、同一个账号再登：记着的那台机器人留着；换了账号就重新选
                        val keepBot = sameServer && stored?.userId == payload.userId
                        adopt(
                            SchatSession(
                                server = server,
                                username = name,
                                password = "", // 扫码登录没有密码：凭据过期靠 refreshToken 续
                                serverPassword = serverPassword,
                                userId = payload.userId,
                                token = payload.token,
                                refreshToken = payload.refreshToken,
                                fingerprint = pinned.orEmpty(),
                                deviceId = deviceId,
                                deviceName = deviceName,
                                botId = if (keepBot) stored?.botId ?: 0 else 0,
                                botName = if (keepBot) stored?.botName.orEmpty() else "",
                            )
                        )
                        _qrLogin.value = QrLogin.Idle
                        return
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (error: Throwable) {
            _qrLogin.value = QrLogin.Failed(error.message ?: "扫码登录失败")
        }
    }

    fun logout() {
        cancelQrLogin()
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
        // 只忘掉这次登录；服务器地址留着（私有服务器退出再登不用重填，登录页的扫码也去对的服务器）
        store.forgetLogin()
        routes.forEach { it.onSignalingOffline("已退出登录") }
        val kept = _state.value
        _state.value = State(server = kept.server, serverPassword = kept.serverPassword)
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
        routes.removeAll { it.botId == route.botId && it.callType == route.callType }
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
        val mine = routes.filter { it.botId == from }
        if (mine.isEmpty()) return
        if (event == "p2p_request") {
            val type = data.str("callType")
            mine.firstOrNull { it.callType == type }?.onWorkFrame(event, data)
            return
        }
        // 只认自己这次拨号的 requestId：同一台机器的工作通道和通话各收各的
        val requestId = data.str("requestId").orEmpty()
        if (requestId.isEmpty()) return
        mine.firstOrNull { it.currentRequestId() == requestId }?.onWorkFrame(event, data)
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
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 20_000, 30_000)
    }
}