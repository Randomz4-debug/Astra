package com.astra.ai

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-controlled visual memory. It stores a compact perceptual fingerprint and a label.
 * It is intentionally enrollment-based: Astra only recognizes entities the user explicitly saved.
 */
class AstraVisualMemoryStore(context: Context) {
    data class Memory(val id: String, val category: String, val name: String, val description: String, val fingerprint: String, val createdAt: Long)
    private val file = File(context.applicationContext.filesDir, "astra_visual_memory.json")

    @Synchronized fun enroll(bitmap: Bitmap, category: String, name: String, description: String = ""): Memory {
        val memory = Memory(java.util.UUID.randomUUID().toString(), category.lowercase(), name.trim(), description.trim(), fingerprint(bitmap), System.currentTimeMillis())
        val all = list().toMutableList(); all.add(memory); save(all); return memory
    }

    @Synchronized fun identify(bitmap: Bitmap, maxDistance: Int = 18): Memory? {
        val target = fingerprint(bitmap)
        return list().map { it to hamming(target, it.fingerprint) }.filter { it.second <= maxDistance }.minByOrNull { it.second }?.first
    }

    @Synchronized fun list(): List<Memory> = runCatching {
        val array = JSONArray(if (file.isFile) file.readText() else "[]")
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { o ->
            Memory(o.optString("id"), o.optString("category"), o.optString("name"), o.optString("description"), o.optString("fingerprint"), o.optLong("createdAt"))
        } }
    }.getOrDefault(emptyList())

    @Synchronized fun remove(id: String) = save(list().filterNot { it.id == id })

    private fun save(items: List<Memory>) {
        val array = JSONArray(); items.forEach { m -> array.put(JSONObject().apply { put("id", m.id); put("category", m.category); put("name", m.name); put("description", m.description); put("fingerprint", m.fingerprint); put("createdAt", m.createdAt) }) }
        file.writeText(array.toString())
    }

    private fun fingerprint(source: Bitmap): String {
        val small = Bitmap.createScaledBitmap(source, 16, 16, true)
        val values = IntArray(256); var sum = 0L; var i = 0
        for (y in 0 until 16) for (x in 0 until 16) { val p = small.getPixel(x, y); val gray = (0.299 * ((p shr 16) and 255) + 0.587 * ((p shr 8) and 255) + 0.114 * (p and 255)).toInt(); values[i++] = gray; sum += gray }
        small.recycle(); val avg = sum / values.size
        val bits = StringBuilder(256); values.forEach { bits.append(if (it >= avg) '1' else '0') }
        return MessageDigest.getInstance("SHA-256").digest(bits.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun hamming(a: String, b: String): Int {
        val n = minOf(a.length, b.length); var d = kotlin.math.abs(a.length - b.length) * 4
        for (i in 0 until n) d += Integer.bitCount((a[i].code xor b[i].code))
        return d
    }
}
