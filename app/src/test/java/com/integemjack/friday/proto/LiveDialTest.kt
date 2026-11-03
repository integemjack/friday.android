package com.integemjack.friday.proto

import com.integemjack.friday.schat.SchatHttp
import com.integemjack.friday.schat.SchatWs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 真连一次聊天服务器 + 真呼一次机器人（默认跳过，只有给了环境变量才跑）：
 *
 * ```
 * SCHAT_SERVER=https://host:port SCHAT_TOKEN=… SCHAT_BOT_ID=100000057 ./gradlew :app:testDebugUnitTest --tests '*LiveDialTest*'
 * ```
 *
 * 验的是「手机这一端发的信令，服务端 / 机器人认不认」——hello 能过、机器人列表能读、
 * `p2p_request{callType:"work"}` 能拿到 `p2p_response`（accepted 或一个明确的拒绝原因）。
 * DataChannel 那半段要真机才能验（org.webrtc 的 so 跑不了 JVM）。
 */
class LiveDialTest {

    private val server = System.getenv("SCHAT_SERVER").orEmpty()
    private val token = System.getenv("SCHAT_TOKEN").orEmpty()
    private val botId = System.getenv("SCHAT_BOT_ID").orEmpty().toLongOrNull() ?: 0L

    @Test
    fun `login over ws, list bots, dial the work channel`() = runBlocking {
        assumeTrue("没给 SCHAT_SERVER/SCHAT_TOKEN/SCHAT_BOT_ID，跳过真连测试", server.isNotEmpty() && token.isNotEmpty() && botId > 0)

        // 1) 机器人列表：好友里 isBot 的那些（这个测试账号可能一个机器人好友都没有，那也能接着拨，
        //    服务端会回一个明确的拒绝原因——这里要验的就是「帧的格式服务端认不认」）
        val bots = SchatHttp.friends(server, token)
        println("[live] bots = ${bots.map { "${it.id}:${it.name}(calls=${it.botCalls})" }}")

        // 2) schat-ws 的 hello；进来的 p2p_* 帧转给信令状态机
        var signaling: WorkSignaling? = null
        val accepted = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var endedReason = ""
        val ws = SchatWs(
            fingerprint = null,
            serverPassword = null,
            onEvent = { envelope ->
                val event = envelope.str("e") ?: ""
                val data = envelope["d"] as? JsonObject
                println("[live] ← $event ${envelope.toString().take(160)}")
                if (data != null && event == "p2p_response") {
                    signaling?.onResponse(
                        data.str("requestId").orEmpty(),
                        data.bool("accepted") == true,
                        data.str("reason").orEmpty(),
                    )
                }
                if (data != null && event == "p2p_signaling") {
                    signaling?.onSignal(
                        data.str("requestId").orEmpty(),
                        data.str("type").orEmpty(),
                        data.str("payload").orEmpty(),
                        data.str("reason").orEmpty(),
                    )
                }
            },
            onState = { state, detail -> println("[live] ws $state $detail") },
        )
        val self = ws.connect(SchatWs.wsUrl(server), token, UUID.randomUUID().toString(), "friday-android-test")
        println("[live] hello ok: $self")
        assertTrue(ws.isOnline)

        // 3) 拨号：p2p_request(callType work) → 等 p2p_response
        signaling = WorkSignaling(
            host = object : WorkSignaling.Host {
                override fun accountId(): Long = self.long("id") ?: 0L

                override fun sendCallFrame(botId: Long, event: String, data: JsonObject) {
                    println("[live] → $event ${data.toString().take(200)}")
                    ws.emit(event, data)
                }
            },
            onEvent = { event ->
                println("[live] event $event")
                when (event) {
                    is WorkSignaling.Event.Accepted -> accepted.countDown()
                    is WorkSignaling.Event.Ended -> {
                        endedReason = event.reason
                        ended.countDown()
                    }
                    else -> Unit
                }
            },
        )

        signaling.dial(botId)
        val gotAccepted = accepted.await(40, TimeUnit.SECONDS)
        val gotEnded = if (!gotAccepted) ended.await(10, TimeUnit.SECONDS) else false
        println("[live] accepted=$gotAccepted ended=$gotEnded reason=$endedReason")

        // 被接受 = 全通了；拿到一个明确的拒绝原因（offline / bot_no_p2p / not_friends…）也算通：
        // 说明服务端把 toUserId / requestId / callType 都解出来了。30 秒超时、什么回帧都没有才是真问题。
        assertTrue("这次拨号没人应答：$endedReason", gotAccepted || gotEnded)

        signaling.hangup()
        ws.close()
    }
}