package com.astra.ai

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AstraAttachmentManager(context: Context) {
    private val app = context.applicationContext
    private val intake = AstraFileIntake(app)
    private val processor = AstraFileProcessor(app)
    private val root = File(app.filesDir, "workspace/attachments").apply { mkdirs() }

    data class Processed(val attachment: AstraFileIntake.Attachment, val text: String, val readable: Boolean, val pages: Int)

    suspend fun importAndProcess(uri: android.net.Uri): Processed? = withContext(Dispatchers.IO) {
        val attachment = intake.importUri(uri) ?: return@withContext null
        process(File(attachment.path), attachment)
    }

    suspend fun process(file: File): Processed? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        val attachment = AstraFileIntake.Attachment(file.name, guessMime(file), file.absolutePath, file.length())
        process(file, attachment)
    }

    private suspend fun process(file: File, attachment: AstraFileIntake.Attachment): Processed {
        val result = processor.process(file)
        File(file.parentFile, file.name + ".astra.txt").writeText(result.text, Charsets.UTF_8)
        return Processed(attachment, result.text, result.readable, result.pages)
    }

    fun status(file: File): String = if (File(file.parentFile, file.name + ".astra.txt").exists()) "Ready" else "Stored"
    fun cachedText(file: File): String? = File(file.parentFile, file.name + ".astra.txt").takeIf { it.isFile }?.readText(Charsets.UTF_8)
    fun attachments(): List<File> = root.listFiles()?.filter { it.isFile && !it.name.endsWith(".astra.txt") }?.sortedByDescending { it.lastModified() }.orEmpty()

    private fun guessMime(file: File): String = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}
