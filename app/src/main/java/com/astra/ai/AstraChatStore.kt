package com.astra.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Persistent local ChatGPT-style chat history. Kept separate from short-term AI memory. */
class AstraChatStore(context: Context) {
    data class Chat(val id: String, val title: String, val createdAt: Long, val updatedAt: Long)
    data class Message(val id: String, val chatId: String, val role: String, val text: String, val timestamp: Long)

    private val file = File(context.applicationContext.filesDir, "astra_chats.json")
    private val lock = Any()

    private fun read(): JSONObject = synchronized(lock) {
        runCatching { if (file.exists()) JSONObject(file.readText()) else JSONObject() }.getOrElse { JSONObject() }
    }

    private fun write(root: JSONObject) = synchronized(lock) {
        val tmp = File(file.parentFile, "astra_chats.json.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    fun ensureChat(id: String? = null, title: String = "New chat"): Chat = synchronized(lock) {
        val root = read()
        val chats = root.optJSONArray("chats") ?: JSONArray().also { root.put("chats", it) }
        val existing = id?.let { findChat(chats, it) }
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        val chat = Chat(id ?: UUID.randomUUID().toString(), title.ifBlank { "New chat" }, now, now)
        chats.put(JSONObject().put("id", chat.id).put("title", chat.title).put("createdAt", now).put("updatedAt", now))
        root.put("messages", root.optJSONArray("messages") ?: JSONArray())
        write(root)
        chat
    }

    fun listChats(max: Int = 500): List<Chat> = synchronized(lock) {
        val a = read().optJSONArray("chats") ?: return@synchronized emptyList()
        buildList {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                add(Chat(o.optString("id"), o.optString("title", "New chat"), o.optLong("createdAt"), o.optLong("updatedAt")))
            }
        }.sortedByDescending { it.updatedAt }.take(max)
    }

    fun messages(chatId: String, sinceMs: Long = 0L, limit: Int = 200): List<Message> = synchronized(lock) {
        val a = read().optJSONArray("messages") ?: return@synchronized emptyList()
        val all = buildList {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (o.optString("chatId") == chatId && o.optLong("timestamp") >= sinceMs) {
                    add(Message(o.optString("id"), chatId, o.optString("role"), o.optString("text"), o.optLong("timestamp")))
                }
            }
        }.sortedBy { it.timestamp }
        if (all.size <= limit) all else all.takeLast(limit)
    }

    fun recentMessages(chatId: String, oneYear: Boolean = true, limit: Int = 80): List<Message> {
        val since = if (oneYear) System.currentTimeMillis() - 365L * 24L * 60L * 60L * 1000L else 0L
        return messages(chatId, since, limit)
    }

    fun append(chatId: String, role: String, text: String, timestamp: Long = System.currentTimeMillis()) {
        if (text.isBlank()) return
        synchronized(lock) {
            val root = read()
            val chats = root.optJSONArray("chats") ?: JSONArray().also { root.put("chats", it) }
            val chat = findChat(chats, chatId) ?: run {
                val now = System.currentTimeMillis()
                val created = JSONObject().put("id", chatId).put("title", "New chat").put("createdAt", now).put("updatedAt", now)
                chats.put(created)
                created
            }
            val messages = root.optJSONArray("messages") ?: JSONArray().also { root.put("messages", it) }
            messages.put(JSONObject().put("id", UUID.randomUUID().toString()).put("chatId", chatId).put("role", role).put("text", text).put("timestamp", timestamp))
            chat.put("updatedAt", timestamp)
            if (role == "user" && chat.optString("title") == "New chat") chat.put("title", text.take(48).replace("\n", " "))
            write(root)
        }
    }

    fun rename(chatId: String, title: String) = synchronized(lock) {
        val root = read(); val chats = root.optJSONArray("chats") ?: return@synchronized
        findChat(chats, chatId)?.put("title", title.trim().ifBlank { "New chat" })
        write(root)
    }

    fun delete(chatId: String) = synchronized(lock) {
        val root = read()
        val chats = root.optJSONArray("chats") ?: JSONArray()
        val keptChats = JSONArray()
        for (i in 0 until chats.length()) if (chats.optJSONObject(i)?.optString("id") != chatId) keptChats.put(chats.opt(i))
        root.put("chats", keptChats)
        val messages = root.optJSONArray("messages") ?: JSONArray()
        val keptMessages = JSONArray()
        for (i in 0 until messages.length()) if (messages.optJSONObject(i)?.optString("chatId") != chatId) keptMessages.put(messages.opt(i))
        root.put("messages", keptMessages)
        write(root)
    }

    fun clearAll() = synchronized(lock) { write(JSONObject().put("chats", JSONArray()).put("messages", JSONArray())) }

    private fun findChat(chats: JSONArray, id: String): Chat? {
        for (i in 0 until chats.length()) {
            val o = chats.optJSONObject(i) ?: continue
            if (o.optString("id") == id) return Chat(o.optString("id"), o.optString("title", "New chat"), o.optLong("createdAt"), o.optLong("updatedAt"))
        }
        return null
    }
}
