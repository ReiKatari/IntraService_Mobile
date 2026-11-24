package com.lantaservice.intra.data.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lantaservice.intra.data.network.SessionCredentialsProvider
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedCredentials(
    val baseUrl: String = SessionCredentialsProvider.FIXED_BASE_URL,
    val login: String,
    val password: String,
    val rememberMe: Boolean
)

/**
 * Encrypts user credentials at rest using AndroidKeyStore AES-256-GCM.
 */
class SecureSessionStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun save(credentials: SavedCredentials) {
        if (!credentials.rememberMe) {
            clear()
            return
        }
        val payload = listOf(
            encodeField(SessionCredentialsProvider.FIXED_BASE_URL),
            encodeField(credentials.login),
            encodeField(credentials.password),
            "1"
        ).joinToString("|")

        val encrypted = encrypt(payload)
        prefs.edit()
            .putString(KEY_PAYLOAD, encrypted)
            .apply()
    }

    @Synchronized
    fun load(): SavedCredentials? {
        val encrypted = prefs.getString(KEY_PAYLOAD, null) ?: return null
        val decrypted = runCatching { decrypt(encrypted) }.getOrElse {
            clear()
            return null
        }
        val parts = decrypted.split("|")
        if (parts.size < 4) {
            clear()
            return null
        }
        val login = decodeField(parts[1])
        val password = decodeField(parts[2])
        val rememberMe = parts[3] == "1"
        if (login.isBlank() || password.isEmpty() || !rememberMe) {
            clear()
            return null
        }
        return SavedCredentials(
            baseUrl = SessionCredentialsProvider.FIXED_BASE_URL,
            login = login,
            password = password,
            rememberMe = true
        )
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val dataB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        return "$ivB64:$dataB64"
    }

    private fun decrypt(encoded: String): String {
        val sep = encoded.indexOf(':')
        require(sep > 0) { "Invalid encrypted payload" }
        val iv = Base64.decode(encoded.substring(0, sep), Base64.NO_WRAP)
        val cipherBytes = Base64.decode(encoded.substring(sep + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
        val plainBytes = cipher.doFinal(cipherBytes)
        return String(plainBytes, StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encodeField(value: String): String {
        return Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    }

    private fun decodeField(encoded: String): String {
        return String(Base64.decode(encoded, Base64.NO_WRAP), StandardCharsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "intra_phone_session_aes_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PREFS_NAME = "intra_secure_session"
        const val KEY_PAYLOAD = "encrypted_creds"
    }
}
