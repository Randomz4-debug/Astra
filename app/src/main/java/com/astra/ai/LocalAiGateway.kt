package com.astra.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local/LAN gateway with Ollama-first handling and OpenAI-compatible fallback. */
class LocalAiGateway(private val context: Context) {
    private val prefs = context.getSharedPreferences("astra_local_ai", Context.MODE_PRIVATE)

    fun endpoint(): String = prefs.getString("endpoint", "http://127.0.0.1:11434")!!
    fun model(): String = prefs.getString("model", "")!!
    fun configure(endpoint: String, model: String) {
        prefs.edit().putString("endpoint", normalize(endpoint)).putString("model", model.trim()).apply()
    }

    suspend fun discoverOllamaModels(): List<String> = withContext(Dispatchers.IO) {
        val base = normalize(endpoint())
        if (!isAllowedHost(URL(base).host)) return@withContext emptyList()
        val text = request("$base/api/tags", "GET", null) ?: return@withContext emptyList()
        val arr = runCatching { JSONObject(text).optJSONArray("models") }.getOrNull() ?: return@withContext emptyList()
        buildList { for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add) }
    }

    suspend fun chat(prompt: String): String = withContext(Dispatchers.IO) {
        val base = normalize(endpoint())
        val uri = runCatching { URL(base) }.getOrElse { return@withContext "Invalid local AI endpoint." }
        if (!isAllowedHost(uri.host)) return@withContext "Local-only mode blocked a non-local AI endpoint."
        val ollama = uri.port == 11434 || base.contains("ollama", true)
        val chosen = model().ifBlank { discoverOllamaModels().firstOrNull().orEmpty() }
        if (chosen.isBlank()) return@withContext "No local model found. Start Ollama and run `ollama pull <model>`, then tap Discover Models."
        val endpoint = if (ollama) "$base/api/chat" else "$base/v1/chat/completions"
        val body = JSONObject().put("model", chosen).put("stream", false).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt))).toString()
        val response = request(endpoint, "POST", body) ?: return@withContext "Local AI is unreachable. Check that Ollama is running and reachable on the selected Wi-Fi/LAN address."
        val json = runCatching { JSONObject(response) }.getOrElse { return@withContext "Local AI returned invalid JSON." }
        if (json.optString("error").isNotBlank()) return@withContext "Local AI error: ${json.optString("error")}"
        json.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }
            ?: json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }
            ?: json.optString("response").takeIf { it.isNotBlank() }
            ?: "Local AI returned an empty response."
    }

    private fun normalize(value: String): String = value.trim().trimEnd('/').removeSuffix("/api").removeSuffix("/v1")
    private fun request(url: String, method: String, body: String?): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 5000; c.readTimeout = 120000
        c.setRequestProperty("Accept", "application/json")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        stream?.bufferedReader()?.use { it.readText() }.also { c.disconnect() }
    }.getOrNull()

    private fun isAllowedHost(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1") return true
        return runCatching { InetAddress.getByName(normalized).let { it.isSiteLocalAddress || it.isLinkLocalAddress } }.getOrDefault(false)
    }
}

class LocalModelManager(context: Context) {
    private val prefs = context.getSharedPreferences("astra_models", Context.MODE_PRIVATE)
    fun models(): List<String> = prefs.getStringSet("models", emptySet())!!.toList().sorted()
    fun addModel(path: String) { prefs.edit().putStringSet("models", (models() + path).toSet()).apply() }
    fun removeModel(path: String) { prefs.edit().putStringSet("models", models().filterNot { it == path }.toSet()).apply() }
}
