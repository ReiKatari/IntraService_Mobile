package com.lantaservice.intra.data.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lantaservice.intra.data.network.SessionCredentialsProvider
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64 as JavaBase64
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
 * Pure JVM codec for serializing and deserializing remembered credential payloads.
 */
object CredentialsPayloadCodec {

    fun encode(credentials: SavedCredentials): String {
        return listOf(
            encodeField(SessionCredentialsProvider.FIXED_BASE_URL),
            encodeField(credentials.login),
            encodeField(credentials.password),
            if (credentials.rememberMe) "1" else "0"
        ).joinToString("|")
    }

    fun decode(payload: String): SavedCredentials? {
        val parts = payload.split("|")
        if (parts.size < 4) return null
        return runCatching {
            val login = decodeField(parts[1])
            val password = decodeField(parts[2])
            val rememberMe = parts[3] == "1"
            SavedCredentials(
                baseUrl = SessionCredentialsProvider.FIXED_BASE_URL,
                login = login,
                password = password,
                rememberMe = rememberMe
            )
        }.getOrNull()
    }

    private fun encodeField(value: String): String {
        return JavaBase64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeField(encoded: String): String {
        return String(JavaBase64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
    }
}

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
        val payload = CredentialsPayloadCodec.encode(credentials)
        val encrypted = encryptWithKeyRecovery(payload)
        prefs.edit()
            .putString(KEY_PAYLOAD, encrypted)
            .putBoolean(KEY_REMEMBER_ME, true)
            .commit()
    }

    @Synchronized
    fun load(): SavedCredentials? {
        val hasRememberFlag = prefs.contains(KEY_REMEMBER_ME)
        val rememberFlag = prefs.getBoolean(KEY_REMEMBER_ME, true)
        if (hasRememberFlag && !rememberFlag) {
            if (prefs.contains(KEY_PAYLOAD)) {
                prefs.edit().remove(KEY_PAYLOAD).commit()
            }
            return SavedCredentials(
                baseUrl = SessionCredentialsProvider.FIXED_BASE_URL,
                login = "",
                password = "",
                rememberMe = false
            )
        }

        val encrypted = prefs.getString(KEY_PAYLOAD, null) ?: return null
        val decrypted = runCatching { decrypt(encrypted) }.getOrElse {
            prefs.edit().remove(KEY_PAYLOAD).commit()
            return null
        }
        val decoded = CredentialsPayloadCodec.decode(decrypted)
        if (decoded == null || !decoded.rememberMe) {
            prefs.edit().remove(KEY_PAYLOAD).commit()
            return null
        }
        return decoded
    }

    @Synchronized
    fun clear() {
        prefs.edit()
            .remove(KEY_PAYLOAD)
            .putBoolean(KEY_REMEMBER_ME, false)
            .commit()
    }

    private fun encryptWithKeyRecovery(plainText: String): String {
        return runCatching { encrypt(plainText) }.getOrElse {
            runCatching {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    keyStore.deleteEntry(KEY_ALIAS)
                }
            }
            encrypt(plainText)
        }
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

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "intra_phone_session_aes_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PREFS_NAME = "intra_secure_session"
        const val KEY_PAYLOAD = "encrypted_creds"
        const val KEY_REMEMBER_ME = "remember_me_enabled"
    }
}
