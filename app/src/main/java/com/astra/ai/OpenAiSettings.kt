package com.astra.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Local OpenAI configuration. The API key is encrypted by Android Keystore via SecureSettings. */
class OpenAiSettings(context: Context) {
    private val appContext = context.applicationContext
    private val secure = SecureSettings(appContext)
    private val prefs = appContext.getSharedPreferences("astra_openai", Context.MODE_PRIVATE)

    fun apiKey(): String? = secure.openAiApiKey()
    fun hasApiKey(): Boolean = !apiKey().isNullOrBlank()

    fun saveApiKey(value: String) {
        secure.setOpenAiApiKey(value.trim())
    }

    fun clearApiKey() {
        secure.setOpenAiApiKey("")
    }

    fun model(): String = prefs.getString("model", "gpt-5.6-luna") ?: "gpt-5.6-luna"

    fun setModel(value: String) {
        prefs.edit().putString("model", value.trim().ifBlank { "gpt-5.6-luna" }).apply()
    }

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: return@withContext "Not configured — enter your OpenAI API key first."
        runCatching {
            val connection = (URL("https://api.openai.com/v1/models").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", "application/json")
            }
            try {
                when (connection.responseCode) {
                    in 200..299 -> "Connected to OpenAI."
                    401, 403 -> "OpenAI rejected the API key. Check the key and account permissions."
                    429 -> "OpenAI responded with rate/billing limits. Check your API usage and billing."
                    else -> "OpenAI connection failed (${connection.responseCode})."
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { "Connection failed: ${it.message ?: "network error"}" }
    }
}
