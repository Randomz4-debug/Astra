package com.astra.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small dependency-free HTTP server for the Astra LAN UI/API.
 * It binds to the local network interface only when the user enables it.
 */
class AstraLanServer(private val context: Context) {
    private val appContext = context.applicationContext
    private var socket: ServerSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val runtime by lazy { AstraAgentRuntime(appContext) }
    private val registry by lazy { AiProviderRegistry(appContext) }

    fun start(port: Int = 8765): Boolean {
        if (socket != null) return true
        return try {
            socket = ServerSocket(port, 32, InetAddress.getByName("0.0.0.0"))
            job = scope.launch {
                while (true) {
                    val client = socket?.accept() ?: break
                    launch { handle(client) }
                }
            }
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        job?.cancel()
        job = null
    }

    fun isRunning(): Boolean = socket != null

    fun port(): Int = socket?.localPort ?: 8765

    private suspend fun handle(client: Socket) {
        client.use { c ->
            c.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))
            val request = reader.readLine() ?: return
            val parts = request.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", true)) contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
            val body = if (contentLength > 0) CharArray(contentLength).also { reader.read(it) }.concatToString() else ""
            val response = route(method, path, body)
            write(c.getOutputStream(), response.first, response.second)
        }
    }

    private suspend fun route(method: String, rawPath: String, body: String): Pair<Int, String> {
        val path = rawPath.substringBefore("?")
        return when {
            method == "GET" && path == "/" -> 200 to html()
            method == "GET" && path == "/api/status" -> 200 to registry.statusJson()
            method == "GET" && path == "/api/models" -> 200 to registry.discoverJson()
            method == "GET" && path == "/api/providers" -> 200 to registry.providersJson()
            method == "POST" && path == "/api/chat" -> {
                runCatching {
                    val o = JSONObject(body)
                    val prompt = o.optString("prompt")
                    val localOnly = o.optBoolean("localOnly", false)
                    val answer = runtime.handle(prompt, localOnly)
                    200 to JSONObject().put("response", answer).toString()
                }.getOrElse { 400 to JSONObject().put("error", it.message ?: "bad request").toString() }
            }
            method == "POST" && path == "/api/parallel" -> parallel(body)
            method == "POST" && path == "/api/provider/chat" -> providerChat(body)
            method == "POST" && path == "/api/providers" -> addProvider(body)
            method == "DELETE" && path == "/api/providers" -> removeProvider(body)
            method == "GET" && path == "/api/ping" -> 200 to "{\"ok\":true,\"name\":\"Astra\"}"
            else -> 404 to JSONObject().put("error", "not found").toString()
        }
    }

    private suspend fun parallel(body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body)
        val tasks = o.optJSONArray("tasks") ?: JSONArray()
        val parsed = buildList {
            for (i in 0 until tasks.length()) add(tasks.optString(i))
        }.filter { it.isNotBlank() }
        if (parsed.isEmpty()) return@runCatching 400 to JSONObject().put("error", "tasks is empty").toString()
        val results = ParallelAgentOrchestrator(appContext).run(parsed)
        200 to JSONObject().put("results", JSONArray(results)).toString()
    }.getOrElse { 400 to JSONObject().put("error", it.message ?: "bad request").toString() }

    private suspend fun providerChat(body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body)
        val id = o.getString("providerId")
        val prompt = o.getString("prompt")
        val result = registry.chat(id, prompt)
        200 to JSONObject().put("response", result).toString()
    }.getOrElse { 400 to JSONObject().put("error", it.message ?: "provider request failed").toString() }

    private fun addProvider(body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body)
        registry.addProvider(o.getString("name"), o.getString("baseUrl"), o.optString("apiKey"))
        200 to registry.providersJson()
    }.getOrElse { 400 to JSONObject().put("error", it.message ?: "invalid provider").toString() }

    private fun removeProvider(body: String): Pair<Int, String> = runCatching {
        registry.removeProvider(JSONObject(body).getString("id"))
        200 to registry.providersJson()
    }.getOrElse { 400 to JSONObject().put("error", it.message ?: "invalid provider").toString() }

    private fun write(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "Internal Server Error" }
        val headers = "HTTP/1.1 $code $status\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(headers.toByteArray(StandardCharsets.UTF_8)); out.write(bytes); out.flush()
    }

    private fun html(): String = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Astra LAN</title>
<style>body{font-family:system-ui;background:#08090d;color:#eee;max-width:900px;margin:auto;padding:20px}button,input,textarea{font:inherit;padding:10px;margin:5px;border-radius:8px;border:1px solid #444;background:#151821;color:#fff}button{cursor:pointer}textarea{width:95%;min-height:90px}#out{white-space:pre-wrap;background:#10131a;padding:15px;border-radius:12px}</style></head>
<body><h1>ASTRA LAN</h1><p>Connected to this Astra device over Wi-Fi/LAN.</p>
<textarea id="p" placeholder="Ask Astra..."></textarea><br><button onclick="ask()">Send</button><button onclick="models()">Discover AIs / Models</button><button onclick="parallel()">Run Parallel Tasks</button><div id="out"></div>
<script>
async function ask(){let r=await fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({prompt:p.value})});out.textContent=JSON.stringify(await r.json(),null,2)}
async function models(){let r=await fetch('/api/models');out.textContent=JSON.stringify(await r.json(),null,2)}
async function parallel(){let t=p.value.split('\n').filter(Boolean);let r=await fetch('/api/parallel',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({tasks:t})});out.textContent=JSON.stringify(await r.json(),null,2)}
</script></body></html>"""
}
