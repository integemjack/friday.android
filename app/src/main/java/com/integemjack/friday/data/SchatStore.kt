package com.integemjack.friday.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** SChat 登录会话（REMOTE.md §2：工作通道认 SChat 主人身份） */
data class SchatSession(
    /** chatserver 地址，如 https://47.100.98.3:2012 */
    val server: String,
    val username: String,
    /** 账号密码：只在手机上留着，供凭据过期时静默重登；加密存 */
    val password: String,
    /** 服务器密码（X-Server-Password，私有服务器才要；可选） */
    val serverPassword: String,
    val userId: Long,
    /** legacy 7 天 token：REST 和 WS 都用它 */
    val token: String,
    val refreshToken: String,
    /** 服务端证书 SHA-256 指纹（去冒号小写，TOFU：第一次见到就记下，之后必须一致） */
    val fingerprint: String,
    val deviceId: String,
    val deviceName: String,
    /** 上次连的那台机器人（远程 Friday） */
    val botId: Long = 0,
    val botName: String = "",
)

/**
 * 加过的一台聊天服务器：登录前先在「选服务器」那一页选它（或者新加一台），登录页只管登这一台。
 *
 * [fingerprint] / [deviceId] 跟着服务器走：换到别的服务器再换回来，证书指纹（TOFU）和这台手机在那边的设备 id 都还在
 */
data class SavedServer(
    /** 规整过的地址，如 https://47.100.98.3:2012（同一台服务器认它） */
    val address: String,
    /** 自己起的名字（可空，空着就显示地址） */
    val name: String = "",
    /** 服务器密码（X-Server-Password，私有服务器才要；加密存） */
    val serverPassword: String = "",
    val fingerprint: String = "",
    val deviceId: String = "",
) {
    val host: String get() = address.removePrefix("https://").removePrefix("http://")
    val title: String get() = name.ifBlank { host }
}

/**
 * SChat 会话存储：SharedPreferences + Android Keystore（AES/GCM）加密密码与令牌。
 *
 * 令牌就是登录凭据（Bearer），和密码一样加密存；证书指纹不加密（它本来就是要给人核对的）。
 */
class SchatStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("schat", Context.MODE_PRIVATE)

    /** 上次登录用的那台服务器（退出登录也留着，选服务器那一页标「上次用的」） */
    fun lastServer(): String = prefs.getString(KEY_SERVER, null).orEmpty()

    /**
     * 加过的服务器。老版本只记一台（[KEY_SERVER]）：列表还空着时把它挪进来，升级上来的人不用重填
     */
    fun loadServers(): List<SavedServer> {
        val raw = prefs.getString(KEY_SERVERS, null)
        if (raw == null) {
            val legacy = load() ?: return emptyList()
            val seeded = listOf(
                SavedServer(
                    address = legacy.server,
                    serverPassword = legacy.serverPassword,
                    fingerprint = legacy.fingerprint,
                    deviceId = legacy.deviceId,
                )
            )
            saveServers(seeded)
            return seeded
        }
        val array = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val address = obj.text("address").ifBlank { return@mapNotNull null }
            SavedServer(
                address = address,
                name = obj.text("name"),
                serverPassword = obj.text("passwordEnc").ifEmpty { null }?.let { decrypt(it) }.orEmpty(),
                fingerprint = obj.text("fingerprint"),
                deviceId = obj.text("deviceId"),
            )
        }
    }

    fun saveServers(servers: List<SavedServer>) {
        val array = buildJsonArray {
            servers.forEach { s ->
                add(
                    buildJsonObject {
                        put("address", s.address)
                        put("name", s.name)
                        if (s.serverPassword.isNotEmpty()) put("passwordEnc", encrypt(s.serverPassword))
                        put("fingerprint", s.fingerprint)
                        put("deviceId", s.deviceId)
                    }
                )
            }
        }
        prefs.edit().putString(KEY_SERVERS, array.toString()).apply()
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** 登录态落盘时，把证书指纹 / 设备 id 也记到服务器列表里那一台上（没变就不写） */
    private fun rememberIdentity(session: SchatSession) {
        val servers = loadServers()
        val index = servers.indexOfFirst { it.address == session.server }
        val updated = if (index < 0) {
            servers + SavedServer(
                address = session.server,
                serverPassword = session.serverPassword,
                fingerprint = session.fingerprint,
                deviceId = session.deviceId,
            )
        } else {
            val old = servers[index]
            if (old.fingerprint == session.fingerprint && old.deviceId == session.deviceId) return
            servers.toMutableList().also {
                it[index] = old.copy(fingerprint = session.fingerprint, deviceId = session.deviceId)
            }
        }
        saveServers(updated)
    }

    fun load(): SchatSession? {
        val server = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: return null
        val token = prefs.getString(KEY_TOKEN, null)?.let { decrypt(it) }.orEmpty()
        return SchatSession(
            server = server,
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD, null)?.let { decrypt(it) }.orEmpty(),
            serverPassword = prefs.getString(KEY_SERVER_PASSWORD, null)?.let { decrypt(it) }.orEmpty(),
            userId = prefs.getLong(KEY_USER_ID, 0),
            token = token,
            refreshToken = prefs.getString(KEY_REFRESH, null)?.let { decrypt(it) }.orEmpty(),
            fingerprint = prefs.getString(KEY_FINGERPRINT, "").orEmpty(),
            deviceId = prefs.getString(KEY_DEVICE_ID, "").orEmpty(),
            deviceName = prefs.getString(KEY_DEVICE_NAME, "").orEmpty(),
            botId = prefs.getLong(KEY_BOT_ID, 0),
            botName = prefs.getString(KEY_BOT_NAME, "").orEmpty(),
        )
    }

    fun save(session: SchatSession) {
        prefs.edit()
            .putString(KEY_SERVER, session.server.trim())
            .putString(KEY_USERNAME, session.username)
            .putLong(KEY_USER_ID, session.userId)
            .putString(KEY_TOKEN, encrypt(session.token))
            .putString(KEY_REFRESH, encrypt(session.refreshToken))
            .putString(KEY_FINGERPRINT, session.fingerprint)
            .putString(KEY_DEVICE_ID, session.deviceId)
            .putString(KEY_DEVICE_NAME, session.deviceName)
            .putLong(KEY_BOT_ID, session.botId)
            .putString(KEY_BOT_NAME, session.botName)
            .apply {
                if (session.password.isNotEmpty()) putString(KEY_PASSWORD, encrypt(session.password))
                else remove(KEY_PASSWORD)
                if (session.serverPassword.isNotEmpty()) putString(KEY_SERVER_PASSWORD, encrypt(session.serverPassword))
                else remove(KEY_SERVER_PASSWORD)
            }
            .apply()
        rememberIdentity(session)
    }

    /** 只改「上次连的机器人」，其余字段不动（连接成功后落一次） */
    fun saveBot(botId: Long, botName: String) {
        prefs.edit().putLong(KEY_BOT_ID, botId).putString(KEY_BOT_NAME, botName).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * 退出登录：凭据、密码、账号、选的机器人都删掉；聊天服务器地址（和它的服务器密码、记下的证书指纹、
     * 这台设备的 id）留着——私有服务器的人退出再登，不用重新填地址，登录页的扫码也直接去对的服务器
     */
    fun forgetLogin() {
        prefs.edit()
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_USER_ID)
            .remove(KEY_TOKEN)
            .remove(KEY_REFRESH)
            .remove(KEY_BOT_ID)
            .remove(KEY_BOT_NAME)
            .apply()
    }

    // MARK: Android Keystore AES/GCM

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = runCatching {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, 12)
        val body = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        private const val ALIAS = "friday_schat_store"
        private const val KEY_SERVER = "server"
        private const val KEY_SERVERS = "servers"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password_enc"
        private const val KEY_SERVER_PASSWORD = "server_password_enc"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_TOKEN = "token_enc"
        private const val KEY_REFRESH = "refresh_enc"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_BOT_ID = "bot_id"
        private const val KEY_BOT_NAME = "bot_name"
    }
}