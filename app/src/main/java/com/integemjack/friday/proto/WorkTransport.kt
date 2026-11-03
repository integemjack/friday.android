package com.integemjack.friday.proto

import com.integemjack.friday.schat.SchatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.webrtc.PeerConnection
import java.util.ArrayDeque

/**
 * WebRTC 工作通道的客户端那一半（REMOTE.md §2）：把 SChat 的信令、WebRTC 的 DataChannel、
 * 分帧、发送队列串起来，向上就是一个 [Transport]。
 *
 * 和桌面端 `WorkChannelHub` + `WorkSignaling` 的配合一一对应：
 * 拨号 → accepted → 建 PC / DataChannel → offer → answer / ICE → 通道开 → 收发 JSON-RPC。
 * 断了就报给上层（FridayClient 退避后用**新的 requestId** 重拨，本类每次重连都是新实例）。
 */
class WorkTransport(
    private val schat: SchatService,
    override val botId: Long,
    private val scope: CoroutineScope,
    private val listener: Listener,
) : Transport, SchatService.WorkRoute {

    interface Listener {
        /** DataChannel 开了（hello 该发了） */
        fun onOpen()

        /** 收到一条完整的 JSON-RPC 消息 */
        fun onMessage(text: String)

        /** 断了；permanent = 重拨也没用（被拒 / 被别的设备顶掉 / 协议错） */
        fun onClosed(reason: String, permanent: Boolean)
    }

    private val lock = Any()
    private val timer = WorkSignaling.DefaultTimer()
    private val signaling: WorkSignaling = WorkSignaling(
        host = object : WorkSignaling.Host {
            override fun accountId(): Long = schat.state.value.userId

            override fun sendCallFrame(botId: Long, event: String, data: JsonObject) {
                schat.workHost()?.sendCallFrame(botId, event, data)
            }
        },
        onEvent = { event -> onSignalEvent(event) },
        timers = timer,
    )

    private var framer = WorkFramer()
    private var peer: WorkPeer? = null
    private val queue = ArrayDeque<WorkOutFrame>()
    private var channelOpen = false
    private var closed = false
    private var connectTimeout: Job? = null

    /** 开始拨号（schat-ws 必须在线，否则上层直接报错，不走到这里） */
    fun open() {
        schat.addRoute(this)
        signaling.dial(botId)
    }

    // MARK: 信令

    private fun onSignalEvent(event: WorkSignaling.Event) {
        when (event) {
            is WorkSignaling.Event.Accepted -> {
                // 先拿 ICE 服务器（内置 STUN + 账号的 TURN），再建 PC
                scope.launch {
                    val servers = runCatching { schat.iceServers() }.getOrDefault(emptyList())
                    if (isClosed()) return@launch
                    createPeer(servers)
                }
            }

            is WorkSignaling.Event.Signal -> when (event.type) {
                "answer" -> peer?.setAnswer(event.payload)
                "ice_candidate" -> peer?.addCandidate(event.payload)
                else -> Unit
            }

            is WorkSignaling.Event.Ended -> {
                // 被同一账号的另一台设备顶掉：不能自动重拨（会互相顶），所以算永久失败
                teardown()
                reportClosed(event.reason, permanent = event.permanent || event.replaced)
            }
        }
    }

    private fun createPeer(servers: List<com.integemjack.friday.schat.IceServerConfig>) {
        if (isClosed()) return
        val iceServers = servers.map { server ->
            PeerConnection.IceServer.builder(server.urls)
                .apply {
                    if (!server.username.isNullOrEmpty() && !server.credential.isNullOrEmpty()) {
                        setUsername(server.username)
                        setPassword(server.credential)
                    }
                }
                .createIceServer()
        }
        val peer = WorkPeer(iceServers, peerListener)
        synchronized(lock) {
            this.peer?.dispose()
            this.peer = peer
        }
        peer.start()
        // offer 发出去之后 25 秒还没开通道：当失败（上层重拨）
        connectTimeout?.cancel()
        connectTimeout = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!isClosed() && !channelOpen) {
                reportClosed("连接超时（打洞没成功）", permanent = false)
                teardown()
            }
        }
    }

    private val peerListener = object : WorkPeer.Listener {
        override fun onLocalOffer(sdp: String) {
            signaling.send("offer", sdp)
        }

        override fun onLocalCandidate(json: String) {
            signaling.send("ice_candidate", json)
        }

        override fun onOpen() {
            connectTimeout?.cancel()
            connectTimeout = null
            synchronized(lock) {
                channelOpen = true
                framer = WorkFramer()
            }
            listener.onOpen()
            flush()
        }

        override fun onFrame(binary: Boolean, data: ByteArray) {
            val feed = synchronized(lock) { framer.feed(binary, data) }
            when (feed) {
                is WorkFramer.Feed.Ready -> listener.onMessage(feed.message)
                WorkFramer.Feed.More -> Unit
                WorkFramer.Feed.Invalid -> {
                    reportClosed("通道协议错（分片对不上）", permanent = true)
                    teardown()
                }
            }
        }

        override fun onClosed(reason: String) {
            if (isClosed()) return
            reportClosed(reason, permanent = false)
            teardown()
        }

        override fun onWritable() {
            flush()
        }
    }

    // MARK: 收发

    override fun send(text: String) {
        if (isClosed()) return
        val frames = WorkFramerCodec.encode(text)
        val peer = synchronized(lock) { peer }
        if (peer == null) {
            synchronized(lock) { queue.addAll(frames) }
            return
        }
        synchronized(lock) {
            if (!channelOpen || peer.bufferedAmount() > BUFFERED_HIGH) {
                queue.addAll(frames)
            } else {
                frames.forEach { transmit(it, peer) }
            }
        }
    }

    private fun flush() {
        val peer = synchronized(lock) { peer } ?: return
        while (true) {
            val frame = synchronized(lock) {
                if (!channelOpen || queue.isEmpty() || peer.bufferedAmount() > BUFFERED_LOW) return
                queue.poll()
            } ?: return
            transmit(frame, peer)
        }
    }

    private fun transmit(frame: WorkOutFrame, peer: WorkPeer) {
        if (frame.binary) peer.sendBinary(frame.bytes ?: return) else peer.sendText(frame.text ?: return)
    }

    override fun close() {
        if (isClosed()) return
        synchronized(lock) { closed = true }
        connectTimeout?.cancel()
        connectTimeout = null
        signaling.hangup()
        signaling.dispose()
        teardown()
        schat.removeRoute(this)
        timer.close()
    }

    // MARK: SchatService.WorkRoute

    override fun currentRequestId(): String = signaling.currentRequestId()

    override fun onWorkFrame(event: String, data: JsonObject) {
        if (isClosed()) return
        val requestId = data.str("requestId").orEmpty()
        when (event) {
            "p2p_response" -> {
                val accepted = data.bool("accepted") ?: false
                signaling.onResponse(requestId, accepted, data.str("reason").orEmpty())
            }

            "p2p_signaling" -> {
                signaling.onSignal(
                    requestId = requestId,
                    type = data.str("type").orEmpty(),
                    payload = data.str("payload").orEmpty(),
                    reason = data.str("reason").orEmpty(),
                )
            }

            else -> Unit
        }
    }

    override fun onSignalingOffline(reason: String) {
        if (isClosed()) return
        reportClosed(reason, permanent = false)
        teardown()
    }

    // MARK: 收尾

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    private fun reportClosed(reason: String, permanent: Boolean) {
        if (isClosed()) return
        synchronized(lock) { closed = true }
        connectTimeout?.cancel()
        connectTimeout = null
        listener.onClosed(reason, permanent)
    }

    private fun teardown() {
        val peer = synchronized(lock) {
            val current = this.peer
            this.peer = null
            channelOpen = false
            queue.clear()
            current
        }
        peer?.dispose()
    }

    companion object {
        /** offer 发出去到通道开：最多等这么久 */
        private const val CONNECT_TIMEOUT_MS = 25_000L

        /** DataChannel 积压超过这个就先不发（REMOTE.md §2「积压」） */
        private const val BUFFERED_HIGH = 4L * 1024 * 1024

        private const val BUFFERED_LOW = 1L * 1024 * 1024
    }
}