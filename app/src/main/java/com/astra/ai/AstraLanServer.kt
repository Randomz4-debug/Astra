package com.astra.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/** Small dependency-free HTTP server for the Astra LAN UI/API. */
class AstraLanServer(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var socket: ServerSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtime by lazy { AstraAgentRuntime(appContext) }
    private val registry by lazy { AiProviderRegistry(appContext) }

    fun start(port: Int = 8765): Boolean = synchronized(lock) {
        if (socket?.isClosed == false) return true
        stopLocked()
        return try {
            val server = ServerSocket(port, 32, InetAddress.getByName("0.0.0.0"))
            server.reuseAddress = true
            socket = server
            job = scope.launch {
                try {
                    while (true) {
                        val client = server.accept()
                        launch { handle(client) }
                    }
                } catch (_: java.net.SocketException) {
                    // Normal when stop() closes the server socket.
                } catch (_: Throwable) {
                    // Never let an accept-loop failure crash the app process.
                }
            }
            true
        } catch (_: Throwable) {
            stopLocked()
            false
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        runCatching { socket?.close() }
        socket = null
        job?.cancel()
        job = null
    }

    fun isRunning(): Boolean = synchronized(lock) { socket?.isClosed == false }
    fun port(): Int = synchronized(lock) { socket?.localPort ?: 8765 }

    private suspend fun handle(client: Socket) {
        runCatching {
            client.use { c ->
                c.soTimeout = 15000
                val reader = BufferedReader(InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))
                val request = reader.readLine() ?: return
                val parts = request.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
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
    }

    private suspend fun route(method: String, rawPath: String, body: String): Pair<Int, String> {
        val path = rawPath.substringBefore("?")
        return when {
            method == "OPTIONS" -> 204 to ""
            method == "GET" && path == "/" -> 200 to html()
            method == "GET" && path == "/api/status" -> 200 to registry.statusJson()
            method == "GET" && path == "/api/models" -> 200 to registry.discoverJson()
            method == "GET" && path == "/api/providers" -> 200 to registry.providersJson()
            method == "POST" && path == "/api/chat" -> runCatching { val o = JSONObject(body); 200 to JSONObject().put("response", runtime.handle(o.optString("prompt"), o.optBoolean("localOnly", false))).toString() }.getOrElse { 400 to JSONObject().put("error", it.message ?: "bad request").toString() }
            method == "POST" && path == "/api/parallel" -> parallel(body)
            method == "POST" && path == "/api/provider/chat" -> providerChat(body)
            method == "POST" && path == "/api/providers" -> addProvider(body)
            method == "DELETE" && path == "/api/providers" -> removeProvider(body)
            method == "GET" && path == "/api/ping" -> 200 to "{\"ok\":true,\"name\":\"Astra\"}"
            else -> 404 to JSONObject().put("error", "not found").toString()
        }
    }

    private suspend fun parallel(body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body); val tasks = o.optJSONArray("tasks") ?: JSONArray()
        val specs = buildList {
            for (i in 0 until tasks.length()) {
                val item = tasks.opt(i)
                when (item) {
                    is JSONObject -> if (item.optString("task").isNotBlank()) add(item)
                    else -> if (item.toString().isNotBlank()) add(JSONObject().put("task", item.toString()))
                }
            }
        }
        if (specs.isEmpty()) return@runCatching 400 to JSONObject().put("error", "tasks is empty").toString()
        200 to JSONObject().put("results", JSONArray(ParallelAgentOrchestrator(appContext).runSpecs(specs))).toString()
    }.getOrElse { 400 to JSONObject().put("error", it.message ?: "bad request").toString() }

    private suspend fun providerChat(body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body); 200 to JSONObject().put("response", registry.chat(o.getString("providerId"), o.getString("prompt"))).toString()
    }.getOrElse { 400 to JSONObject().put("error", it.message ?: "provider request failed").toString() }

    private fun addProvider(body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body); registry.addProvider(o.getString("name"), o.getString("baseUrl"), o.optString("apiKey")); 200 to registry.providersJson()
    }.getOrElse { 400 to JSONObject().put("error", it.message ?: "invalid provider").toString() }

    private fun removeProvider(body: String): Pair<Int, String> = runCatching {
        registry.removeProvider(JSONObject(body).getString("id")); 200 to registry.providersJson()
    }.getOrElse { 400 to JSONObject().put("error", it.message ?: "invalid provider").toString() }

    private fun write(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "Internal Server Error" }
        val type = if (body.trimStart().startsWith("<!doctype html", true) || body.trimStart().startsWith("<html", true)) "text/html" else "application/json"
        val headers = "HTTP/1.1 $code $status\r\nContent-Type: $type; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,DELETE,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type,Authorization\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(headers.toByteArray(StandardCharsets.UTF_8)); out.write(bytes); out.flush()
    }

    private fun html(): String = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Astra LAN</title><style>body{font-family:system-ui;background:#08090d;color:#eee;max-width:950px;margin:auto;padding:20px}button,input,textarea,select{font:inherit;padding:10px;margin:5px;border-radius:8px;border:1px solid #444;background:#151821;color:#fff}button{cursor:pointer}textarea{width:95%;min-height:90px}#out{white-space:pre-wrap;background:#10131a;padding:15px;border-radius:12px}</style></head><body><h1>ASTRA LAN</h1><p>All devices on the same Wi-Fi can use this assistant and its API.</p><select id="model"><option value="">Auto-select best available model</option></select><br><textarea id="p" placeholder="Ask Astra..."></textarea><br><input id="instruction" placeholder="Optional sub-agent/custom prompt"><br><button onclick="ask()">Send</button><button onclick="discover()">Discover AIs / Models</button><button onclick="parallel()">Run Parallel Sub-AIs</button><div id="out"></div><script>let discovered=[];async function discover(){let r=await fetch('/api/models');discovered=await r.json();model.innerHTML='<option value="">Auto-select best available model</option>'+discovered.filter(x=>x.id).map(x=>'<option value="'+x.providerId+'">'+x.providerName+' / '+x.id+'</option>').join('');out.textContent=JSON.stringify(discovered,null,2)}async function ask(){let r=model.value?await fetch('/api/provider/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({providerId:model.value,prompt:p.value})}):await fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({prompt:p.value})});out.textContent=JSON.stringify(await r.json(),null,2)}async function parallel(){let lines=p.value.split('\n').filter(Boolean);let tasks=lines.map(x=>({task:x,instruction:instruction.value}));let r=await fetch('/api/parallel',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({tasks:tasks})});out.textContent=JSON.stringify(await r.json(),null,2)}discover();</script></body></html>"""
}
