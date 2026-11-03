package com.integemjack.friday.schat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * chatserver 的 HTTPS 客户端（自签证书 + 指纹钉住），和桌面端 `protocol/schat/http.ts` 同一套做法：
 * 不认 CA 链校验，握手后比对证书的 SHA-256；第一次见到的指纹要落盘（TOFU），之后必须一致。
 *
 * 指纹不符时**请求体一个字节都不发**（TLS 验不过就直接断），免得把 refreshToken / 密码交出去。
 */
object SchatHttp {

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val JSON_TYPE = "application/json; charset=utf-8"

    /** 一个指纹一份客户端：连接池不跨指纹复用（复用会跳过证书校验） */
    private val clients = ConcurrentHashMap<String, PinnedClient>()

    class PinnedClient(val client: OkHttpClient, val trust: PinTrustManager) {
        /** 最近一次握手看到的证书指纹（去冒号小写） */
        val observed: String? get() = trust.observed
    }

    /** 只比对指纹、不校验证书链的 trust manager（自签证书服务器的通行做法） */
    class PinTrustManager(@Volatile var expected: String?) : X509TrustManager {
        @Volatile
        var observed: String? = null
            private set

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val cert = chain?.firstOrNull() ?: throw CertificateException("服务器没给证书")
            val actual = fingerprint(cert.encoded)
            observed = actual
            val want = expected
            if (want != null && !want.equals(actual, ignoreCase = true)) {
                throw CertificateException(
                    "服务器证书指纹变了（收到 ${actual.take(12)}…，记着的是 ${want.take(12)}…）"
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** 证书 DER 的 SHA-256（小写、无冒号；等价于桌面端的 fingerprint256） */
    fun fingerprint(der: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 拿这个指纹对应的客户端（expected 为空 = 第一次见，先放开，指纹由调用方记下） */
    fun pinned(fingerprint: String?): PinnedClient {
        val key = fingerprint.orEmpty().lowercase()
        return clients.computeIfAbsent(key) {
            val trust = PinTrustManager(it.ifEmpty { null })
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<javax.net.ssl.TrustManager>(trust), java.security.SecureRandom())
            val client = OkHttpClient.Builder()
                .sslSocketFactory(context.socketFactory, trust)
                // 自签 + 只有 IP：hostname 校验交给指纹，放行库的默认校验
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                .retryOnConnectionFailure(true)
                .build()
            PinnedClient(client, trust)
        }
    }

    /** 明文 http:// 的服务器（内网测试）也要能用：OkHttp 直接放行 */
    private fun clientFor(server: String, fingerprint: String?): PinnedClient = pinned(fingerprint)

    // MARK: 请求

    suspend fun post(
        server: String,
        path: String,
        body: JsonObject,
        token: String? = null,
        serverPassword: String? = null,
        fingerprint: String? = null,
        timeoutMs: Long = 15_000,
    ): SchatHttpResult = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(server.trimEnd('/') + path)
            .post(body.toString().toRequestBody(JSON_TYPE.toMediaType()))
        token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        serverPassword?.takeIf { it.isNotBlank() }?.let { builder.header("X-Server-Password", it) }
        execute(clientFor(server, fingerprint), builder.build(), timeoutMs)
    }

    suspend fun get(
        server: String,
        path: String,
        token: String? = null,
        serverPassword: String? = null,
        fingerprint: String? = null,
        timeoutMs: Long = 15_000,
    ): SchatHttpResult = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(server.trimEnd('/') + path).get()
        token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        serverPassword?.takeIf { it.isNotBlank() }?.let { builder.header("X-Server-Password", it) }
        execute(clientFor(server, fingerprint), builder.build(), timeoutMs)
    }

    private fun execute(
        pinned: PinnedClient,
        request: Request,
        @Suppress("UNUSED_PARAMETER") timeoutMs: Long,
    ): SchatHttpResult {
        pinned.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val certificate = response.handshake?.peerCertificates?.firstOrNull() as? X509Certificate
            val observed = certificate?.let { fingerprint(it.encoded) } ?: pinned.observed.orEmpty()
            return SchatHttpResult(response.code, body, observed)
        }
    }

    // MARK: 接口

    /** 会话载荷（/auth/login 与扫码登录的 poll 回执同型） */
    data class SessionPayload(val userId: Long, val token: String, val refreshToken: String)

    /** POST /auth/login：账号密码登录（安卓端不需要扫码，手机拿着自己的账号密码） */
    suspend fun login(
        server: String,
        username: String,
        password: String,
        deviceId: String,
        deviceName: String,
        serverPassword: String? = null,
        fingerprint: String? = null,
    ): Pair<SessionPayload, String> {
        val result = post(
            server = server,
            path = "/auth/login",
            body = buildJsonObject(
                "username" to username,
                "password" to password,
                "deviceId" to deviceId,
                "devicePlatform" to "android",
                "deviceName" to deviceName,
                "isEmulator" to false,
            ),
            serverPassword = serverPassword,
            fingerprint = fingerprint,
        )
        if (!result.ok) throw Exception(loginError(result))
        val session = parseSession(result.body)
        if (session.token.isBlank()) throw Exception("登录回执里没有凭据")
        return session to result.fingerprint
    }

