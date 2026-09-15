package com.astra.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Generic user-configured REST API registry. Supports any number of APIs and common HTTP methods. */
data class AstraApiDefinition(
    val name: String,
    val description: String,
    val baseUrl: String,
    val defaultPath: String,
    val method: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val enabled: Boolean = true
)

data class AstraApiResult(val ok: Boolean, val status: Int, val body: String, val error: String = "")

class AstraApiHub(private val context: Context) {
    private val prefs = context.getSharedPreferences("astra_api_hub", Context.MODE_PRIVATE)

    fun all(): List<AstraApiDefinition> = runCatching {
        val arr = JSONArray(prefs.getString("apis", "[]"))
        buildList {
            for (i in 0 until arr.length()) add(fromJson(arr.getJSONObject(i)))
        }
    }.getOrDefault(emptyList())

    fun save(api: AstraApiDefinition) {
        val list = all().filterNot { it.name.equals(api.name, true) } + api.copy(name = api.name.trim())
        persist(list)
    }

    fun delete(name: String) = persist(all().filterNot { it.name.equals(name.trim(), true) })

    fun find(name: String): AstraApiDefinition? = all().firstOrNull { it.name.equals(name.trim(), true) }
        ?: all().firstOrNull { it.name.contains(name.trim(), true) }

    fun catalog(): String = all().filter { it.enabled }.joinToString("\n") {
        "- ${it.name}: ${it.description.ifBlank { "custom REST API" }} | ${it.method.uppercase()} ${it.baseUrl}${it.defaultPath}"
    }.ifBlank { "(no custom APIs configured)" }

    fun execute(name: String, path: String? = null, method: String? = null, body: String? = null): AstraApiResult {
        val api = find(name) ?: return AstraApiResult(false, 0, "", "API '$name' is not configured.")
        if (!api.enabled) return AstraApiResult(false, 0, "", "API '$name' is disabled.")
        val requestMethod = (method?.takeIf { it.isNotBlank() } ?: api.method).uppercase()
        val targetPath = path?.takeIf { it.isNotBlank() } ?: api.defaultPath.ifBlank { "/" }
        val base = api.baseUrl.trimEnd('/')
        val cleanPath = if (targetPath.startsWith("http://") || targetPath.startsWith("https://")) targetPath else if (targetPath.startsWith("/")) targetPath else "/$targetPath"
        val url = if (cleanPath.startsWith("http")) cleanPath else base + cleanPath
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = requestMethod
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            api.headers.forEach { (key, value) -> if (key.isNotBlank()) connection.setRequestProperty(key, value) }
            val outgoingBody = body?.takeIf { it.isNotBlank() } ?: api.body
            if (requestMethod in setOf("POST", "PUT", "PATCH", "DELETE") && outgoingBody.isNotBlank()) {
                connection.doOutput = true
                if (connection.getRequestProperty("Content-Type").isNullOrBlank()) connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(outgoingBody.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty().take(120000)
            connection.disconnect()
            AstraApiResult(status in 200..299, status, text, if (status !in 200..299) "HTTP $status" else "")
        }.getOrElse { AstraApiResult(false, 0, "", it.message ?: "Network request failed") }
    }

    private fun persist(list: List<AstraApiDefinition>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString("apis", arr.toString()).apply()
    }

    private fun toJson(api: AstraApiDefinition) = JSONObject().apply {
        put("name", api.name); put("description", api.description); put("baseUrl", api.baseUrl)
        put("defaultPath", api.defaultPath); put("method", api.method); put("body", api.body); put("enabled", api.enabled)
        put("headers", JSONObject(api.headers))
    }

    private fun fromJson(o: JSONObject) = AstraApiDefinition(
        name = o.optString("name"), description = o.optString("description"), baseUrl = o.optString("baseUrl"),
        defaultPath = o.optString("defaultPath", "/"), method = o.optString("method", "GET"), body = o.optString("body"),
        enabled = o.optBoolean("enabled", true), headers = buildMap {
            val h = o.optJSONObject("headers") ?: return@buildMap
            h.keys().forEach { key -> put(key, h.optString(key)) }
        }
    )
}
