package com.integemjack.friday.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.integemjack.friday.proto.SshConfig
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 服务器配置存储：SharedPreferences + Android Keystore（AES/GCM）加密密码和私钥。
 */
class ServerStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    fun load(): SshConfig? {
        val host = prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: return null
        return SshConfig(
            host = host,
            port = prefs.getInt(KEY_PORT, 22),
            user = prefs.getString(KEY_USER, "") ?: "",
            password = prefs.getString(KEY_PASSWORD, null)?.let { decrypt(it) },
            privateKey = prefs.getString(KEY_KEY, null)?.let { decrypt(it) },
        )
    }

    fun save(cfg: SshConfig) {
        prefs.edit()
            .putString(KEY_HOST, cfg.host.trim())
            .putInt(KEY_PORT, cfg.port)
            .putString(KEY_USER, cfg.user.trim())
            .apply {
                if (cfg.password != null) putString(KEY_PASSWORD, encrypt(cfg.password)) else remove(KEY_PASSWORD)
                if (cfg.privateKey != null) putString(KEY_KEY, encrypt(cfg.privateKey)) else remove(KEY_KEY)
            }
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
        val combined = iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
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
        private const val ALIAS = "friday_server_store"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USER = "user"
        private const val KEY_PASSWORD = "password_enc"
        private const val KEY_KEY = "key_enc"
    }
}
