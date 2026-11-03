package com.integemjack.friday.proto

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlinx.serialization.json.contentOrNull
import java.nio.ByteBuffer

/**
 * 一条工作通道的 WebRTC 那一半（REMOTE.md §2「WebRTC 工作通道」；对应桌面端 `workChannel.ts`）：
 * 主叫侧建 `RTCPeerConnection` + DataChannel「friday-work」、出 offer、收 answer / ICE。
 *
 * 和 friday.work 一样：**只有数据**，不碰摄像头 / 麦克风（语音视频走普通通话，不在这条连接上）。
 * 所有 WebRTC 调用都在自己的一条 HandlerThread 上串行执行（org.webrtc 要求同一线程）。
 */
class WorkPeer(
    private val iceServers: List<PeerConnection.IceServer>,
    private val listener: Listener,
) {

    interface Listener {
        /** 本地 offer 就绪（SDP 文本，直接当 p2p_signaling 的 payload 发出去） */
        fun onLocalOffer(sdp: String)

        /** 本地 ICE 候选（JSON：{sdpMid, sdpMLineIndex, candidate}） */
        fun onLocalCandidate(json: String)

        /** DataChannel 开了：这条通道能用了 */
        fun onOpen()

        /** 收到一帧 */
        fun onFrame(binary: Boolean, data: ByteArray)

        /** 通道关了 / 连不上（只报一次） */
        fun onClosed(reason: String)

        /** bufferedAmount 降下来了，可以接着发 */
        fun onWritable()
    }

    private val thread = HandlerThread("friday-work-peer").apply { start() }
    private val handler = Handler(thread.looper)

    private var factory: PeerConnectionFactory? = null
    private var egl: EglBase? = null
    private var pc: PeerConnection? = null
    private var channel: DataChannel? = null

    @Volatile
    private var closedReported = false

    @Volatile
    private var disposed = false

    /** 建 PC + DataChannel、出 offer（在 WebRTC 线程上排队） */
    fun start() {
        handler.post {
            if (pc != null || disposed) return@post
            createPeerConnection()
            createDataChannel()
            createOffer()
        }
    }

    fun setAnswer(sdp: String) {
        handler.post {
            val pc = pc ?: return@post
            pc.setRemoteDescription(
                object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) {
                        closedLater("收不下对方的应答（${error ?: "坏 SDP"}）")
                    }
                },
                SessionDescription(SessionDescription.Type.ANSWER, sdp),
            )
        }
    }

    fun addCandidate(json: String) {
        handler.post {
            val pc = pc ?: return@post
            val candidate = parseCandidate(json) ?: return@post
            // 候选来早了 / 已过期：忽略（连接状态机会兜底，和桌面端一样）
            runCatching { pc.addIceCandidate(candidate) }
        }
    }

    fun sendText(text: String) {
        send(binary = false, bytes = text.toByteArray(Charsets.UTF_8))
    }

    fun sendBinary(bytes: ByteArray) {
        send(binary = true, bytes = bytes)
    }

    private fun send(binary: Boolean, bytes: ByteArray) {
        handler.post {
            val channel = channel ?: return@post
            if (channel.state() != DataChannel.State.OPEN) return@post
            runCatching { channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), binary)) }
                .onFailure { closedLater("发不出去（${it.message ?: "通道坏了"}）") }
        }
    }

    fun bufferedAmount(): Long = runCatching { channel?.bufferedAmount() ?: 0L }.getOrDefault(0L)

    fun dispose() {
        disposed = true
        handler.post {
            teardown()
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
    }

    // MARK: 建

    private fun createPeerConnection() {
        // JVM 单测（Robolectric）里没有 JNI 库：初始化会抛 UnsatisfiedLinkError，真机上才走得通
        appContext?.let { context -> if (!initialized) runCatching { initialize(context) } }
        val egl = EglBase.create()
        this.egl = egl
        // 只有数据也用得上工厂；视频工厂给个默认的（不建轨道就不会用到相机 / 编码）
        val factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        this.factory = factory
        val config = RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            // 和桌面端一样：候选一直收着（对端可能先给 TURN 的）
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }
        val connection = factory.createPeerConnection(config, observer) ?: run {
            closedLater("建不起 PeerConnection")
            return
        }
        pc = connection
    }

    private fun createDataChannel() {
        val pc = pc ?: return
        val channel = pc.createDataChannel(CHANNEL, DataChannel.Init().apply { ordered = true }) ?: run {
            closedLater("建不起数据通道")
            return
        }
        this.channel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {
                if (channel.bufferedAmount() < BUFFERED_LOW) listener.onWritable()
            }

            override fun onStateChange() {
                when (channel.state()) {
                    DataChannel.State.OPEN -> listener.onOpen()
                    DataChannel.State.CLOSING, DataChannel.State.CLOSED -> closedLater("通道关了")
                    else -> Unit
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                listener.onFrame(buffer.binary, bytes)
            }
        })
    }

    private fun createOffer() {
        val pc = pc ?: return
        pc.createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    val sdp = description ?: return
                    pc.setLocalDescription(
                        object : SdpObserver {
                            override fun onCreateSuccess(desc: SessionDescription?) {}
                            override fun onSetSuccess() = listener.onLocalOffer(sdp.description)
                            override fun onCreateFailure(error: String?) {}
                            override fun onSetFailure(error: String?) {
                                closedLater("本地描述设不上（${error ?: "？"}）")
                            }
                        },
                        sdp,
                    )
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    closedLater("出 offer 失败（${error ?: "？"}）")
                }

                override fun onSetFailure(error: String?) {}
            },
            MediaConstraints(),
        )
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        override fun onIceCandidate(candidate: IceCandidate?) {
            val ice = candidate ?: return
            if (ice.sdp.isEmpty()) return
            listener.onLocalCandidate(candidateJson(ice))
        }

        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            when (newState) {
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED,
                -> closedLater("WebRTC 连不上（NAT 穿透失败）")

                else -> Unit
            }
        }
    }

    /** 只报一次断开，然后拆掉（再叫就直接忽略） */
    private fun closedLater(reason: String) {
        if (closedReported || disposed) return
        closedReported = true
        handler.post {
            teardown()
            listener.onClosed(reason)
        }
    }

    private fun teardown() {
        val channel = this.channel
        val pc = this.pc
        val factory = this.factory
        val egl = this.egl
        this.channel = null
        this.pc = null
        this.factory = null
        this.egl = null
        if (channel != null) {
            runCatching { channel.unregisterObserver() }
            runCatching { channel.close() }
            runCatching { channel.dispose() }
        }
        if (pc != null) {
            runCatching { pc.close() }
            runCatching { pc.dispose() }
        }
        runCatching { factory?.dispose() }
        runCatching { egl?.release() }
    }

    companion object {
        const val CHANNEL = "friday-work"

        /** bufferedAmount 低于这个就当「不堵了」 */
        const val BUFFERED_LOW = 1024 * 1024L

        @Volatile
        private var initialized = false

        @Volatile
        private var appContext: Context? = null

        /** 进程里只初始化一次（PeerConnectionFactory.initialize）；没有 JNI 库（JVM 单测）就丢掉异常等下次 */
        fun initialize(context: Context) {
            appContext = context.applicationContext
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .createInitializationOptions()
                )
                initialized = true
            }
        }

        /** 和服务端同一口径的候选 JSON（Qt candidatePayload：sdpMid 空就写 "0"） */
        fun candidateJson(candidate: IceCandidate): String {
            return "{\"candidate\":${quote(candidate.sdp)},\"sdpMid\":${quote(candidate.sdpMid?.ifEmpty { "0" } ?: "0")}," +
                "\"sdpMLineIndex\":${candidate.sdpMLineIndex}}"
        }

        /** {sdpMid, sdpMLineIndex, candidate}（对端也可能只给纯候选串） */
        fun parseCandidate(payload: String): IceCandidate? {
            val text = payload.trim()
            if (text.isEmpty()) return null
            if (!text.startsWith("{")) return IceCandidate("0", 0, text)
            val obj = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
            }.getOrNull() ?: return null
            fun field(key: String): String? = runCatching {
                (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            }.getOrNull()
            val candidate = field("candidate") ?: return null
            val mid = field("sdpMid")?.ifEmpty { "0" } ?: "0"
            val index = field("sdpMLineIndex")?.toIntOrNull() ?: 0
            return IceCandidate(mid, index, candidate)
        }

        private fun quote(value: String): String {
            val out = StringBuilder(value.length + 2)
            out.append('"')
            for (char in value) {
                when (char) {
                    '\\' -> out.append("\\\\")
                    '"' -> out.append("\\\"")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> out.append(char)
                }
            }
            out.append('"')
            return out.toString()
        }
    }
}