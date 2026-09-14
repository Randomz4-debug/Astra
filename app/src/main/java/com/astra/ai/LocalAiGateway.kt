package com.astra.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local/LAN AI gateway for Ollama and OpenAI-compatible local servers. */
class LocalAiGateway(private val context: Context) {
    private val prefs = context.getSharedPreferences("astra_local_ai", Context.MODE_PRIVATE)

    fun endpoint(): String = prefs.getString("endpoint", "http://127.0.0.1:11434")!!
    fun model(): String = prefs.getString("model", "")!!
    fun configure(endpoint: String, model: String) { prefs.edit().putString("endpoint", normalize(endpoint)).putString("model", model.trim()).apply() }

    suspend fun discoverOllamaModels(): List<String> = withContext(Dispatchers.IO) {
        val result = linkedSetOf<String>()
        for (base in endpointCandidates(endpoint())) {
            val host = runCatching { URL(base).host }.getOrNull() ?: continue
            if (!isAllowedHost(host)) continue
            val text = request("$base/api/tags", "GET", null) ?: continue
            parseModelIds(text).forEach(result::add)
            if (result.isNotEmpty()) break
        }
        result.toList()
    }

    suspend fun discoverModels(): List<String> = withContext(Dispatchers.IO) {
        val result = linkedSetOf<String>()
        for (base in endpointCandidates(endpoint())) {
            val host = runCatching { URL(base).host }.getOrNull() ?: continue
            if (!isAllowedHost(host)) continue
            request("$base/api/tags", "GET", null)?.let { parseModelIds(it).forEach(result::add) }
            request("$base/v1/models", "GET", null)?.let { parseModelIds(it).forEach(result::add) }
        }
        result.toList()
    }

    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        val configured = normalize(endpoint())
        for (base in endpointCandidates(configured)) {
            val host = runCatching { URL(base).host }.getOrNull() ?: continue
            if (!isAllowedHost(host)) continue
            request("$base/api/tags", "GET", null)?.let {
                val count = parseModelIds(it).size
                return@withContext if (count > 0) "Connected to Ollama at $base • $count model(s) found." else "Connected to Ollama at $base, but no models are installed. Run `ollama pull <model>`."
            }
            request("$base/v1/models", "GET", null)?.let {
                val count = parseModelIds(it).size
                return@withContext if (count > 0) "Connected to OpenAI-compatible server at $base • $count model(s) found." else "Server is reachable at $base, but it returned no models."
            }
        }
        if (configured.contains(":12434")) return@withContext "Could not reach $configured. Astra also tried the standard Ollama port 11434. If Ollama is running on your PC, use http://YOUR-PC-IP:11434 and allow Ollama through Windows Firewall."
        "Could not reach $configured. Make sure Ollama is running, the phone and PC are on the same Wi-Fi, and the server accepts LAN connections."
    }

    suspend fun chat(prompt: String): String = withContext(Dispatchers.IO) {
        val candidates = endpointCandidates(endpoint())
        var lastError = "Local AI is unreachable."
        for (base in candidates) {
            val uri = runCatching { URL(base) }.getOrNull()
            if (uri == null) { lastError = "Invalid local AI endpoint."; continue }
            if (!isAllowedHost(uri.host)) { lastError = "Local-only mode blocked a non-local AI endpoint."; continue }
            val models = discoverModelsFromBase(base)
            val chosen = model().ifBlank { models.firstOrNull().orEmpty() }
            if (chosen.isBlank()) { lastError = "No local model found. Install a model in Ollama, then tap Discover Models."; continue }
            val isOllama = request("$base/api/tags", "GET", null) != null || uri.port == 11434 || base.contains("ollama", true)
            val target = if (isOllama) "$base/api/chat" else "$base/v1/chat/completions"
            val body = JSONObject().put("model", chosen).put("stream", false).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt))).toString()
            val response = request(target, "POST", body)
            if (response == null) { lastError = "Local AI at $base could not answer."; continue }
            val json = runCatching { JSONObject(response) }.getOrNull()
            if (json == null) { lastError = "Local AI returned invalid JSON."; continue }
            if (json.optString("error").isNotBlank()) { lastError = "Local AI error: ${json.optString("error")}"; continue }
            return@withContext json.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }
                ?: json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }
                ?: json.optString("response").takeIf { it.isNotBlank() }
                ?: "Local AI returned an empty response."
        }
        lastError
    }

    private fun discoverModelsFromBase(base: String): List<String> {
        val native = request("$base/api/tags", "GET", null)
        if (native != null) {
            val models = parseModelIds(native)
            if (models.isNotEmpty()) return models
        }
        return parseModelIds(request("$base/v1/models", "GET", null).orEmpty())
    }

    private fun endpointCandidates(value: String): List<String> {
        val clean = normalize(value)
        val out = linkedSetOf(clean)
        runCatching {
            val u = URL(clean)
            if (u.port == 12434) out.add(URL(u.protocol, u.host, 11434, "").toString().trimEnd('/'))
        }
        return out.toList()
    }

    private fun parseModelIds(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val source = root.optJSONArray("models") ?: root.optJSONArray("data") ?: return emptyList()
        val out = linkedSetOf<String>()
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            val id = item.optString("name").ifBlank { item.optString("id") }
            if (id.isNotBlank()) out.add(id)
        }
        return out.toList()
    }

    private fun normalize(value: String): String = value.trim().trimEnd('/').removeSuffix("/api").removeSuffix("/v1")

    private fun request(url: String, method: String, body: String?): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 4000; c.readTimeout = 120000
        c.setRequestProperty("Accept", "application/json")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
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
