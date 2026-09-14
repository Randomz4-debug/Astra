package com.astra.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Local workspace index. Text-like attachments are exposed to Astra's reasoning prompt. */
class AstraWorkspace(context: Context) {
    private val root = File(context.applicationContext.filesDir, "workspace/attachments")

    suspend fun contextText(maxFiles: Int = 12, maxCharsPerFile: Int = 24_000, maxTotalChars: Int = 120_000): String = withContext(Dispatchers.IO) {
        if (!root.exists()) return@withContext "(no files imported)"
        val files = root.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.take(maxFiles).orEmpty()
        if (files.isEmpty()) return@withContext "(no files imported)"
        val out = StringBuilder()
        for (file in files) {
            if (out.length >= maxTotalChars) break
            out.append("FILE: ").append(file.name).append(" (size=").append(file.length()).append(" bytes)\n")
            if (isTextLike(file)) {
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
                out.append(text.take(minOf(maxCharsPerFile, maxTotalChars - out.length)))
            } else {
                out.append("[binary attachment; stored in Astra workspace but raw bytes are not injected into text context]")
            }
            out.append("\n---\n")
        }
        out.toString().take(maxTotalChars)
    }

    fun list(): List<File> = root.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()

    private fun isTextLike(file: File): Boolean {
        val n = file.name.lowercase()
        return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv") || n.endsWith(".json") ||
            n.endsWith(".xml") || n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".kt") ||
            n.endsWith(".java") || n.endsWith(".py") || n.endsWith(".js") || n.endsWith(".ts") ||
            n.endsWith(".css") || n.endsWith(".c") || n.endsWith(".cpp") || n.endsWith(".h") ||
            n.endsWith(".hpp") || n.endsWith(".asm") || n.endsWith(".s") || n.endsWith(".yaml") || n.endsWith(".yml")
    }
}
