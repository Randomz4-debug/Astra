package com.astra.ai

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk-backed local memory with a hard 2 GiB budget and automatic junk cleanup.
 * Important memories are retained; temporary/low-value entries are evicted first.
 */
class AstraMemoryStore(context: Context) {
    companion object { const val MAX_BYTES = 2L * 1024L * 1024L * 1024L; private const val TARGET_BYTES = 1800L * 1024L * 1024L }
    private val dir = File(context.applicationContext.filesDir, "astra_memory").apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Any>()

    fun put(key: String, value: String, pinned: Boolean = false) {
        val k = key.trim(); val v = value.trim(); if (k.isBlank() || v.isBlank()) return
        val file = File(dir, sha(k) + ".mem")
        synchronized(locks.getOrPut(file.name) { Any() }) {
            file.writeText("$k\n$pinned\n${System.currentTimeMillis()}\n$v", Charsets.UTF_8)
        }
        cleanup()
    }

    fun get(key: String): String? = read(File(dir, sha(key.trim()) + ".mem"))?.value

    fun remove(key: String) { File(dir, sha(key.trim()) + ".mem").delete() }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    fun all(): Map<String, String> = dir.listFiles()?.mapNotNull { read(it)?.let { r -> r.key to r.value } }?.toMap() ?: emptyMap()

    fun bytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Removes stale, duplicate-like and low-value memories until comfortably below the limit. */
    fun cleanup() {
        var total = bytes(); if (total <= TARGET_BYTES) return
        val candidates = dir.listFiles()?.mapNotNull { f -> read(f)?.let { f to it } }?.filterNot { it.second.pinned }
            ?.sortedBy { junkScore(it.second, it.first) } ?: return
        for ((file, record) in candidates) {
            if (total <= TARGET_BYTES) break
            total -= file.length(); file.delete()
        }
        if (total > MAX_BYTES) {
            dir.listFiles()?.filter { it.length() > 0 }?.sortedBy { it.lastModified() }?.forEach { f ->
                if (total > MAX_BYTES) { total -= f.length(); f.delete() }
            }
        }
    }

    private fun junkScore(r: Record, f: File): Long {
        var score = r.timestamp
        val text = (r.key + " " + r.value).lowercase()
        if (text.contains("temporary") || text.contains("temp") || text.contains("test") || text.contains("debug") || text.contains("junk")) score -= 9_000_000_000L
        if (r.value.length < 12) score -= 4_000_000_000L
        score += f.length() / 1024L
        return score
    }

    private data class Record(val key: String, val pinned: Boolean, val timestamp: Long, val value: String)
    private fun read(f: File): Record? = runCatching {
        if (!f.isFile) return null
        val lines = f.readLines(Charsets.UTF_8); if (lines.size < 4) return null
        Record(lines[0], lines[1].toBoolean(), lines[2].toLong(), lines.drop(3).joinToString("\n"))
    }.getOrNull()

    private fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
