package com.integemjack.friday.proto

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** SSH 连接配置 */
data class SshConfig(
    val host: String,
    val port: Int = 22,
    val user: String,
    val password: String? = null,
    /** 私钥（OpenSSH 格式文本） */
    val privateKey: String? = null,
    val keyPassphrase: String? = null,
)

/**
 * SSH 传输（REMOTE.md §2）：exec 远程桥命令，把 Friday 的本地服务桥接到 SSH 的 stdin/stdout。
 * 字节流上每条消息是一行紧凑 UTF-8 JSON，以 \n 结尾。
 */
class SshTransport(
    private val config: SshConfig,
    private val onLine: (String) -> Unit,
    /** 桥自己发的错误（连不上服务时 message 附服务日志，随后桥退出） */
    private val onBridgeError: (String) -> Unit,
    private val onClosed: (Exception?) -> Unit,
) {
    private var session: Session? = null
    private var channel: ChannelExec? = null
    private var out: java.io.OutputStream? = null
    private val closedFlag = AtomicBoolean(false)

    companion object {
        /** REMOTE.md §2 sh 桥：受管的 → deb → 服务端包（friday-server 的不带 server 参数）。raw string 里 $ 不是变量，用 ${'$'} 转义 */
        const val BRIDGE_COMMAND = """sh -c 'if [ -x "${'$'}HOME/.local/opt/Friday/usr/bin/friday" ]; then exec "${'$'}HOME/.local/opt/Friday/usr/bin/friday" server --attach; elif [ -x /opt/friday/usr/bin/friday ]; then exec /opt/friday/usr/bin/friday server --attach; elif [ -x "${'$'}HOME/.friday-server/bin/friday-server" ]; then exec "${'$'}HOME/.friday-server/bin/friday-server" --attach; else echo \"Friday 未安装：先在那台机器上装 Friday（https://github.com/integemjack/friday）\"; exit 1; fi'"""
    }

    @Throws(Exception::class)
    fun connect(connectTimeoutMs: Int = 15_000) {
        val jsch = JSch()
        if (!config.privateKey.isNullOrBlank()) {
            // addIdentity(name, prvkey bytes, pubkey=null, passphrase bytes)
            jsch.addIdentity(
                "friday-key",
                config.privateKey.toByteArray(StandardCharsets.UTF_8),
                null,
                config.keyPassphrase?.takeIf { it.isNotEmpty() }?.toByteArray(StandardCharsets.UTF_8),
            )
        }
        val session = jsch.getSession(config.user, config.host, config.port)
        config.password?.takeIf { it.isNotEmpty() }?.let { session.setPassword(it) }
        // TOFU：新指纹接受、变化拒绝（进程内存内）；host key 不落盘（重启后重新 TOFU）
        session.setConfig("StrictHostKeyChecking", "accept-new")
        session.setConfig("PreferredAuthentications", if (config.privateKey.isNullOrBlank()) "password,keyboard-interactive" else "publickey,password,keyboard-interactive")
        session.connect(connectTimeoutMs)

        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(BRIDGE_COMMAND)
        channel.setInputStream(null)
        channel.setErrStream(java.io.ByteArrayOutputStream()) // 桥的 stderr 不混进协议流
        channel.connect(connectTimeoutMs)
        this.session = session
        this.channel = channel
        this.out = channel.getOutputStream()

        val input = channel.getInputStream()
        val reader = Thread {
            try {
                val br = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
                while (true) {
                    val line = br.readLine() ?: break
                    if (line.isEmpty()) continue
                    if (line.startsWith("{\"bridgeError\"") || line.contains("\"bridgeError\"")) {
                        runCatching {
                            val obj = kotlinx.serialization.json.Json.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject
                            onBridgeError(obj?.str("message") ?: line)
                        }
                        continue
                    }
                    onLine(line)
                }
            } catch (_: Exception) {
            } finally {
                if (!closedFlag.getAndSet(true)) {
                    onClosed(null)
                }
            }
        }
        reader.isDaemon = true
        reader.name = "friday-ssh-reader"
        reader.start()
    }

    @Synchronized
    fun send(text: String) {
        val o = out ?: throw Exception("SSH 未连接")
        o.write((text + "\n").toByteArray(StandardCharsets.UTF_8))
        o.flush()
    }

    fun close() {
        if (!closedFlag.getAndSet(true)) onClosed(null)
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        out = null
    }
}
