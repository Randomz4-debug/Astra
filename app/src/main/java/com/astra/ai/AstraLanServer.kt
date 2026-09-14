package com.astra.ai

import android.util.Base64
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

/** Dependency-free HTTP server for Astra's bundled and LAN Web UI. */
class AstraLanServer(context: Context) {
    private val appContext = context.applicationContext
    private val runtime by lazy { AstraAgentRuntime(appContext) }
    private val registry by lazy { AiProviderRegistry(appContext) }
    private val files by lazy { AstraFileIntake(appContext) }
    private val tasks by lazy { AstraTaskManager(appContext) }

    companion object {
        private val lock = Any()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var socket: ServerSocket? = null
        private var job: Job? = null
    }

    fun start(port: Int = 8765): Boolean = synchronized(lock) {
        if (socket?.isClosed == false) return true
        runCatching {
            val server = ServerSocket(port, 32, InetAddress.getByName("0.0.0.0")).apply { reuseAddress = true }
            socket = server
            job = scope.launch {
                try {
                    while (!server.isClosed) {
                        val client = server.accept()
                        launch { handle(client) }
                    }
                } catch (_: java.net.SocketException) { }
                catch (_: Throwable) { }
            }
            true
        }.getOrElse { socket = null; job?.cancel(); job = null; false }
    }

