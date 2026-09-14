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
import java.util.concurrent.atomic.AtomicBoolean

/** User-authorized screen reader. One VirtualDisplay is reused for one consent session. */
class ScreenCaptureManager(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var pending: ((Bitmap?) -> Unit)? = null
    private val waiting = AtomicBoolean(false)

    fun permissionIntent(): Intent =
        (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()

    @Synchronized
    fun attachResult(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) return false
        return runCatching {
            release()
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = manager.getMediaProjection(resultCode, data) ?: return false
            val callback = object : MediaProjection.Callback() {
                override fun onStop() { main.post { releaseProjectionState() } }
            }
            p.registerCallback(callback, main)
            projectionCallback = callback
            projection = p
            createDisplay(p)
            display != null && reader != null
        }.getOrElse { release(); false }
    }

    fun hasPermission(): Boolean = projection != null && display != null && reader != null

    @Synchronized
    private fun createDisplay(p: MediaProjection) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader.setOnImageAvailableListener({ source ->
            if (!waiting.compareAndSet(true, false)) { source.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            val callback = synchronized(this) { pending.also { pending = null } }
            val image = runCatching { source.acquireLatestImage() }.getOrNull()
            if (image == null) { main.post { callback?.invoke(null) }; return@setOnImageAvailableListener }
            try {
                val plane = image.planes.firstOrNull()
                if (plane == null) { main.post { callback?.invoke(null) }; return@setOnImageAvailableListener }
                val pixelStride = plane.pixelStride.coerceAtLeast(1)
                val rowPadding = (plane.rowStride - pixelStride * image.width).coerceAtLeast(0)
                val paddedWidth = image.width + rowPadding / pixelStride
                val raw = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                raw.copyPixelsFromBuffer(plane.buffer)
                val bitmap = if (paddedWidth != image.width) Bitmap.createBitmap(raw, 0, 0, image.width, image.height) else raw
                if (bitmap !== raw) raw.recycle()
                main.post { callback?.invoke(bitmap) }
            } catch (_: Throwable) { main.post { callback?.invoke(null) } }
            finally { image.close() }
        }, main)
        reader = imageReader
        display = p.createVirtualDisplay("AstraScreen", width, height, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, main)
    }

    fun capture(onResult: (Bitmap?) -> Unit) {
        synchronized(this) {
            if (!hasPermission()) { onResult(null); return }
            if (!waiting.compareAndSet(false, true)) { onResult(null); return }
            pending = onResult
        }
    }

    @Synchronized private fun releaseProjectionState() {
        pending?.invoke(null); pending = null; waiting.set(false)
        try { display?.release() } catch (_: Throwable) {}
        display = null
        try { reader?.close() } catch (_: Throwable) {}
        reader = null
        projection = null
        projectionCallback = null
    }

    @Synchronized fun release() {
        val p = projection; val cb = projectionCallback
        pending?.invoke(null); pending = null; waiting.set(false)
        try { display?.release() } catch (_: Throwable) {}
        display = null
        try { reader?.close() } catch (_: Throwable) {}
        reader = null
        if (p != null && cb != null) runCatching { p.unregisterCallback(cb) }
        runCatching { p?.stop() }
        projection = null; projectionCallback = null
    }
}
