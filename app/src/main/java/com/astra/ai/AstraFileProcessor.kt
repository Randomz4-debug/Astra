package com.astra.ai

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Reads common user attachments before Astra answers questions about them.
 * Text, source, JSON/CSV, images, PDFs and Office Open XML files are supported.
 * Binary files are still retained, but Astra reports that their contents could not be extracted.
 */
class AstraFileProcessor(private val context: Context) {
    data class Result(val text: String, val pages: Int = 0, val readable: Boolean = true)

    suspend fun process(file: File): Result = withContext(Dispatchers.IO) {
        runCatching {
            val name = file.name.lowercase()
            when {
                isText(name) -> Result(file.readText(Charsets.UTF_8).take(MAX_TEXT))
                name.endsWith(".json") -> Result(runCatching { JSONObject(file.readText()).toString(2) }.getOrElse { file.readText() }.take(MAX_TEXT))
                name.endsWith(".pdf") -> processPdf(file)
                name.endsWith(".docx") || name.endsWith(".xlsx") || name.endsWith(".pptx") -> processOpenXml(file)
                name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp") -> processImage(file)
                else -> Result("[Astra stored this file, but its contents are not supported for text extraction yet.]", readable = false)
            }
        }.getOrElse { Result("[Astra could not read this attachment: ${it.message ?: "unknown error"}]", readable = false) }
    }

    private fun isText(name: String): Boolean = listOf(".txt", ".md", ".csv", ".xml", ".html", ".htm", ".kt", ".java", ".py", ".js", ".ts", ".css", ".c", ".cpp", ".h", ".hpp", ".asm", ".s", ".yaml", ".yml", ".log", ".ini", ".gradle", ".properties").any(name::endsWith)

    private suspend fun processPdf(file: File): Result {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val out = StringBuilder("PDF: ${file.name}\nPages: ${renderer.pageCount}\n\n")
                val pages = minOf(renderer.pageCount, 12)
                for (i in 0 until pages) {
                    if (out.length >= MAX_TEXT) break
                    val page = renderer.openPage(i)
                    val width = 1200
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    val text = runCatching { LocalOcrEngine().read(bitmap) }.getOrDefault("")
                    bitmap.recycle()
                    out.append("--- PAGE ${i + 1} ---\n").append(text).append('\n')
                }
                return Result(out.toString().take(MAX_TEXT), pages = renderer.pageCount)
            }
        }
    }

    private suspend fun processImage(file: File): Result {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return Result("[Image could not be decoded.]", readable = false)
        return try {
            val text = runCatching { LocalOcrEngine().read(bitmap) }.getOrDefault("[No readable text found in the image.]")
            Result("IMAGE: ${file.name}\n\n$text", readable = true)
        } finally { bitmap.recycle() }
    }

    private fun processOpenXml(file: File): Result {
        val out = StringBuilder()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name
                if (!entry.isDirectory && (path.endsWith("document.xml") || path.endsWith("sharedStrings.xml") || path.endsWith("sheet.xml") || path.endsWith("slide.xml") || path.endsWith("notesSlides.xml"))) {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    val text = xml.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                    if (text.isNotBlank()) out.append(text).append('\n')
                }
                zip.closeEntry()
                if (out.length >= MAX_TEXT) break
            }
        }
        return Result("${file.name}\n\n${out.toString().take(MAX_TEXT)}", readable = out.isNotBlank())
    }

    companion object { private const val MAX_TEXT = 120_000 }
}
