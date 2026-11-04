package com.integemjack.friday.call

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import com.integemjack.friday.proto.WorkPeer
import com.integemjack.friday.proto.WorkSignaling
import com.integemjack.friday.proto.bool
import com.integemjack.friday.proto.str
import com.integemjack.friday.schat.SchatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * 跟助手打视频 / 语音电话（ASSISTANT.md「通话」；SChat「机器人通话」）：打给连着的那台 Friday 的机器人，
 * 它的助手接主人打来的电话——听你说、看你摄像头最新的一帧、开口回你，画面是它的光环。
 *
 * 信令和工作通道是同一个状态机（[WorkSignaling]，callType `video` / `audio`），走同一条 schat-ws，
 * 按 requestId 分帧（[SchatService.WorkRoute]），所以工作通道一直连着、不受影响。
 * 媒体口径照契约：音频 Opus 48 kHz 单声道（WebRTC 默认），视频 H.264 baseline（对方 answer 只留这两样；
 * 我们 offer 里有就行，DefaultVideoEncoderFactory 带硬件 H.264）。
 *
 * 所有 org.webrtc 调用都在自己的一条 HandlerThread 上串行（同 [WorkPeer]）。界面经 [state] 看状态，
 * 用 [setRemoteSink] / [setLocalSink] 接上画面（视频轨是异步来的，这里用转发 sink 接住）。
 */
