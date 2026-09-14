package com.astra.ai

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

/** Astra-controlled camera: front/back switch, capture and immediate Gallery hand-off. */
class AstraCameraActivity : ComponentActivity() {
    private var imageCapture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null
    private var front = false
    private var autoCapture = false
    private var previewView: PreviewView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        front = intent.getBooleanExtra("front", false)
        autoCapture = intent.getBooleanExtra("autoCapture", false)
        setContent { CameraScreen() }
    }

    @Composable
    private fun CameraScreen() {
        val context = LocalContext.current
        var ready by remember { mutableStateOf(false) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) bindCamera { ready = it } else Toast.makeText(context, "Camera permission is required.", Toast.LENGTH_LONG).show()
        }
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera { ready = it }
            else permission.launch(Manifest.permission.CAMERA)
        }
        Surface(Modifier.fillMaxSize(), color = Color(0xFF050509)) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { PreviewView(it).also { previewView = it } }, modifier = Modifier.fillMaxSize())
                Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(16.dp)) {
                    Text("ASTRA CAMERA", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(if (front) "Front camera" else "Back camera", color = Color.LightGray)
                }
                Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { front = !front; bindCamera { ready = it } }) { Text("Flip") }
                    Button(enabled = ready, onClick = { capturePhoto() }, modifier = Modifier.height(56.dp)) { Text("CAPTURE") }
                    OutlinedButton(onClick = { finish() }) { Text("Close") }
                }
            }
        }
    }

    private fun bindCamera(onReady: (Boolean) -> Unit) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val p = future.get()
                provider = p
                val preview = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(previewView?.surfaceProvider) }
                imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                p.unbindAll()
                p.bindToLifecycle(this, selector, preview, imageCapture)
                onReady(true)
                if (autoCapture) { autoCapture = false; window.decorView.postDelayed({ capturePhoto() }, 650) }
            }.onFailure { onReady(false); Toast.makeText(this, "Camera could not start: ${it.message}", Toast.LENGTH_LONG).show() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Astra_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Astra")
        }
        val output = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        capture.takePicture(output, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                val uri: Uri = result.savedUri ?: return Toast.makeText(this@AstraCameraActivity, "Photo saved but Gallery URI was unavailable.", Toast.LENGTH_SHORT).show()
                openGallery(uri)
            }
            override fun onError(exception: ImageCaptureException) { Toast.makeText(this@AstraCameraActivity, "Capture failed: ${exception.message}", Toast.LENGTH_LONG).show() }
        })
    }

    private fun openGallery(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "image/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        runCatching { startActivity(intent) }.onFailure { runCatching { startActivity(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)) } }
    }

    override fun onDestroy() {
        provider?.unbindAll()
        provider = null
        imageCapture = null
        super.onDestroy()
    }
}
