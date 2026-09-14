package com.astra.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics

/** User-authorized MediaProjection screen reader. Permission must be granted for every capture session. */
class ScreenCaptureManager(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var projectionData: Intent? = null

    fun permissionIntent(): Intent =
        (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()

    fun attachResult(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) return false
        return runCatching {
            releaseCaptureOnly()
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val newProjection = manager.getMediaProjection(resultCode, data) ?: return false
            projection = newProjection
            projectionData = Intent(data)
            true
        }.getOrDefault(false)
    }

    fun hasPermission(): Boolean = projection != null

    fun capture(onResult: (Bitmap?) -> Unit) {
        val media = projection ?: return onResult(null)
        try {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            releaseCaptureOnly()
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val r = reader ?: return onResult(null)
            display = media.createVirtualDisplay(
                "AstraScreen",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface,
                null,
                main
            )
            r.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes.firstOrNull()
                    if (plane == null) { onResult(null); return@setOnImageAvailableListener }
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * image.width
                    val paddedWidth = image.width + (rowPadding / pixelStride).coerceAtLeast(0)
                    val raw = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                    raw.copyPixelsFromBuffer(plane.buffer)
                    val bitmap = if (paddedWidth != image.width) Bitmap.createBitmap(raw, 0, 0, image.width, image.height) else raw
                    if (bitmap !== raw) raw.recycle()
                    onResult(bitmap)
                } catch (_: Throwable) {
                    onResult(null)
                } finally {
                    image.close()
                    releaseCaptureOnly()
                }
            }, main)
        } catch (_: Throwable) {
            releaseCaptureOnly()
            onResult(null)
        }
    }

    private fun releaseCaptureOnly() {
        try { display?.release() } catch (_: Throwable) {}
        display = null
        try { reader?.close() } catch (_: Throwable) {}
        reader = null
    }

    fun release() {
        releaseCaptureOnly()
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        projectionData = null
    }
}
