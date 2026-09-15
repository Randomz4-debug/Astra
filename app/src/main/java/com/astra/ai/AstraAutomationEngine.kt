package com.astra.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Local-first workflow engine inspired by visual automation platforms.
 * It deliberately uses only Android/Java APIs so it remains offline-capable.
 * Workflows are persisted locally and can contain triggers, ordered actions,
 * conditions, branches, loops, variables, retries, HTTP/API calls, app/UI
 * actions, AI steps, notifications and execution history.
 */
class AstraAutomationEngine(private val context: Context) {
    data class Workflow(val id: String, val name: String, val enabled: Boolean, val trigger: Trigger, val nodes: List<Node>, val variables: Map<String, String> = emptyMap())
    data class Trigger(val type: String, val value: String = "")
    data class Node(val type: String, val value: String = "", val a: String = "", val b: String = "", val retries: Int = 0, val children: List<Node> = emptyList(), val elseChildren: List<Node> = emptyList())
    data class Execution(val id: String, val workflowId: String, val started: Long, val finished: Long, val status: String, val output: String)

    companion object {
        private const val PREFS = "astra_automation_engine"
        private const val KEY_WORKFLOWS = "workflows"
        private const val KEY_HISTORY = "history"
        private val running = ConcurrentHashMap.newKeySet<String>()
    }
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val app = context.applicationContext

