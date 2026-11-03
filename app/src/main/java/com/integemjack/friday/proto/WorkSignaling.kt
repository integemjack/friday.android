package com.integemjack.friday.proto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * WebRTC 工作通道的信令状态机（REMOTE.md §2「WebRTC 工作通道」；和桌面端
 * `src/protocol/work/WorkSignaling.ts` 同一个状态机）。
 *
 * 流程（主叫 = 本机，被叫 = Friday 机器人那头的 RemoteWorkSession）：
 * ```
 *   dial → p2p_request{callType:"work"} → 等 p2p_response{accepted:true}
 *   → 建 RTCPeerConnection + DataChannel「friday-work」→ offer
 *   → p2p_signaling{type:"offer"} → answer / ice_candidate 互送 → DataChannel open → hello
 * ```
 * 挂断 / 通道断开：发 hangup（还没被接受就发 request_cancel），用新的 requestId 退避重拨。
 *
 * 本类只管信令，不碰 WebRTC：建 PC 由 [WorkTransport] 收到 [Event.Accepted] 之后做。
 */
class WorkSignaling(
    private val host: Host,
    private val onEvent: (Event) -> Unit,
    private val timers: Timer = DefaultTimer(),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {

    /** 信令要发给对端时的宿主（SchatService 负责真的发出去） */
    interface Host {
        /** 发一帧 p2p_* 事件给对端 */
        fun sendCallFrame(botId: Long, event: String, data: JsonObject)

        /** 本机账号 id（p2p_* 的 fromUserId） */
        fun accountId(): Long
    }

    /** 信令事件（喂 RTCPeerConnection / 上层重拨） */
    sealed interface Event {
        /** 机器人接受了：该建 PC、出 offer 了 */
        data class Accepted(val requestId: String) : Event

        /** 对端信令（answer / ice_candidate） */
        data class Signal(val requestId: String, val type: String, val payload: String) : Event

        /** 这次拨号收尾了（被拒 / 超时 / 挂断 / 被顶） */
        data class Ended(
            val requestId: String,
            val reason: String,
            val replaced: Boolean = false,
            val permanent: Boolean = false,
        ) : Event
    }

    /** 可注入的定时器（单测里换成手动推进的假实现） */
    interface Timer {
        fun schedule(delayMs: Long, run: () -> Unit): Cancel

        fun interface Cancel {
            fun cancel()
        }
    }

    /** 默认定时器：一条守护线程的调度器（每个连接一个，跟着连接一起关） */
    class DefaultTimer : Timer, AutoCloseable {
        private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "friday-work-timer").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        override fun schedule(delayMs: Long, run: () -> Unit): Timer.Cancel {
            val future = executor.schedule({ run() }, delayMs, TimeUnit.MILLISECONDS)
            return Timer.Cancel { future.cancel(false) }
        }

        override fun close() {
            executor.shutdownNow()
        }
    }

    private var requestId = ""
    private var peerId = 0L
    private var ended = true
    private var accepted = false
    private var timer: Timer.Cancel? = null

    val isActive: Boolean get() = !ended

    /*** 现在这次拨号的 requestId（认领 SChat 帧用；空闲为空） */
    fun currentRequestId(): String = if (ended) "" else requestId

    /** 发起连接：p2p_request(callType work)。30 秒没被接受就报失败（上层重拨） */
    fun dial(botId: Long) {
        dispose()
        peerId = botId
        requestId = newRequestId()
        ended = false
        accepted = false
        val at = now()
        host.sendCallFrame(
            botId,
            "p2p_request",
            buildJsonObject {
                put("toUserId", botId)
                put("fromUserId", host.accountId())
                put("requestId", requestId)
                put("callType", "work")
                put("timestamp", at)
                put("expiresAt", at + 30_000)
            },
        )
        timer = timers.schedule(30_000) {
            if (!ended) finish("机器人 30 秒内没接受连接（它在线吗？装的是 2.43+ 吗？）")
        }
    }

    /** p2p_response 进来（accepted / declined） */
    fun onResponse(requestId: String, accepted: Boolean, reason: String) {
        if (ended || requestId != this.requestId) return
        timer?.cancel()
        timer = null
        if (accepted) {
            this.accepted = true
            onEvent(Event.Accepted(requestId))
            return
        }
        val text = when (reason) {
            "user_rejected" -> "机器人拒绝了工作连接（不是主人账号？）"
            "not_friends" -> "这个机器人已经不在你的 SChat 好友里（被删了或换了账号）：重新加好友后再连"
            "error" -> "机器人那头没有工作服务（装的是 2.43+ 吗？）"
            else -> "机器人拒绝了连接（$reason）"
        }
        finish(text, permanent = reason in PERMANENT_REJECTS)
    }

    /** p2p_signaling 进来（offer / answer / ice_candidate / hangup）；hangup 的 reason 在信令帧的顶层字段 */
    fun onSignal(requestId: String, type: String, payload: String, reason: String = "") {
        if (ended || requestId != this.requestId) return
        if (type == "hangup" || type == "request_cancel") {
            val replaced = type == "hangup" && reason in REPLACED_REASONS
            finish(if (replaced) "同一账号的另一台设备把这条连接顶掉了" else "机器人挂断了工作连接", replaced)
            return
        }
        onEvent(Event.Signal(requestId, type, payload))
    }

    /** 要发给对端的信令（offer / ice_candidate） */
    fun send(type: String, payload: String) {
        if (ended || peerId == 0L) return
        host.sendCallFrame(
            peerId,
            "p2p_signaling",
            buildJsonObject {
                put("toUserId", peerId)
                put("targetUserId", peerId)
                put("fromUserId", host.accountId())
                put("requestId", requestId)
                put("type", type)
                put("payload", payload)
                put("timestamp", now())
            },
        )
    }

    /** 挂断（还没被接受就取消） */
    fun hangup() {
        if (ended) return
        if (peerId != 0L) {
            host.sendCallFrame(
                peerId,
                "p2p_signaling",
                buildJsonObject {
                    put("toUserId", peerId)
                    put("targetUserId", peerId)
                    put("fromUserId", host.accountId())
                    put("requestId", requestId)
                    put("type", if (accepted) "hangup" else "request_cancel")
                    put("payload", "")
                    put("timestamp", now())
                },
            )
        }
        finish("已断开")
    }

    private fun finish(reason: String, replaced: Boolean = false, permanent: Boolean = false) {
        timer?.cancel()
        timer = null
        if (ended) return
        ended = true
        onEvent(Event.Ended(requestId, reason, replaced, permanent))
    }

    fun dispose() {
        timer?.cancel()
        timer = null
        ended = true
        accepted = false
        requestId = ""
        peerId = 0L
    }

    companion object {
        /** 这些拒绝重拨也没用（换不来结果，只会每分钟再「正在呼叫」一次）：上层停止自动重拨 */
        private val PERMANENT_REJECTS = setOf("not_friends", "user_rejected")

        /** 服务端 hangup 的 reason：同账号另一台设备把这条连接顶掉了（被顶时不能自动重拨，会互相顶） */
        private val REPLACED_REASONS = setOf("新的连接来了", "replaced")
    }
}