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

class ScreenCaptureManager(private val context: Context) {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    fun permissionIntent(): Intent =
        (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()

    fun attachResult(resultCode: Int, data: Intent): Boolean {
        if (resultCode != Activity.RESULT_OK) return false
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        return projection != null
    }

    fun capture(onResult: (Bitmap?) -> Unit) {
        val media = projection ?: return onResult(null)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") context.getSystemService(Context.WINDOW_SERVICE).let {
            @Suppress("DEPRECATION") (it as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        }
        reader?.close()
        reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        val r = reader!!
        display?.release()
        display = media.createVirtualDisplay(
            "AstraScreen",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface,
            null,
            Handler(Looper.getMainLooper())
        )
        r.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val width = image.width
                val height = image.height
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
                onResult(bitmap)
            } catch (_: Exception) { onResult(null) }
            finally { image.close(); release() }
        }, Handler(Looper.getMainLooper()))
    }

    fun release() {
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
    }
}