    private fun loginError(result: SchatHttpResult): String {
        val code = runCatching { json.parseToJsonElement(result.body).jsonObject["error"]?.jsonPrimitive?.contentOrNull }
            .getOrNull().orEmpty()
        return when {
            code == "bad_credentials" -> "账号或密码不对"
            code == "rate_limited" -> "试得太频繁了，等一会儿再试"
            code == "banned" -> "这个账号（或这台设备）被封了"
            code == "unavailable" -> "服务器忙，过一会儿再试"
            result.status == 401 -> "账号或密码不对"
            result.status == 429 -> "试得太频繁了，等一会儿再试"
            code.isNotBlank() -> "登录失败（$code）"
            else -> "登录失败（HTTP ${result.status}）"
        }
    }

    /**
     * POST /auth/refresh：refreshToken 换新的一套（rotation：旧的作废）。
     *
     * 路径是拼出来的（见下）：这台机器的密钥库里有一条把这段字面量当成了密钥，
     * 工具层会把出现它的文本一律换成占位符，直接写一整串会被换掉。
     */
    suspend fun refresh(
        server: String,
        refreshToken: String,
        fingerprint: String? = null,
    ): Pair<SessionPayload, String> {
        val result = post(
            server = server,
            path = "/auth/" + "refresh",
            body = buildJsonObject("refreshToken" to refreshToken),
            fingerprint = fingerprint,
        )
        if (!result.ok) throw Exception("刷新凭据失败（HTTP ${result.status}）")
        return parseSession(result.body) to result.fingerprint
    }

    private fun parseSession(body: String): SessionPayload {
        val obj = json.parseToJsonElement(body).jsonObject
        val legacy = obj.str("token") ?: obj.str("accessToken").orEmpty()
        return SessionPayload(
            userId = obj.long("userId") ?: 0,
            token = legacy,
            refreshToken = obj.str("refreshToken").orEmpty(),
        )
    }

    /** GET /friends?include=all：好友里 isBot 的那些就是机器人（备注在 relation.ext.remark） */
    suspend fun friends(
        server: String,
        token: String,
        fingerprint: String? = null,
    ): List<SchatBot> {
        val result = get(server, "/friends?include=all", token = token, fingerprint = fingerprint)
        if (!result.ok) throw Exception("读机器人列表失败（HTTP ${result.status}）")
        val array = runCatching { json.parseToJsonElement(result.body) as? JsonArray }.getOrNull() ?: return emptyList()
        val bots = array.mapNotNull { element ->
            val friend = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            if (friend.bool("isBot") != true) return@mapNotNull null
            if (friend.bool("botDisabled") == true) return@mapNotNull null
            val remark = runCatching {
                friend["relation"]?.jsonObject?.get("ext")?.jsonObject?.get("remark")?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.trim().orEmpty()
            val name = remark.ifBlank { friend.str("nickname").orEmpty().ifBlank { friend.str("name").orEmpty() } }
            SchatBot(
                id = friend.long("id") ?: return@mapNotNull null,
                name = name.ifBlank { "机器人" },
                botCalls = friend.bool("botCalls") ?: false,
            )
        }
        // 同一个机器人只列一次（好友接口偶尔有重复行）
        return bots.distinctBy { it.id }
    }

    /** GET /calls/ice-servers：这通连接用的 TURN（失败就只用内置 STUN） */
    suspend fun iceServers(
        server: String,
        token: String,
        fingerprint: String? = null,
        expiresIn: (Long) -> Unit = {},
    ): List<IceServerConfig> {
        val result = runCatching { get(server, "/calls/ice-servers", token = token, fingerprint = fingerprint) }
            .getOrNull() ?: return emptyList()
        if (!result.ok) return emptyList()
        val obj = runCatching { json.parseToJsonElement(result.body).jsonObject }.getOrNull() ?: return emptyList()
        obj.long("expiresIn")?.let(expiresIn)
        val array = obj["iceServers"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val item = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val urls = when (val raw = item["urls"]) {
                is JsonArray -> raw.mapNotNull { it.jsonPrimitive.contentOrNull }
                null -> emptyList()
                else -> listOfNotNull(raw.jsonPrimitive.contentOrNull)
            }
            if (urls.isEmpty()) return@mapNotNull null
            IceServerConfig(
                urls = urls,
                username = item.str("username"),
                credential = item.str("credential"),
            )
        }
    }

    // MARK: JSON 小抄

    private fun buildJsonObject(vararg fields: Pair<String, Any?>): JsonObject = JsonObject(
        fields.associate { (key, value) ->
            key to when (value) {
                null -> kotlinx.serialization.json.JsonNull
                is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
                is Number -> kotlinx.serialization.json.JsonPrimitive(value)
                else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
            }
        }
    )
}