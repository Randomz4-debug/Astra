package com.astra.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Discovers OpenAI-compatible and Ollama-compatible local/LAN AI providers. */
class AiProviderRegistry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("astra_ai_providers", Context.MODE_PRIVATE)
    init { if (!prefs.contains("providers")) addProviderInternal("Ollama localhost", "http://127.0.0.1:11434", "") }
    fun addProvider(name: String, baseUrl: String, apiKey: String) { val clean = baseUrl.trim().trimEnd('/'); require(clean.startsWith("http://") || clean.startsWith("https://")); val arr = JSONArray(prefs.getString("providers", "[]")); arr.put(JSONObject().put("id", UUID.randomUUID().toString()).put("name", name).put("baseUrl", clean).put("apiKey", apiKey)); prefs.edit().putString("providers", arr.toString()).apply() }
    private fun addProviderInternal(name: String, baseUrl: String, apiKey: String) = addProvider(name, baseUrl, apiKey)
    fun removeProvider(id: String) { val old = JSONArray(prefs.getString("providers", "[]")); val out = JSONArray(); for (i in 0 until old.length()) if (old.getJSONObject(i).optString("id") != id) out.put(old.getJSONObject(i)); prefs.edit().putString("providers", out.toString()).apply() }
    private fun providers(): List<JSONObject> { val arr = JSONArray(prefs.getString("providers", "[]")); return buildList { for (i in 0 until arr.length()) add(arr.getJSONObject(i)) } }
    fun providersJson(): String { val out = JSONArray(); for (p in providers()) out.put(JSONObject().put("id", p.optString("id")).put("name", p.optString("name")).put("baseUrl", p.optString("baseUrl"))); return out.toString() }

    suspend fun discoverJson(): String = withContext(Dispatchers.IO) {
        val all = JSONArray()
        for (provider in providers()) {
            val base = provider.optString("baseUrl").trimEnd('/'); val key = provider.optString("apiKey").takeIf { it.isNotBlank() }
            val urls = if (base.contains("11434") || base.endsWith("/ollama")) listOf("$base/api/tags") else listOf("$base/v1/models", "$base/models", "$base/api/tags")
            var found = false
            for (url in urls) {
                val result = request(url, key, null, "GET") ?: continue; val models = parseModels(result)
                if (models.length() > 0) { for (i in 0 until models.length()) { val m = models.getJSONObject(i); m.put("providerId", provider.optString("id")); m.put("providerName", provider.optString("name")); all.put(m) }; found = true; break }
            }
            if (!found) all.put(JSONObject().put("providerId", provider.optString("id")).put("providerName", provider.optString("name")).put("error", "No models discovered"))
        }
        all.toString()
    }

    suspend fun chat(id: String, prompt: String): String = withContext(Dispatchers.IO) {
        val p = providers().firstOrNull { it.optString("id") == id } ?: error("Unknown provider"); val base = p.optString("baseUrl").trimEnd('/'); val key = p.optString("apiKey").takeIf { it.isNotBlank() }
        val models = parseModels(request(if (base.contains("11434")) "$base/api/tags" else "$base/v1/models", key, null, "GET") ?: ""); val model = bestModel(models); require(model.isNotBlank()) { "No model available" }
        val payload = JSONObject().put("model", model).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt))).put("stream", false).toString(); val endpoint = if (base.contains("11434")) "$base/api/chat" else "$base/v1/chat/completions"
        parseChat(request(endpoint, key, payload, "POST") ?: error("Provider unavailable"))
    }
    fun statusJson(): String = JSONObject().put("running", true).put("providers", providers().size).toString()
    private fun bestModel(models: JSONArray): String { var best = ""; var bestScore = Int.MIN_VALUE; for (i in 0 until models.length()) { val id = models.optJSONObject(i)?.optString("id") ?: continue; val n = id.lowercase(); var score = 0; if (n.contains("instruct") || n.contains("chat")) score += 30; if (n.contains("latest")) score += 20; if (n.contains("reason") || n.contains("thinking")) score += 15; Regex("(\\d+)b").find(n)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { score += it.coerceAtMost(32) }; if (score > bestScore) { bestScore = score; best = id } }; return best.ifBlank { models.optJSONObject(0)?.optString("id").orEmpty() } }
    private fun parseModels(text: String): JSONArray { val o = runCatching { JSONObject(text) }.getOrNull() ?: return JSONArray(); val source = o.optJSONArray("models") ?: o.optJSONArray("data") ?: return JSONArray(); val out = JSONArray(); for (i in 0 until source.length()) { val item = source.optJSONObject(i) ?: continue; val id = item.optString("name").ifBlank { item.optString("id") }; if (id.isNotBlank()) out.put(JSONObject().put("id", id).put("name", id)) }; return out }
    private fun parseChat(text: String): String = JSONObject(text).optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() } ?: JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() } ?: JSONObject(text).optString("response").takeIf { it.isNotBlank() } ?: "No response returned."
    private fun request(url: String, key: String?, body: String?, method: String): String? = runCatching { val c = URL(url).openConnection() as HttpURLConnection; c.requestMethod = method; c.connectTimeout = 5000; c.readTimeout = 30000; c.setRequestProperty("Accept", "application/json"); if (!key.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $key"); if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }; val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream; stream?.bufferedReader()?.use { it.readText() }.also { c.disconnect() } }.getOrNull()
}
