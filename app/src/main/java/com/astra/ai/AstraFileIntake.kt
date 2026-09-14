package com.astra.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Imports user-selected files into Astra's private workspace. */
class AstraFileIntake(private val context: Context) {
    data class Attachment(val name: String, val mime: String, val path: String, val size: Long)

    suspend fun importUri(uri: Uri): Attachment? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "attachment" else "attachment"
            } ?: "attachment"
            resolver.openInputStream(uri)?.use { input -> importStream(name, mime, input) }
        }.getOrNull()
    }

    suspend fun importBytes(name: String, mime: String, bytes: ByteArray): Attachment? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "workspace/attachments").apply { mkdirs() }
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "attachment" }
            val target = File(dir, "${System.currentTimeMillis()}_$safeName")
            FileOutputStream(target).use { it.write(bytes) }
            Attachment(name, mime.ifBlank { "application/octet-stream" }, target.absolutePath, target.length())
        }.getOrNull()
    }

    fun list(): List<File> = File(context.filesDir, "workspace/attachments").listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()

    private fun importStream(name: String, mime: String, input: java.io.InputStream): Attachment? {
        val dir = File(context.filesDir, "workspace/attachments").apply { mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "attachment" }
        val target = File(dir, "${System.currentTimeMillis()}_$safeName")
        FileOutputStream(target).use { output -> input.copyTo(output) }
        return Attachment(name, mime, target.absolutePath, target.length())
    }
}
