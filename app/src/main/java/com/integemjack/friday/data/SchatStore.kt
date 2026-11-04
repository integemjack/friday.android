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
 * SChat 会话存储：SharedPreferences + Android Keystore（AES/GCM）加密密码与令牌。
 *
 * 令牌就是登录凭据（Bearer），和密码一样加密存；证书指纹不加密（它本来就是要给人核对的）。
 */
class SchatStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("schat", Context.MODE_PRIVATE)

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