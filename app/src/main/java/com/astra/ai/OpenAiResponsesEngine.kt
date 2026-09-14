package com.astra.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud text provider. It is never used by LocalAiEngine or local-only builds unless
 * the user explicitly selects cloud mode and has stored an API key.
 */
class OpenAiResponsesEngine(context: Context) : AiEngine {
    private val settings = OpenAiSettings(context.applicationContext)

    override suspend fun respond(input: String): String = withContext(Dispatchers.IO) {
        val key = settings.apiKey() ?: return@withContext "OpenAI is not configured. Add your API key in Astra's OpenAI settings."
        val connection = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val body = JSONObject().apply {
                put("model", settings.model())
                put("input", input)
            }.toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) return@withContext "OpenAI request failed (${connection.responseCode})."
            extractText(JSONObject(response))
        } catch (_: Exception) {
            "OpenAI is unavailable right now."
        } finally {
            connection.disconnect()
        }
    }

    private fun extractText(root: JSONObject): String {
        root.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = root.optJSONArray("output") ?: return "No response text was returned."
        val parts = mutableListOf<String>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                part.optString("text").takeIf { it.isNotBlank() }?.let(parts::add)
            }
        }
        return parts.joinToString("\n").ifBlank { "No response text was returned." }
    }
}