class AssistantCall(
    private val app: Context,
    private val schat: SchatService,
    private val scope: CoroutineScope,
) {
    enum class Phase { Idle, Dialing, Connecting, Active, Ended }

    data class State(
        val phase: Phase = Phase.Idle,
        val video: Boolean = true,
        val peerName: String = "",
        val muted: Boolean = false,
        val cameraOn: Boolean = true,
        val frontCamera: Boolean = true,
        val speaker: Boolean = true,
        /** 媒体接通的时刻（毫秒；没通是 0） */
        val connectedAt: Long = 0,
        /** 对方的画面到了 */
        val remoteVideo: Boolean = false,
        /** 给用户看的一句（被拒的原因、没权限只能听……） */
        val message: String? = null,
    ) {
        val inCall: Boolean get() = phase != Phase.Idle
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val thread = HandlerThread("friday-call").apply { start() }
    private val handler = Handler(thread.looper)

    /** 渲染用的 EGL（界面上的 SurfaceViewRenderer 用它的 context 初始化）；第一次要用时建，跟着进程 */
    val egl: EglBase by lazy { EglBase.create() }

    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var remoteTrack: VideoTrack? = null
    private var dismissJob: Job? = null
    private var botId = 0L

    private val remoteProxy = ProxySink()
    private val localProxy = ProxySink()

    private val timers = WorkSignaling.DefaultTimer()
    private var signaling: WorkSignaling? = null

    private val route = object : SchatService.WorkRoute {
        override val botId: Long get() = this@AssistantCall.botId
        override val callType: String get() = if (_state.value.video) "video" else "audio"
        override fun currentRequestId(): String = signaling?.currentRequestId().orEmpty()

        override fun onWorkFrame(event: String, data: JsonObject) {
            val signaling = signaling ?: return
            val requestId = data.str("requestId").orEmpty()
            when (event) {
                "p2p_response" -> signaling.onResponse(requestId, data.bool("accepted") ?: false, data.str("reason").orEmpty())
                "p2p_signaling" -> signaling.onSignal(
                    requestId = requestId,
                    type = data.str("type").orEmpty(),
                    payload = data.str("payload").orEmpty(),
                    reason = data.str("reason").orEmpty(),
                )
                else -> Unit
            }
        }

        override fun onSignalingOffline(reason: String) {
            if (_state.value.phase == Phase.Idle || _state.value.phase == Phase.Ended) return
            end("聊天服务器断开了，通话结束")
        }
    }

    // MARK: 打 / 挂

    /** 打给 [botId] 那台的助手。界面先要到麦克风（视频还要摄像头）权限再调 */
    fun start(botId: Long, peerName: String, video: Boolean) {
        val current = _state.value
        if (current.phase != Phase.Idle && current.phase != Phase.Ended) return
        val host = schat.workHost()
        if (host == null) {
            _state.value = State(phase = Phase.Ended, video = video, peerName = peerName, message = "聊天服务器没连上，打不了电话")
            scheduleDismiss()
            return
        }
        dismissJob?.cancel()
        this.botId = botId
        _state.value = State(phase = Phase.Dialing, video = video, peerName = peerName, cameraOn = video)
        val signaling = WorkSignaling(
            host = host,
            onEvent = ::onSignalEvent,
            timers = timers,
            callType = if (video) "video" else "audio",
        )
        this.signaling = signaling
        schat.addRoute(route)
        signaling.dial(botId)
        routeAudio(true)
    }

    fun hangup() {
        val phase = _state.value.phase
        if (phase == Phase.Idle || phase == Phase.Ended) {
            dismiss()
            return
        }
        signaling?.hangup()
        end(if (_state.value.connectedAt > 0) "通话结束 · ${durationText()}" else "已取消")
    }

    /** 演示数据用（debug 包的 DemoData）：直接摆一个通话中的样子看排版，不拨号、不碰媒体 */
    internal fun showDemo(state: State) {
        _state.value = state
    }

    /** 收起「通话结束」那一页 */
    fun dismiss() {
        dismissJob?.cancel()
        if (_state.value.phase == Phase.Ended) _state.value = State()
    }

    fun setMuted(muted: Boolean) {
        _state.value = _state.value.copy(muted = muted)
        handler.post { audioTrack?.setEnabled(!muted) }
        sendMediaState()
    }

    /** 摄像头开关：本端视频轨 enabled 切换（关 = 发黑帧，不重协商，同桌面端） */
    fun setCamera(on: Boolean) {
        _state.value = _state.value.copy(cameraOn = on)
        handler.post { videoTrack?.setEnabled(on) }
    }

    fun switchCamera() {
        handler.post {
            (capturer as? CameraVideoCapturer)?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFront: Boolean) {
                    _state.value = _state.value.copy(frontCamera = isFront)
                }

                override fun onCameraSwitchError(error: String?) {}
            })
        }
    }

    fun setSpeaker(on: Boolean) {
        _state.value = _state.value.copy(speaker = on)
        runCatching { audioManager().isSpeakerphoneOn = on }
    }

    /** 界面上的远端画面（null = 拆下） */
    fun setRemoteSink(sink: VideoSink?) {
        remoteProxy.target = sink
    }

    /** 界面上的本端预览（null = 拆下） */
    fun setLocalSink(sink: VideoSink?) {
        localProxy.target = sink
    }

    // MARK: 信令

    private fun onSignalEvent(event: WorkSignaling.Event) {
        when (event) {
            is WorkSignaling.Event.Accepted -> {
                _state.value = _state.value.copy(phase = Phase.Connecting)
                scope.launch {
                    val servers = runCatching { schat.iceServers() }.getOrDefault(emptyList())
                    val ice = servers.map { server ->
                        PeerConnection.IceServer.builder(server.urls).apply {
                            if (!server.username.isNullOrEmpty() && !server.credential.isNullOrEmpty()) {
                                setUsername(server.username)
                                setPassword(server.credential)
                            }
                        }.createIceServer()
                    }
                    handler.post { buildPeer(ice) }
                }
            }

            is WorkSignaling.Event.Signal -> handler.post {
                val pc = pc ?: return@post
                when (event.type) {
                    "answer" -> pc.setRemoteDescription(
                        sdpObserver(onSetFailure = { end("收不下助手的应答（$it）") }),
                        SessionDescription(SessionDescription.Type.ANSWER, event.payload),
                    )
                    "ice_candidate" -> WorkPeer.parseCandidate(event.payload)?.let { runCatching { pc.addIceCandidate(it) } }
                    else -> Unit // media_state（对方的画面 / 静音）只是显示用，助手不会静音
                }
            }

            is WorkSignaling.Event.Ended -> end(event.reason)
        }
    }

    private fun sendMediaState() {
        val state = _state.value
        if (state.phase != Phase.Active && state.phase != Phase.Connecting) return
        signaling?.send("media_state", "{\"video\":${state.video},\"muted\":${state.muted}}")
    }

    // MARK: 媒体（WebRTC 线程）

    private fun buildPeer(iceServers: List<PeerConnection.IceServer>) {
        if (pc != null || _state.value.phase != Phase.Connecting) return
        val video = _state.value.video
        try {
            WorkPeer.initialize(app)
            val adm = JavaAudioDeviceModule.builder(app)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            val factory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, false))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            adm.release()
            this.factory = factory

            val config = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            val pc = factory.createPeerConnection(config, observer) ?: run {
                end("建不起通话连接")
                return
            }
            this.pc = pc

            // 麦克风：回声消除 + 降噪（JavaAudioDeviceModule 用系统的硬件 AEC / NS）
            val audioSource = factory.createAudioSource(MediaConstraints())
            val audioTrack = factory.createAudioTrack("friday-audio", audioSource)
            audioTrack.setEnabled(!_state.value.muted)
            this.audioSource = audioSource
            this.audioTrack = audioTrack
            pc.addTrack(audioTrack, listOf("friday"))

            if (video) {
                val track = startCamera(factory)
                if (track != null) {
                    pc.addTrack(track, listOf("friday"))
                } else {
                    // 摄像头开不了：只收对方的画面（对方照常发、我们能看）
                    pc.addTransceiver(
                        MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                        RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
                    )
                    _state.value = _state.value.copy(cameraOn = false, message = "摄像头打不开，对方看不到你")
                }
            }

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", video.toString()))
            }
            pc.createOffer(
                sdpObserver(
                    onCreate = { offer ->
                        pc.setLocalDescription(
                            sdpObserver(
                                onSet = { signaling?.send("offer", offer.description) },
                                onSetFailure = { end("本地描述设不上（$it）") },
                            ),
                            offer,
                        )
                    },
                    onCreateFailure = { end("出 offer 失败（$it）") },
                ),
                constraints,
            )
        } catch (e: Throwable) {
            end("通话起不来（${e.message ?: e.javaClass.simpleName}）")
        }
    }

    private fun startCamera(factory: PeerConnectionFactory): VideoTrack? = runCatching {
        val enumerator = Camera2Enumerator(app)
        val names = enumerator.deviceNames
        val name = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull() ?: return null
        val capturer = enumerator.createCapturer(name, null) ?: return null
        val helper = SurfaceTextureHelper.create("friday-camera", egl.eglBaseContext)
        val source = factory.createVideoSource(capturer.isScreencast)
        capturer.initialize(helper, app, source.capturerObserver)
        // 720p 竖屏（同助手那头的画面口径：Baseline 3.1 以内）
        capturer.startCapture(1280, 720, 30)
        val track = factory.createVideoTrack("friday-video", source)
        track.addSink(localProxy)
        this.capturer = capturer
        this.surfaceHelper = helper
        this.videoSource = source
        this.videoTrack = track
        _state.value = _state.value.copy(frontCamera = enumerator.isFrontFacing(name))
        track
    }.getOrNull()

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}

        override fun onIceCandidate(candidate: IceCandidate?) {
            val ice = candidate ?: return
            if (ice.sdp.isEmpty()) return
            signaling?.send("ice_candidate", WorkPeer.candidateJson(ice))
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() as? VideoTrack ?: return
            handler.post {
                remoteTrack = track
                track.addSink(remoteProxy)
                _state.value = _state.value.copy(remoteVideo = true)
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    if (_state.value.connectedAt == 0L) {
                        signaling?.markConnected()
                        _state.value = _state.value.copy(phase = Phase.Active, connectedAt = System.currentTimeMillis())
                        sendMediaState()
                    }
                }
                PeerConnection.PeerConnectionState.FAILED -> {
                    signaling?.hangup()
                    end("媒体连不上（NAT 穿透失败）")
                }
                else -> Unit
            }
        }
    }

    // MARK: 收尾

    private fun end(message: String) {
        val current = _state.value
        if (current.phase == Phase.Idle || current.phase == Phase.Ended) return
        _state.value = current.copy(phase = Phase.Ended, message = message, remoteVideo = false)
        schat.removeRoute(route)
        signaling?.dispose()
        signaling = null
        handler.post { teardownMedia() }
        routeAudio(false)
        scheduleDismiss()
    }

    private fun scheduleDismiss() {
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(2_500)
            if (_state.value.phase == Phase.Ended) _state.value = State()
        }
    }

    private fun teardownMedia() {
        remoteTrack?.let { runCatching { it.removeSink(remoteProxy) } }
        remoteTrack = null
        videoTrack?.let { runCatching { it.removeSink(localProxy) } }
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        runCatching { surfaceHelper?.dispose() }
        surfaceHelper = null
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        pc = null
        runCatching { videoTrack?.dispose() }
        videoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { audioTrack?.dispose() }
        audioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { factory?.dispose() }
        factory = null
    }

    private fun audioManager(): AudioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 通话时走通话音频模式、默认外放（视频通话手机拿在面前）；结束了还原 */
    private fun routeAudio(inCall: Boolean) {
        runCatching {
            val audio = audioManager()
            if (inCall) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                audio.isSpeakerphoneOn = _state.value.speaker
            } else {
                audio.isSpeakerphoneOn = false
                audio.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    /** 「3 分 05 秒」 */
    fun durationText(now: Long = System.currentTimeMillis()): String {
        val at = _state.value.connectedAt
        if (at == 0L) return ""
        val total = ((now - at) / 1000).coerceAtLeast(0)
        val minutes = total / 60
        val seconds = total % 60
        return if (minutes > 0) "%d 分 %02d 秒".format(minutes, seconds) else "$seconds 秒"
    }

    private fun sdpObserver(
        onCreate: (SessionDescription) -> Unit = {},
        onSet: () -> Unit = {},
        onCreateFailure: (String) -> Unit = {},
        onSetFailure: (String) -> Unit = {},
    ) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) {
            description?.let(onCreate)
        }

        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(error: String?) = onCreateFailure(error ?: "？")
        override fun onSetFailure(error: String?) = onSetFailure(error ?: "？")
    }

    /** 转发 sink：视频轨是异步来的、界面上的渲染器随时换，用它接住（同 AppRTC 的 ProxyVideoSink） */
    private class ProxySink : VideoSink {
        @Volatile
        var target: VideoSink? = null

        override fun onFrame(frame: VideoFrame) {
            target?.onFrame(frame)
        }
    }

}
