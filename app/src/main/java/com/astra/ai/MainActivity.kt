package com.astra.ai

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var voice: MultilingualVoiceController
    private lateinit var screenCapture: ScreenCaptureManager
    private lateinit var camera: AstraCameraManager
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    private val screenCapturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        if (screenCapture.attachResult(result.resultCode, data)) Toast.makeText(this, "Screen access enabled for this session", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS))
        voice = MultilingualVoiceController(this)
        screenCapture = ScreenCaptureManager(this)
        camera = AstraCameraManager(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { AstraScreen(this) } }
    }

    fun listen(language: String, result: (String) -> Unit, state: (Boolean) -> Unit) = voice.listen(language, result, state)
    fun stopListening() = voice.stop()
    fun speak(text: String, language: String) = voice.speak(text, language)
    fun startBackground() { if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, AstraForegroundService::class.java)) else startService(Intent(this, AstraForegroundService::class.java)) }
    fun stopBackground() { stopService(Intent(this, AstraForegroundService::class.java)) }
    fun requestScreenAccess() { screenCapturePermission.launch(screenCapture.permissionIntent()) }
    fun captureScreen(onText: (String) -> Unit) {
        screenCapture.capture { bitmap ->
            if (bitmap == null) return@capture onText("Screen capture failed or permission was not granted.")
            lifecycleScope.launch { val text = LocalOcrEngine().read(bitmap); onText(if (text.isBlank()) "No readable text was found on the screen." else text) }
        }
    }
    fun cameraOcr(onText: (String) -> Unit) {
        camera.bind(this) { ok ->
            if (!ok) return@bind onText("Camera could not be opened. Check camera permission.")
            camera.takePhoto { file ->
                if (file == null) return@takePhoto onText("Photo capture failed.")
                lifecycleScope.launch { val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath); val text = if (bitmap != null) LocalOcrEngine().read(bitmap) else ""; onText(if (text.isBlank()) "Photo saved. No readable text was found." else text) }
            }
        }
    }
    fun openAccessibilitySettings() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    fun openNotificationSettings() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    fun openBatterySettings() = startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    fun openOverlaySettings() = startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
    override fun onDestroy() { screenCapture.release(); voice.release(); super.onDestroy() }
}

@Composable
fun AstraScreen(activity: MainActivity, vm: AstraViewModel = viewModel(factory = AstraViewModel.factory(activity))) {
    val ui by vm.ui.collectAsState()
    var input by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("auto") }
    var apiKey by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }

    LaunchedEffect(ui.response) { if (ui.response.isNotBlank()) activity.speak(ui.response, language) }

    Column(Modifier.fillMaxSize().background(Color(0xFF08090D)).verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Text("ASTRA", style = MaterialTheme.typography.displaySmall)
        Text(if (listening) "LISTENING" else ui.state.name, color = Color.LightGray)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.size(150.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) { Text("A", style = MaterialTheme.typography.displayLarge) }
        Spacer(Modifier.height(12.dp))
        Text(ui.transcript, color = Color.LightGray)
        Text(ui.response)
        if (info.isNotBlank()) { Spacer(Modifier.height(8.dp)); Card(Modifier.fillMaxWidth()) { Text(info, Modifier.padding(12.dp)) } }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(language, { language = it }, Modifier.fillMaxWidth(), label = { Text("Language / BCP-47: auto, en-US, hi-IN...") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text("Talk, type, translate, or issue a command") })
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ if (listening) activity.stopListening() else activity.listen(language, { text -> input = text; vm.ask(text) }, { listening = it }) }, Modifier.weight(1f)) { Text(if (listening) "Stop" else "Listen") }
            Button({ if (input.isNotBlank()) { vm.ask(input); input = "" } }, Modifier.weight(1f)) { Text("Send") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ activity.requestScreenAccess() }, Modifier.weight(1f)) { Text("Screen Access") }
            OutlinedButton({ activity.captureScreen { info = it } }, Modifier.weight(1f)) { Text("Read Screen") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ activity.cameraOcr { info = it } }, Modifier.weight(1f)) { Text("Camera + OCR") }
            OutlinedButton({ activity.openAccessibilitySettings() }, Modifier.weight(1f)) { Text("Accessibility") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ activity.openNotificationSettings() }, Modifier.weight(1f)) { Text("Notifications") }
            OutlinedButton({ activity.openBatterySettings() }, Modifier.weight(1f)) { Text("Battery") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ activity.openOverlaySettings() }, Modifier.weight(1f)) { Text("Overlay") }
            OutlinedButton({ info = "Local-only: ${ui.localOnly}\nCloud configured: ${ui.cloudConfigured}\nNetwork: ${if (ui.localOnly) "OFF" else "ON for explicitly selected cloud mode"}\nAccessibility: user-controlled\nNotification access: user-controlled" }, Modifier.weight(1f)) { Text("Privacy") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(ui.localOnly, vm::setLocalOnly); Text("Local only") }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(ui.background, { checked -> vm.setBackground(checked); if (checked) activity.startBackground() else activity.stopBackground() }); Text("Background") }
        }
        Spacer(Modifier.height(8.dp))
        Text("Cloud mode is optional. The API key is encrypted with Android Keystore and is never hard-coded. Local mode never calls the cloud provider.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("Optional OpenAI API key") }, singleLine = true)
        Spacer(Modifier.height(6.dp))
        Button({ vm.setOpenAiApiKey(apiKey); info = "Cloud credential saved in Android Keystore." }, Modifier.fillMaxWidth()) { Text("Save Cloud Key") }
        Spacer(Modifier.height(8.dp))
        Text("Astra never silently records audio, activates the camera, captures the screen, or bypasses Android security. Accessibility and notification access are user-enabled.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(Modifier.height(24.dp))
    }
}