    fun list(): List<Workflow> = readWorkflows()
    fun get(id: String): Workflow? = list().firstOrNull { it.id == id }
    fun delete(id: String): Boolean {
        val all = list(); val changed = all.filterNot { it.id == id }; if (changed.size == all.size) return false
        writeWorkflows(changed); cancelSchedule(id); return true
    }
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val all = list(); val index = all.indexOfFirst { it.id == id }; if (index < 0) return false
        val old = all[index]; val updated = old.copy(enabled = enabled); val out = all.toMutableList(); out[index] = updated; writeWorkflows(out)
        if (enabled) schedule(updated) else cancelSchedule(id)
        return true
    }
    fun save(workflow: Workflow): Workflow {
        val all = list().filterNot { it.id == workflow.id }.toMutableList(); all += workflow; writeWorkflows(all)
        if (workflow.enabled) schedule(workflow) else cancelSchedule(workflow.id)
        return workflow
    }
    fun create(name: String, triggerType: String = "manual", triggerValue: String = "", nodes: List<Node> = emptyList()): Workflow = save(Workflow(UUID.randomUUID().toString().take(10), name.ifBlank { "Astra Automation" }, true, Trigger(triggerType, triggerValue), nodes))

    suspend fun run(id: String, input: String = ""): Execution {
        val workflow = get(id) ?: return record(id, "failed", "Workflow not found")
        if (!running.add(id)) return record(id, "skipped", "Workflow is already running")
        val started = System.currentTimeMillis()
        return try {
            val vars = workflow.variables.toMutableMap(); vars["input"] = input; vars["timestamp"] = started.toString()
            val output = executeNodes(workflow.nodes, vars)
            record(id, "completed", output, started)
        } catch (t: Throwable) { record(id, "failed", t.message ?: "Automation failed", started) }
        finally { running.remove(id) }
    }

    fun history(limit: Int = 50): List<Execution> = try {
        val arr = JSONArray(prefs.getString(KEY_HISTORY, "[]")); (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Execution(it.optString("id"), it.optString("workflowId"), it.optLong("started"), it.optLong("finished"), it.optString("status"), it.optString("output")) } }.takeLast(limit).reversed()
    } catch (_: Throwable) { emptyList() }

    private suspend fun executeNodes(nodes: List<Node>, vars: MutableMap<String, String>): String {
        var last = ""
        for (node in nodes) {
            last = executeNode(node, vars)
            vars["last"] = last
        }
        return last
    }

    private suspend fun executeNode(node: Node, vars: MutableMap<String, String>): String {
        val value = template(node.value, vars); val a = template(node.a, vars); val b = template(node.b, vars)
        suspend fun attempt(): String = when (node.type.lowercase()) {
            "set", "variable" -> { vars[a.ifBlank { value.substringBefore('=') }] = value.substringAfter('=', value); vars[a.ifBlank { value.substringBefore('=') }] ?: "" }
            "wait", "delay" -> { delay(value.toLongOrNull()?.coerceIn(0, 300000) ?: 500); "waited" }
            "open_app", "app", "launch" -> { AppManager(app).open(value); "opened $value" }
            "open_url", "url", "browser" -> { DeviceTools(app).browser(value); "opened $value" }
            "maps", "navigate" -> { DeviceTools(app).maps(value); "navigated to $value" }
            "camera", "photo" -> { DeviceTools(app).camera(); "camera opened" }
            "call", "dial" -> { DeviceTools(app).call(value); "call requested" }
            "speak", "say" -> { MultilingualVoiceController(app).speak(value); value }
            "notify", "notification" -> { AstraAutomationNotifications.show(app, value); value }
            "click", "tap" -> { AstraAccessibilityService.current()?.clickText(value) == true; "clicked $value" }
            "type", "write" -> { AstraAccessibilityService.current()?.typeText(value) == true; "typed text" }
            "back" -> { AstraAccessibilityService.current()?.globalBack(); "back" }
            "home" -> { AstraAccessibilityService.current()?.globalHome(); "home" }
            "scroll" -> { AstraAccessibilityService.current()?.scrollForward(); "scrolled" }
            "http", "http_request", "rest", "api" -> http(a.ifBlank { "GET" }, value, b, vars)
            "ai", "ask_ai", "agent" -> AstraAgentRuntime(app).automationReason(value)
            "condition", "if" -> { if (evaluate(value, vars)) executeNodes(node.children, vars) else executeNodes(node.elseChildren, vars) }
            "loop", "repeat" -> { val count = value.toIntOrNull()?.coerceIn(1, 100) ?: 1; var out = ""; repeat(count) { out = executeNodes(node.children, vars) }; out }
            "foreach" -> { var out = ""; value.split(',').map { it.trim() }.filter { it.isNotBlank() }.take(100).forEach { vars[a.ifBlank { "item" }] = it; out = executeNodes(node.children, vars) }; out }
            "workflow", "run_workflow" -> { get(value)?.let { run(it.id, vars["input"].orEmpty())?.output } ?: "workflow not found" }
            "connected_app", "app_action" -> AstraConnectionTools(app).openApp(value)
            "stop" -> throw AutomationStopException()
            else -> if (value.isNotBlank()) value else ""
        }
        var attempt = 0; var lastError: Throwable? = null
        while (attempt <= node.retries.coerceIn(0, 5)) {
            try { return attempt() } catch (t: Throwable) { lastError = t; attempt++; if (attempt <= node.retries) delay(200L * attempt) }
        }
        throw lastError ?: IllegalStateException("Node failed")
    }

    private fun evaluate(expression: String, vars: Map<String, String>): Boolean {
        val e = template(expression, vars).trim()
        if (e.equals("true", true)) return true; if (e.equals("false", true)) return false
        val ops = listOf("!=", ">=", "<=", "=", ">", "<", " contains ", " startsWith ", " endsWith ")
        val op = ops.firstOrNull { e.contains(it, true) } ?: return vars[e].orEmpty().isNotBlank()
        val p = e.split(op, limit = 2); if (p.size != 2) return false
        val l = p[0].trim(); val r = p[1].trim().trim('"', '\''); val lv = vars[l] ?: l.trim('"', '\'')
        return when (op.lowercase()) { "=" -> lv == r; "!=" -> lv != r; ">" -> lv.toDoubleOrNull()?.let { it > (r.toDoubleOrNull() ?: Double.MAX_VALUE) } ?: (lv > r); "<" -> lv.toDoubleOrNull()?.let { it < (r.toDoubleOrNull() ?: Double.MIN_VALUE) } ?: (lv < r); ">=" -> lv.toDoubleOrNull()?.let { it >= (r.toDoubleOrNull() ?: Double.MAX_VALUE) } ?: (lv >= r); "<=" -> lv.toDoubleOrNull()?.let { it <= (r.toDoubleOrNull() ?: Double.MIN_VALUE) } ?: (lv <= r); " contains " -> lv.contains(r, true); " startswith " -> lv.startsWith(r, true); " endswith " -> lv.endsWith(r, true); else -> false }
    }

    private suspend fun http(method: String, urlText: String, body: String, vars: MutableMap<String, String>): String = withContext(Dispatchers.IO) {
        val conn = (URL(urlText).openConnection() as HttpURLConnection).apply { requestMethod = method.uppercase(); connectTimeout = 8000; readTimeout = 15000; setRequestProperty("Accept", "application/json"); if (body.isNotBlank() && method.uppercase() != "GET") { doOutput = true; setRequestProperty("Content-Type", "application/json") } }
        try { if (conn.doOutput) conn.outputStream.use { it.write(body.toByteArray()) }; val code = conn.responseCode; val stream = if (code in 200..399) conn.inputStream else conn.errorStream; val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty().take(20000); vars["http.status"] = code.toString(); vars["http.body"] = text; text }
        finally { conn.disconnect() }
    }

    private fun template(value: String, vars: Map<String, String>): String = Regex("\\{\\{\\s*([^}]+?)\\s*}}|\\$\\{([^}]+)}").replace(value) { vars[it.groupValues[1].ifBlank { it.groupValues[2] }].orEmpty() }

    private fun record(workflowId: String, status: String, output: String, started: Long = System.currentTimeMillis()): Execution {
        val now = System.currentTimeMillis(); val e = Execution(UUID.randomUUID().toString().take(10), workflowId, started, now, status, output.take(4000)); val arr = JSONArray(); history(199).reversed().forEach { old -> arr.put(JSONObject().apply { put("id", old.id); put("workflowId", old.workflowId); put("started", old.started); put("finished", old.finished); put("status", old.status); put("output", old.output) }) }; arr.put(JSONObject().apply { put("id", e.id); put("workflowId", e.workflowId); put("started", e.started); put("finished", e.finished); put("status", e.status); put("output", e.output) }); prefs.edit().putString(KEY_HISTORY, arr.toString()).apply(); return e
    }

    private fun readWorkflows(): List<Workflow> = try { val arr = JSONArray(prefs.getString(KEY_WORKFLOWS, "[]")); (0 until arr.length()).mapNotNull { parse(arr.optJSONObject(it)) } } catch (_: Throwable) { emptyList() }
    private fun parse(o: JSONObject?): Workflow? = try { if (o == null) null else Workflow(o.optString("id"), o.optString("name"), o.optBoolean("enabled", true), Trigger(o.optJSONObject("trigger")?.optString("type").orEmpty(), o.optJSONObject("trigger")?.optString("value").orEmpty()), parseNodes(o.optJSONArray("nodes")), o.optJSONObject("variables")?.let { j -> j.keys().asSequence().associateWith { j.optString(it) } } ?: emptyMap()) } catch (_: Throwable) { null }
    private fun parseNodes(a: JSONArray?): List<Node> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { n -> a.optJSONObject(n)?.let { Node(it.optString("type"), it.optString("value"), it.optString("a"), it.optString("b"), it.optInt("retries"), parseNodes(it.optJSONArray("children")), parseNodes(it.optJSONArray("elseChildren"))) } }
    private fun nodeJson(n: Node) = JSONObject().apply { put("type", n.type); put("value", n.value); put("a", n.a); put("b", n.b); put("retries", n.retries); put("children", JSONArray().apply { n.children.forEach { put(nodeJson(it)) } }); put("elseChildren", JSONArray().apply { n.elseChildren.forEach { put(nodeJson(it)) } }) }
    private fun workflowJson(w: Workflow) = JSONObject().apply { put("id", w.id); put("name", w.name); put("enabled", w.enabled); put("trigger", JSONObject().apply { put("type", w.trigger.type); put("value", w.trigger.value) }); put("nodes", JSONArray().apply { w.nodes.forEach { put(nodeJson(it)) } }); put("variables", JSONObject().apply { w.variables.forEach { (k,v) -> put(k,v) } }) }
    private fun writeWorkflows(list: List<Workflow>) { prefs.edit().putString(KEY_WORKFLOWS, JSONArray().apply { list.forEach { put(workflowJson(it)) } }.toString()).apply() }

    private fun schedule(w: Workflow) {
        if (w.trigger.type.equals("manual", true) || w.trigger.type.equals("webhook", true) || w.trigger.type.equals("event", true)) return
        val alarm = app.getSystemService(AlarmManager::class.java); val intent = Intent(app, AstraAutomationReceiver::class.java).putExtra("workflow_id", w.id); val pi = PendingIntent.getBroadcast(app, w.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val millis = when (w.trigger.type.lowercase()) { "interval", "every" -> w.trigger.value.toLongOrNull()?.coerceAtLeast(1000L) ?: 60000L; "delay" -> w.trigger.value.toLongOrNull()?.coerceAtLeast(1000L) ?: 60000L; else -> 0L }
        if (millis > 0) alarm.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + millis, millis, pi)
    }
    private fun cancelSchedule(id: String) { val alarm = app.getSystemService(AlarmManager::class.java); val pi = PendingIntent.getBroadcast(app, id.hashCode(), Intent(app, AstraAutomationReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE); if (pi != null) alarm.cancel(pi) }

    class AutomationStopException : RuntimeException()
}

object AstraAutomationNotifications {
    fun show(context: Context, text: String) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java); val channel = android.app.NotificationChannel("astra_automation", "Astra Automations", android.app.NotificationManager.IMPORTANCE_DEFAULT); nm.createNotificationChannel(channel); val n = androidx.core.app.NotificationCompat.Builder(context, "astra_automation").setSmallIcon(com.astra.ai.R.drawable.ic_astra).setContentTitle("Astra Automation").setContentText(text.take(200)).setAutoCancel(true).build(); nm.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), n)
    }
}
