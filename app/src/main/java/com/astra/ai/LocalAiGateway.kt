package com.astra.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local AI gateway. It can talk to an Ollama/OpenAI-compatible service on loopback or
 * an explicitly configured private LAN address. It never sends local-only requests to
 * a public host.
 */
class LocalAiGateway(private val context: Context) {
    private val prefs = context.getSharedPreferences("astra_local_ai", Context.MODE_PRIVATE)

    fun endpoint(): String = prefs.getString("endpoint", "http://127.0.0.1:11434")!!
    fun model(): String = prefs.getString("model", "qwen2.5:0.5b")!!
    fun configure(endpoint: String, model: String) {
        prefs.edit().putString("endpoint", endpoint.trimEnd('/')).putString("model", model.trim()).apply()
    }

    suspend fun chat(prompt: String): String = withContext(Dispatchers.IO) {
        val base = endpoint()
        val uri = runCatching { URL(base) }.getOrElse { return@withContext "Invalid local AI endpoint." }
        if (!isAllowedHost(uri.host)) return@withContext "Local-only mode blocked a non-local AI endpoint."
        val url = URL("$base/api/chat")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 4000
        connection.readTimeout = 120000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().apply {
            put("model", model())
            put("stream", false)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        }.toString()
        connection.outputStream.use { it.write(body.toByteArray()) }
        if (connection.responseCode !in 200..299) return@withContext "Local AI returned HTTP ${connection.responseCode}."
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        json.optJSONObject("message")?.optString("content")
            ?.takeIf { it.isNotBlank() }
            ?: json.optString("response").takeIf { it.isNotBlank() }
            ?: "Local AI returned an empty response."
    }

    private fun isAllowedHost(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1") return true
        return runCatching {
            val address = InetAddress.getByName(normalized)
            address.isSiteLocalAddress || address.isLinkLocalAddress
        }.getOrDefault(false)
    }
}

class LocalModelManager(context: Context) {
    private val prefs = context.getSharedPreferences("astra_models", Context.MODE_PRIVATE)
    fun models(): List<String> = prefs.getStringSet("models", emptySet())!!.toList().sorted()
    fun addModel(path: String) { prefs.edit().putStringSet("models", (models() + path).toSet()).apply() }
    fun removeModel(path: String) { prefs.edit().putStringSet("models", models().filterNot { it == path }.toSet()).apply() }
}
