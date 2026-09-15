package com.astra.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Local OpenAI configuration. API secrets remain encrypted with Android Keystore. */
class OpenAiSettings(context: Context) {
    private val appContext = context.applicationContext
    private val secure = SecureSettings(appContext)
    private val prefs = appContext.getSharedPreferences("astra_openai", Context.MODE_PRIVATE)

    /** Decrypt only when an actual API operation needs the secret. */
    fun apiKey(): String? = secure.openAiApiKey()
    /** Fast UI-safe check: only checks whether an encrypted value exists. */
    fun hasApiKey(): Boolean = secure.hasOpenAiApiKey()

    fun saveApiKey(value: String) { secure.setOpenAiApiKey(value.trim()) }
    fun clearApiKey() { secure.setOpenAiApiKey("") }

    fun model(): String = prefs.getString("model", "gpt-5.6-luna") ?: "gpt-5.6-luna"
    fun setModel(value: String) { prefs.edit().putString("model", value.trim().ifBlank { "gpt-5.6-luna" }).apply() }

    /** Explicit model discovery; the network request is always on Dispatchers.IO. */
    suspend fun discoverModels(): List<String> = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        runCatching {
            val connection = (URL("https://api.openai.com/v1/models").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching emptyList<String>()
                val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val data = root.optJSONArray("data") ?: return@runCatching emptyList<String>()
                buildList { for (i in 0 until data.length()) data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add) }.distinct().sorted()
            } finally { connection.disconnect() }
        }.getOrDefault(emptyList())
    }

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return@withContext "Not configured — enter your OpenAI API key first."
        runCatching {
            val connection = (URL("https://api.openai.com/v1/models").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 12_000
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
            } finally { connection.disconnect() }
        }.getOrElse { "Connection failed: ${it.message ?: "network error"}" }
    }
}
