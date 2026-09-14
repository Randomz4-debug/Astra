package com.astra.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class AstraCustomCommandStore(context: Context) {
    data class Command(val id: Int, val uuid: String, val trigger: String, val actions: String, val enabled: Boolean = true, val createdAt: Long = System.currentTimeMillis())
    private val file = File(context.applicationContext.filesDir, "astra_custom_commands.json")
    private val lock = Any()
    private fun read(): JSONArray = synchronized(lock) { runCatching { if (file.exists()) JSONArray(file.readText()) else JSONArray() }.getOrElse { JSONArray() } }
    private fun write(a: JSONArray) = synchronized(lock) { val tmp = File(file.parentFile, "astra_custom_commands.json.tmp"); tmp.writeText(a.toString()); if (!tmp.renameTo(file)) { file.writeText(a.toString()); tmp.delete() } }
    fun list(): List<Command> = synchronized(lock) { val a = read(); buildList { for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; add(Command(o.optInt("id", i), o.optString("uuid"), o.optString("trigger"), o.optString("actions"), o.optBoolean("enabled", true), o.optLong("createdAt"))) } }.sortedBy { it.id } }
    fun find(trigger: String): Command? = list().firstOrNull { it.enabled && it.trigger.equals(trigger.trim(), true) }
    fun addOrUpdate(trigger: String, actions: String, enabled: Boolean = true): Command {
        require(trigger.trim().isNotBlank()) { "Trigger cannot be blank" }; require(actions.trim().isNotBlank()) { "Actions cannot be blank" }
        synchronized(lock) {
            val a = read()
            for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; if (o.optString("trigger").equals(trigger.trim(), true)) { o.put("actions", actions.trim()).put("enabled", enabled); write(a); return Command(o.optInt("id"), o.optString("uuid"), o.optString("trigger"), o.optString("actions"), enabled, o.optLong("createdAt")) } }
            val used = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optInt("id") }.toSet(); var id = 0; while (id in used) id++
            val o = JSONObject().put("id", id).put("uuid", UUID.randomUUID().toString()).put("trigger", trigger.trim()).put("actions", actions.trim()).put("enabled", enabled).put("createdAt", System.currentTimeMillis()); a.put(o); write(a)
            return Command(id, o.optString("uuid"), trigger.trim(), actions.trim(), enabled, o.optLong("createdAt"))
        }
    }
    fun delete(trigger: String): Boolean = synchronized(lock) { val a = read(); val kept = JSONArray(); var removed = false; for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; if (o.optString("trigger").equals(trigger.trim(), true)) removed = true else kept.put(o) }; if (!removed) return@synchronized false; val compact = JSONArray(); (0 until kept.length()).mapNotNull { kept.optJSONObject(it) }.sortedBy { it.optInt("id") }.forEachIndexed { index, o -> o.put("id", index); compact.put(o) }; write(compact); true }
    fun deleteById(id: Int): Boolean = synchronized(lock) { val a = read(); val kept = JSONArray(); var removed = false; for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; if (o.optInt("id") == id) removed = true else kept.put(o) }; if (!removed) return@synchronized false; val compact = JSONArray(); (0 until kept.length()).mapNotNull { kept.optJSONObject(it) }.sortedBy { it.optInt("id") }.forEachIndexed { index, o -> o.put("id", index); compact.put(o) }; write(compact); true }
    fun setEnabled(trigger: String, enabled: Boolean): Boolean = synchronized(lock) { val a = read(); var found = false; for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; if (o.optString("trigger").equals(trigger.trim(), true)) { o.put("enabled", enabled); found = true; break } }; if (found) write(a); found }
}
