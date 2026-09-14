package com.astra.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AutomationManager(context: Context) {
    private val prefs = context.getSharedPreferences("astra_automations", Context.MODE_PRIVATE)

    fun saveCommand(phrase: String, actions: List<String>) {
        val all = JSONObject(prefs.getString("commands", "{}") ?: "{}")
        all.put(phrase.trim().lowercase(), JSONArray(actions))
        prefs.edit().putString("commands", all.toString()).apply()
    }

    fun actionsFor(phrase: String): List<String> {
        val raw = JSONObject(prefs.getString("commands", "{}") ?: "{}").optJSONArray(phrase.trim().lowercase()) ?: return emptyList()
        return List(raw.length()) { raw.optString(it) }
    }

    fun removeCommand(phrase: String) {
        val all = JSONObject(prefs.getString("commands", "{}") ?: "{}").apply { remove(phrase.trim().lowercase()) }
        prefs.edit().putString("commands", all.toString()).apply()
    }

    fun listCommands(): List<String> {
        val all = JSONObject(prefs.getString("commands", "{}") ?: "{}")
        return all.keys().asSequence().toList().sorted()
    }
}
