package com.integemjack.friday.schat

/**
 * chatserver 的数据类型（Secret-Chat 四端同一套字段）。
 */

/** 好友里的一个机器人（远程 Friday 就在这些里） */
data class SchatBot(
    val id: Long,
    val name: String,
    val botCalls: Boolean = false,
)

/** 1:1 连接用的 ICE 服务器（REMOTE.md §2：内置 STUN + 账号的 TURN） */
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

/** chatserver 的 HTTPS 回执（带服务端证书指纹，TOFU 用） */
data class SchatHttpResult(
    val status: Int,
    val body: String,
    val fingerprint: String,
) {
    val ok: Boolean get() = status in 200..299
}

/** 业务拒绝（t:"ack" 里 success:false）或服务端错误 */
class SchatRejected(val code: String, message: String) : Exception(message)

internal fun rejectText(code: String): String = when (code) {
    "not_friends" -> "还没和这个机器人成为好友（或陌生人额度用完了）：先在手机上把它加为好友"
    "blocked" -> "对方把你拉黑了"
    "contentLimitExceeded" -> "内容太长了"
    else -> if (code.isBlank()) "发送失败" else "发送失败（$code）"
}

object SchatIce {
    /** 与桌面端 DcMediaEngine 的 defaultServers 同一份（三端 parity：这份清单不许动） */
    val BUILTIN_STUN = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:stun2.l.google.com:19302",
        "stun:stun3.l.google.com:19302",
        "stun:stun4.l.google.com:19302",
        "stun:stun.miwifi.com:3478",
        "stun:stun.qq.com:3478",
        "stun:stun.syncthing.net:3478",
        "stun:stun.stunprotocol.org:3478",
    ).map { IceServerConfig(urls = listOf(it)) }

    /** 内置 STUN + 账号的 TURN（拉失败就只用内置 STUN） */
    fun withBuiltin(turn: List<IceServerConfig>): List<IceServerConfig> = BUILTIN_STUN + turn
}