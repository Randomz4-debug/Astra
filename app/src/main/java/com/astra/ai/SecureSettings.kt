package com.astra.ai

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class SecureSettings(context: Context) {
    private val prefs = context.getSharedPreferences("astra_secure", Context.MODE_PRIVATE)
    private val alias = "AstraCloudKey"

    private fun key(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    fun setOpenAiApiKey(value: String) {
        if (value.isBlank()) { prefs.edit().remove("openai_key").remove("openai_iv").apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        prefs.edit().putString("openai_key", encrypted).putString("openai_iv", iv).apply()
    }

    /** Fast UI-safe presence check. It never opens Android Keystore or decrypts the secret. */
    fun hasOpenAiApiKey(): Boolean = !prefs.getString("openai_key", null).isNullOrBlank()

    /** Decryption is deliberately kept for actual API work, which callers should perform off the main thread. */
    fun openAiApiKey(): String? = runCatching {
        val encrypted = prefs.getString("openai_key", null) ?: return null
        val ivText = prefs.getString("openai_iv", null) ?: return null
        val iv = Base64.decode(ivText, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }.getOrNull()
}
