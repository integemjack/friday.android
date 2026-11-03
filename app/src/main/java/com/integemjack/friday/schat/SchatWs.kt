package com.integemjack.friday.schat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * schat-ws/v1 客户端（Secret-Chat docs/raw_ws_protocol.md；和桌面端 `protocol/schat/SchatClient.ts` 一致）。
 *
 * 信封：`{ e: 事件名, d: 载荷, a: ackId, t: req|ev|ack|err }`。连上后第一帧必须是 hello；
 * 被踢（auth_expired）后不许自动重连（不然失效 token 会一直降级成访客）。
 */
class SchatWs(
    fingerprint: String?,
    /** 私有服务器的密码（可选，放进 hello 里） */
    private val serverPassword: String?,
    private val onEvent: (JsonObject) -> Unit,
    private val onState: (State, String?) -> Unit,
) {

    enum class State { Connecting, Online, Offline, Kicked }

    private val pinned = SchatHttp.pinned(fingerprint)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject?>>()
    private val ackSeq = AtomicLong(0)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closedByUs = false

    @Volatile
    var kicked: Boolean = false
        private set

    @Volatile
    var state: State = State.Offline
        private set

    /** 这次握手看到的服务端证书指纹（TOFU：第一次见到要落盘） */
    val observedFingerprint: String? get() = pinned.observed

    val isOnline: Boolean get() = state == State.Online

    /** 连上并 hello。抛错 = 连不上 / hello 被拒 */
    suspend fun connect(url: String, token: String, deviceId: String, deviceName: String): JsonObject {
        closedByUs = false
        kicked = false
        setState(State.Connecting)
        val opened = CompletableDeferred<Unit>()
        val request = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!opened.isCompleted) opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onFrame(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                val message = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                rejectAll(Exception("连接断了（$message）"))
                if (!opened.isCompleted) {
                    opened.completeExceptionally(Exception("连不上聊天服务器：$message"))
                }
                setState(if (kicked) State.Kicked else State.Offline, if (kicked) KICKED_TEXT else message)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                rejectAll(Exception("连接断了"))
                if (!opened.isCompleted) {
                    opened.completeExceptionally(Exception("聊天服务器关了连接（$code $reason）"))
                }
                setState(
                    if (kicked) State.Kicked else State.Offline,
                    if (kicked) KICKED_TEXT else if (closedByUs) null else "连接断了",
                )
            }
        }
        socket = pinned.client.newWebSocket(request, listener)
        withTimeout(CONNECT_TIMEOUT_MS) { opened.await() }

        val hello = buildJsonObject {
            put("v", JsonPrimitive(1))
            put("token", JsonPrimitive(token))
            put("deviceId", JsonPrimitive(deviceId))
            put("devicePlatform", JsonPrimitive("android"))
            put("deviceName", JsonPrimitive(deviceName))
            serverPassword?.takeIf { it.isNotBlank() }?.let { put("password", JsonPrimitive(it)) }
        }
        val self = try {
            request("hello", hello) ?: JsonObject(emptyMap())
        } catch (error: Throwable) {
            // hello 失败（凭据被降级成访客 / ack 超时）要把连接关掉再抛：不关就是一条幽灵连接
            closedByUs = true
            socket?.close(1000, null)
            socket = null
            throw error
        }
        val id = self.str("id")?.toLongOrNull() ?: 0
        if (id == 999L || self.bool("guest") == true) {
            kicked = true
            socket?.close(1000, null)
            socket = null
            setState(State.Kicked, "凭据已失效（服务端把它降级成访客了）")
            throw Exception("凭据已失效，请重新登录")
        }
        setState(State.Online)
        return self
    }

    private fun onFrame(text: String) {
        val envelope = runCatching { SchatHttp.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val type = envelope.str("t") ?: if (envelope["a"] != null) "ack" else "ev"
        if (type == "ack" || type == "err") {
            val ackId = envelope.str("a")
            val call = ackId?.let { pending.remove(it) }
            if (call != null) {
                if (type == "err") {
                    val detail = runCatching { envelope["d"]?.jsonObject }.getOrNull()
                    call.completeExceptionally(
                        Exception(detail?.str("message") ?: detail?.str("code") ?: "服务端拒绝了请求")
                    )
                } else {
                    // 业务失败也走 t:"ack"：{success:false, error:"not_friends"|"blocked"|…}，不能当成功吞掉
                    val ack = runCatching { envelope["d"]?.jsonObject }.getOrNull()
                    if (ack != null && ack.bool("success") == false) {
                        val code = ack.str("error").orEmpty()
                        call.completeExceptionally(SchatRejected(code, rejectText(code)))
                    } else {
                        call.complete(envelope["d"] as? JsonObject)
                    }
                }
                return
            }
        }
        if (envelope.str("e") == "auth_expired") {
            kicked = true
            val reason = runCatching { envelope["d"]?.jsonObject?.str("reason") }.getOrNull()
            setState(State.Kicked, reason?.let { "凭据已失效：$it" } ?: KICKED_TEXT)
            return
        }
        onEvent(envelope)
    }

    /** 发一帧并等 ack */
    suspend fun request(event: String, data: JsonObject): JsonObject? {
        val ws = socket ?: throw Exception("还没连上聊天服务器")
        val ackId = "a${ackSeq.incrementAndGet()}-${System.currentTimeMillis().toString(36)}"
        val deferred = CompletableDeferred<JsonObject?>()
        pending[ackId] = deferred
        val envelope = buildJsonObject {
            put("e", JsonPrimitive(event))
            put("d", data)
            put("a", JsonPrimitive(ackId))
            put("t", JsonPrimitive("req"))
        }
        if (!ws.send(envelope.toString())) {
            pending.remove(ackId)
            throw Exception("发不出去（连接断了）")
        }
        return try {
            withTimeout(ACK_TIMEOUT_MS) { deferred.await() }
        } finally {
            pending.remove(ackId)
        }
    }

    /** 单向事件（不需要 ack） */
    fun emit(event: String, data: JsonObject) {
        val ws = socket ?: return
        val envelope = buildJsonObject {
            put("e", JsonPrimitive(event))
            put("d", data)
            put("t", JsonPrimitive("ev"))
        }
        ws.send(envelope.toString())
    }

    fun close() {
        closedByUs = true
        kicked = false
        socket?.close(1000, null)
        socket = null
        rejectAll(Exception("已断开"))
        setState(State.Offline)
    }

    private fun rejectAll(error: Throwable) {
        for (key in pending.keys) pending.remove(key)?.completeExceptionally(error)
    }

    private fun setState(next: State, detail: String? = null) {
        state = next
        onState(next, detail)
    }

    companion object {
        private const val SUBPROTOCOL = "schat-ws.v1"
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val ACK_TIMEOUT_MS = 15_000L
        private const val KICKED_TEXT = "这个设备在别处退出了，或凭据被撤销"

        /** https://host:port → wss://host:port/ws（chatserver 的 WS 入口） */
        fun wsUrl(server: String): String {
            val trimmed = server.trim().trimEnd('/')
            val secure = !trimmed.startsWith("http://")
            val host = trimmed.removePrefix("https://").removePrefix("http://")
            return (if (secure) "wss://" else "ws://") + host + "/ws"
        }
    }
}

/** 把用户填的地址规整成 https://host:port（不写 scheme 时默认 https） */
fun normalizeServer(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        // 只写了 IP / 域名（可能带端口）：默认 https
        else -> "https://$trimmed"
    }
}