    fun stop() = synchronized(lock) {
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
                c.soTimeout = 30_000
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
                if (contentLength > 15 * 1024 * 1024) { write(c.getOutputStream(), 413, JSONObject().put("error", "request too large").toString()); return }
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
            method == "GET" && path == "/" -> { start(8765); 200 to html() }
            method == "GET" && path == "/api/status" -> 200 to registry.statusJson()
            method == "GET" && path == "/api/models" -> runCatching { 200 to registry.discoverJson() }.getOrElse { 500 to JSONObject().put("error", it.message ?: "model discovery failed").toString() }
            method == "GET" && path == "/api/providers" -> 200 to registry.providersJson()
            method == "GET" && path == "/api/files" -> 200 to listFilesJson()
            method == "POST" && path == "/api/files" -> importFiles(body)
            method == "GET" && path == "/api/tasks" -> 200 to tasksJson()
            method == "POST" && path == "/api/tasks" -> startTask(body)
            method == "DELETE" && path.startsWith("/api/tasks/") -> { tasks.stop(path.substringAfterLast('/')); 200 to tasksJson() }
            method == "POST" && path == "/api/chat" -> runCatching { val o = JSONObject(body); 200 to JSONObject().put("response", runtime.handle(o.optString("prompt"), o.optBoolean("localOnly", false))).toString() }.getOrElse { 400 to JSONObject().put("error", it.message ?: "bad request").toString() }
            method == "POST" && path == "/api/parallel" -> parallel(body)
            method == "POST" && path == "/api/provider/chat" -> providerChat(body)
            method == "POST" && path == "/api/providers" -> addProvider(body)
            method == "DELETE" && path == "/api/providers" -> removeProvider(body)
            method == "GET" && path == "/api/ping" -> 200 to "{\"ok\":true,\"name\":\"Astra\"}"
            else -> 404 to JSONObject().put("error", "not found").toString()
        }
    }

    private fun listFilesJson(): String {
        val out = JSONArray()
        files.list().forEach { out.put(JSONObject().put("name", it.name).put("size", it.length()).put("modified", it.lastModified())) }
        return out.toString()
    }

    private suspend fun importFiles(body: String): Pair<Int, String> = runCatching {
        val arr = JSONObject(body).optJSONArray("files") ?: JSONArray()
        val imported = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val name = item.optString("name", "attachment")
            val mime = item.optString("mime", "application/octet-stream")
            val data = item.optString("data")
            if (data.isBlank()) continue
            val bytes = Base64.decode(data, Base64.DEFAULT)
            val result = files.importBytes(name, mime, bytes)
            if (result != null) imported.put(JSONObject().put("name", result.name).put("size", result.size))
        }
        200 to JSONObject().put("imported", imported).put("count", imported.length()).toString()
    }.getOrElse { 400 to JSONObject().put("error", it.message ?: "file import failed").toString() }

    private fun tasksJson(): String {
        val out = JSONArray()
        tasks.all().forEach { out.put(JSONObject().put("id", it.id).put("prompt", it.prompt).put("step", it.step).put("total", it.total).put("status", it.status).put("result", it.result)) }
        return out.toString()
    }

    private fun startTask(body: String): Pair<Int, String> = runCatching {
        val prompt = JSONObject(body).optString("prompt").trim()
        require(prompt.isNotBlank()) { "prompt is empty" }
        val id = tasks.start(prompt)
        202 to JSONObject().put("id", id).put("status", "queued").toString()
    }.getOrElse { 400 to JSONObject().put("error", it.message ?: "task failed").toString() }

    private suspend fun parallel(body: String): Pair<Int, String> = runCatching {
        val o = JSONObject(body); val tasksArray = o.optJSONArray("tasks") ?: JSONArray()
        val specs = buildList {
            for (i in 0 until tasksArray.length()) {
                val item = tasksArray.opt(i)
                if (item is JSONObject && item.optString("task").isNotBlank()) add(item)
                else if (item.toString().isNotBlank()) add(JSONObject().put("task", item.toString()))
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
        val status = when (code) { 200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"; 400 -> "Bad Request"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Internal Server Error" }
        val type = if (body.trimStart().startsWith("<!doctype html", true) || body.trimStart().startsWith("<html", true)) "text/html" else "application/json"
        val headers = "HTTP/1.1 $code $status\r\nContent-Type: $type; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,DELETE,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type,Authorization\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(headers.toByteArray(StandardCharsets.UTF_8)); out.write(bytes); out.flush()
    }

    private fun html(): String = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Astra</title><style>
:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 50% 0,#2a050c 0,#090a10 38%,#05060a 100%);color:#f4f7ff;font:15px system-ui,sans-serif}main{max-width:980px;margin:auto;padding:14px}.card{background:rgba(15,16,24,.9);border:1px solid #3b1620;border-radius:20px;padding:14px;margin:10px 0;box-shadow:0 8px 35px #0008}.top{display:flex;align-items:center;gap:12px}.logo{width:50px;height:50px;border-radius:50%;display:grid;place-items:center;background:linear-gradient(145deg,#ff203f,#8b0015);border:1px solid #ff6075;color:white;font-weight:900;font-size:25px;box-shadow:0 0 28px #ff12384d}.grow{flex:1}.chat{min-height:42vh;max-height:58vh;overflow:auto}.msg{padding:12px 14px;border-radius:16px;margin:9px 0;white-space:pre-wrap}.u{background:#211d25;margin-left:12%;border:1px solid #3b3440}.a{background:#1b1016;margin-right:12%;border:1px solid #5c1725}.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}textarea,input,select,button{font:inherit;border-radius:12px;border:1px solid #4a2430;background:#0c0e15;color:#fff;padding:10px}textarea{width:100%;min-height:82px}button{cursor:pointer;background:linear-gradient(135deg,#ff1738,#a80020);border:0;font-weight:750}button.secondary{background:#171a23;border:1px solid #45303a}.wave{height:100px;border-radius:22px;background:radial-gradient(ellipse at center,#1e0d16 0,#08090e 65%);position:relative;overflow:hidden}.wave:before{content:"";position:absolute;inset:49% 8% auto;height:2px;background:linear-gradient(90deg,transparent,#3478ff,#7edcff,#3478ff,transparent);box-shadow:0 0 16px #3180ff}.line{position:absolute;left:7%;right:7%;top:50%;height:1px;background:linear-gradient(90deg,transparent,#2878ff,#8bdfff,#2878ff,transparent);box-shadow:0 0 10px #2781ff}.pill{padding:6px 9px;border-radius:99px;background:#17151d;color:#aeb8ca;font-size:12px}.ok{color:#75f1ff}.err{color:#ff7389}.file{display:flex;justify-content:space-between;padding:8px;border-bottom:1px solid #2b2027}.small{font-size:12px;color:#a6a9b3}</style></head><body><main>
<div class="card top"><div class="logo">A</div><div class="grow"><h2 style="margin:0">ASTRA</h2><small id="status">Ready</small></div><button class="secondary" onclick="toggleDrawer()">☰</button></div>
<div id="drawer" style="display:none" class="card"><div class="row"><span class="pill">AI / model</span><select id="model"><option value="">Auto-select best available model</option></select><button class="secondary" onclick="discover()">Discover AIs / Models</button></div><p class="small">If discovery fails, check the Ollama/OpenAI-compatible endpoint configured in Astra.</p><input id="instruction" style="width:100%" placeholder="Optional sub-agent instruction"><div class="row"><label class="pill">Files <input id="files" type="file" multiple style="display:none" onchange="uploadFiles()"></label><button class="secondary" onclick="document.getElementById('files').click()">Fetch / Upload multiple files</button><button class="secondary" onclick="listFiles()">Show workspace files</button></div><div id="filesOut"></div></div>
<div class="card"><div class="wave"><div class="line" id="w1"></div><div class="line" id="w2" style="transform:scaleY(5);opacity:.55"></div><div class="line" id="w3" style="transform:scaleY(10);opacity:.28"></div></div></div>
<div id="chat" class="card chat"></div>
<div class="card"><textarea id="q" placeholder="Talk to Astra, type a command, or give a task..."></textarea><div class="row"><button onclick="send()">Send</button><button class="secondary" onclick="parallel()">⚡ Parallel</button><button class="secondary" onclick="backgroundTask()">⏱ Background task</button><button class="secondary" onclick="refreshTasks()">Tasks</button></div></div>
<div class="card"><div class="row"><span class="pill">API: /api/status</span><span class="pill">Models: /api/models</span><span class="pill">Files: /api/files</span><span class="pill">Tasks: /api/tasks</span></div><div id="tasksOut" class="small"></div></div>
<script>
const chat=document.getElementById('chat'),q=document.getElementById('q'),status=document.getElementById('status'),model=document.getElementById('model');
function add(t,c){let d=document.createElement('div');d.className='msg '+c;d.textContent=t;chat.appendChild(d);chat.scrollTop=chat.scrollHeight}
function toggleDrawer(){let d=document.getElementById('drawer');d.style.display=d.style.display==='none'?'block':'none'}
async function jsonFetch(url,opt){let r=await fetch(url,opt);let t=await r.text();let x;try{x=JSON.parse(t)}catch(_){throw Error('HTTP '+r.status)}if(!r.ok)throw Error(x.error||('HTTP '+r.status));return x}
async function discover(){status.textContent='Discovering…';try{let a=await jsonFetch('/api/models');model.innerHTML='<option value="">Auto-select best available model</option>'+a.filter(x=>x.id).map(x=>'<option value="'+x.providerId+'">'+x.providerName+' / '+x.id+'</option>').join('');status.textContent=a.length+' model(s) discovered'}catch(e){status.textContent='Discovery failed: '+e.message}}
async function send(){let t=q.value.trim();if(!t)return;add(t,'u');q.value='';status.textContent='Thinking…';try{let x=model.value?await jsonFetch('/api/provider/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({providerId:model.value,prompt:t})}):await jsonFetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({prompt:t})});add(x.response||x.error||JSON.stringify(x),'a');status.textContent='Ready'}catch(e){add('Connection failed: '+e.message,'a');status.textContent='Offline'}}
async function parallel(){let lines=q.value.split('\n').map(x=>x.trim()).filter(Boolean);if(!lines.length){status.textContent='Put one task per line';return}status.textContent='Running…';try{let x=await jsonFetch('/api/parallel',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({tasks:lines.map(x=>({task:x,instruction:document.getElementById('instruction').value}))})});add(JSON.stringify(x,null,2),'a');status.textContent='Ready'}catch(e){status.textContent='Parallel failed: '+e.message}}
async function backgroundTask(){let t=q.value.trim();if(!t)return;try{let x=await jsonFetch('/api/tasks',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({prompt:t})});add('Background task started: '+x.id,'a');q.value='';refreshTasks()}catch(e){status.textContent='Task failed: '+e.message}}
async function refreshTasks(){try{let a=await jsonFetch('/api/tasks');document.getElementById('tasksOut').textContent=a.map(x=>x.id+' • '+x.status+' • '+x.step+'/'+x.total+(x.result?' • '+x.result:'')).join('\n')||'No tasks'}catch(e){}}
function readFile(file){return new Promise((resolve,reject)=>{let r=new FileReader();r.onload=()=>resolve(String(r.result).split(',')[1]||'');r.onerror=reject;r.readAsDataURL(file)})}
async function uploadFiles(){let fs=[...document.getElementById('files').files];if(!fs.length)return;status.textContent='Uploading '+fs.length+' file(s)…';try{let arr=[];for(let f of fs){arr.push({name:f.name,mime:f.type,data:await readFile(f)})}let x=await jsonFetch('/api/files',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({files:arr})});status.textContent='Imported '+x.count+' file(s)';listFiles()}catch(e){status.textContent='Upload failed: '+e.message}}
async function listFiles(){try{let a=await jsonFetch('/api/files');document.getElementById('filesOut').innerHTML=a.map(x=>'<div class="file"><span>'+x.name+'</span><span class="small">'+x.size+' B</span></div>').join('')||'<span class="small">No files</span>'}catch(e){}}
discover();refreshTasks();
</script></main></body></html>"""
}
