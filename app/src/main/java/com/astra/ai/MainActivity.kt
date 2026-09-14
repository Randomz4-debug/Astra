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
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    private lateinit var voice: MultilingualVoiceController
    private lateinit var screenCapture: ScreenCaptureManager
    private lateinit var camera: AstraCameraManager
    private lateinit var lanServer: AstraLanServer
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    private val screenCapturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val enabled = runCatching { screenCapture.attachResult(result.resultCode, data) }.getOrDefault(false)
        Toast.makeText(this, if (enabled) "Screen access enabled for this session" else "Screen access was not granted. Try again.", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS))
        voice = MultilingualVoiceController(this); screenCapture = ScreenCaptureManager(this); camera = AstraCameraManager(this); lanServer = AstraLanServer(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { AstraScreen(this) } }
    }
    fun listen(language: String, result: (String) -> Unit, state: (Boolean) -> Unit) = voice.listen(language, result, state)
    fun stopListening() = voice.stop()
    fun speak(text: String, language: String) = voice.speak(text, language)
    fun openWebUi() = startActivity(Intent(this, AstraWebActivity::class.java))
    fun startBackground() { if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, AstraForegroundService::class.java)) else startService(Intent(this, AstraForegroundService::class.java)) }
    fun stopBackground() = stopService(Intent(this, AstraForegroundService::class.java))
    fun requestScreenAccess() { runCatching { screenCapturePermission.launch(screenCapture.permissionIntent()) }.onFailure { Toast.makeText(this, "Could not open Android screen sharing: ${it.message ?: "try again"}", Toast.LENGTH_LONG).show() } }
    fun captureScreen(onText: (String) -> Unit) {
        if (!screenCapture.hasPermission()) return onText("Grant Screen Access first, then tap Read Screen.")
        screenCapture.capture { bitmap ->
            if (bitmap == null) return@capture onText("Screen capture failed. Grant Screen Access again if Android revoked it.")
            lifecycleScope.launch { val text = LocalOcrEngine().read(bitmap); bitmap.recycle(); onText(if (text.isBlank()) "No readable text was found on the screen." else text) }
        }
    }
    fun cameraOcr(onText: (String) -> Unit) {
        camera.bind(this) { ok ->
            if (!ok) return@bind onText("Camera could not be opened. Check camera permission.")
            camera.takePhoto { file ->
                if (file == null) return@takePhoto onText("Photo capture failed.")
                lifecycleScope.launch { val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath); val text = if (bitmap != null) LocalOcrEngine().read(bitmap) else ""; bitmap?.recycle(); onText(if (text.isBlank()) "Photo saved. No readable text was found." else text) }
            }
        }
    }
    fun translateLyrics(lyrics: String, source: String, target: String, onResult: (String) -> Unit) { lifecycleScope.launch { runCatching { LyricsTranslationEngine().translate(lyrics, source.ifBlank { "auto" }, target) }.onSuccess(onResult).onFailure { onResult("Translation failed: ${it.message ?: "unsupported language or model unavailable"}") } } }
    fun openAccessibilitySettings() = runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure { Toast.makeText(this, "Accessibility settings could not be opened.", Toast.LENGTH_SHORT).show() }
    fun openNotificationSettings() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    fun openBatterySettings() = startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    fun openOverlaySettings() = startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
    fun toggleLanServer(enabled: Boolean): String {
        if (enabled) return if (lanServer.start(8765)) "LAN Web UI: http://${lanAddress()}:8765" else "Could not start LAN Web UI (port 8765 may be busy)."
        lanServer.stop(); return "LAN Web UI stopped."
    }
    private fun lanAddress(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(":") == false }?.hostAddress ?: "<phone-ip>"
    }.getOrDefault("<phone-ip>")
    override fun onDestroy() { if (::lanServer.isInitialized) lanServer.stop(); screenCapture.release(); voice.release(); super.onDestroy() }
}

@Composable
fun AstraScreen(activity: MainActivity, vm: AstraViewModel = viewModel(factory = AstraViewModel.factory(activity))) {
    val ui by vm.ui.collectAsState(); var input by remember { mutableStateOf("") }; var language by remember { mutableStateOf("auto") }; var apiKey by remember { mutableStateOf("") }
    var localEndpoint by remember { mutableStateOf("http://127.0.0.1:11434") }; var localModel by remember { mutableStateOf("qwen2.5:0.5b") }; var lyrics by remember { mutableStateOf("") }
    var lyricsSource by remember { mutableStateOf("auto") }; var lyricsTarget by remember { mutableStateOf("en") }; var listening by remember { mutableStateOf(false) }; var lanEnabled by remember { mutableStateOf(false) }; var info by remember { mutableStateOf("") }
    LaunchedEffect(ui.response) { if (ui.response.isNotBlank()) activity.speak(ui.response, language) }
    Column(Modifier.fillMaxSize().background(Color(0xFF08090D)).verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp)); Text("ASTRA", style = MaterialTheme.typography.displaySmall); Text(if (listening) "LISTENING" else ui.state.name, color = Color.LightGray); Spacer(Modifier.height(20.dp))
        Box(Modifier.size(150.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) { Text("A", style = MaterialTheme.typography.displayLarge) }
        Spacer(Modifier.height(12.dp)); Text(ui.transcript, color = Color.LightGray); Text(ui.response)
        if (info.isNotBlank()) { Spacer(Modifier.height(8.dp)); Card(Modifier.fillMaxWidth()) { Text(info, Modifier.padding(12.dp)) } }
        Spacer(Modifier.height(12.dp)); Button({ activity.openWebUi() }, Modifier.fillMaxWidth()) { Text("Open Astra Web UI") }
        OutlinedTextField(language, { language = it }, Modifier.fillMaxWidth(), label = { Text("Voice language: auto, en-US, hi-IN...") }, singleLine = true); Spacer(Modifier.height(8.dp))
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text("Talk, type, or issue a command") }); Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ if (listening) activity.stopListening() else activity.listen(language, { text -> input = text; vm.ask(text) }, { listening = it }) }, Modifier.weight(1f)) { Text(if (listening) "Stop" else "Listen") }; Button({ if (input.isNotBlank()) { vm.ask(input); input = "" } }, Modifier.weight(1f)) { Text("Send") } }
        Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ activity.requestScreenAccess() }, Modifier.weight(1f)) { Text("Screen Access") }; OutlinedButton({ activity.captureScreen { info = it } }, Modifier.weight(1f)) { Text("Read Screen") } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ activity.cameraOcr { info = it } }, Modifier.weight(1f)) { Text("Camera + OCR") }; OutlinedButton({ activity.openAccessibilitySettings() }, Modifier.weight(1f)) { Text("Accessibility") } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ activity.openNotificationSettings() }, Modifier.weight(1f)) { Text("Notifications") }; OutlinedButton({ activity.openBatterySettings() }, Modifier.weight(1f)) { Text("Battery") } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ activity.openOverlaySettings() }, Modifier.weight(1f)) { Text("Overlay") }; OutlinedButton({ info = "Local-only: ${ui.localOnly}\nCloud configured: ${ui.cloudConfigured}" }, Modifier.weight(1f)) { Text("Privacy") } }
        Spacer(Modifier.height(10.dp)); Text("LAN WEB UI / API", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text(if (lanEnabled) "ON — other Wi-Fi devices can connect" else "OFF"); Switch(lanEnabled, { lanEnabled = it; info = activity.toggleLanServer(it) }) }
        Text("When ON, open http://PHONE-IP:8765 on any device on the same Wi-Fi. API: /api/models, /api/providers, /api/chat, /api/provider/chat and /api/parallel.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(Modifier.height(10.dp)); Text("LOCAL AI RUNTIME", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(localEndpoint, { localEndpoint = it }, Modifier.fillMaxWidth(), label = { Text("Ollama/PocketLLM endpoint") }, singleLine = true); OutlinedTextField(localModel, { localModel = it }, Modifier.fillMaxWidth(), label = { Text("Local model") }, singleLine = true)
        Button({ LocalAiGateway(activity).configure(localEndpoint, localModel); info = "Local AI runtime saved." }, Modifier.fillMaxWidth()) { Text("Save Local AI Runtime") }
        Spacer(Modifier.height(10.dp)); Text("LYRICS TRANSLATOR", style = MaterialTheme.typography.titleMedium); OutlinedTextField(lyrics, { lyrics = it }, Modifier.fillMaxWidth(), label = { Text("Paste lyrics") })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(lyricsSource, { lyricsSource = it }, Modifier.weight(1f), label = { Text("From") }, singleLine = true); OutlinedTextField(lyricsTarget, { lyricsTarget = it }, Modifier.weight(1f), label = { Text("To") }, singleLine = true) }
        Button({ activity.translateLyrics(lyrics, lyricsSource, lyricsTarget) { info = it } }, Modifier.fillMaxWidth(), enabled = lyrics.isNotBlank() && lyricsTarget.isNotBlank()) { Text("Translate Lyrics On-Device") }
        Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Row(verticalAlignment = Alignment.CenterVertically) { Switch(ui.localOnly, vm::setLocalOnly); Text("Local only") }; Row(verticalAlignment = Alignment.CenterVertically) { Switch(ui.background, { checked -> vm.setBackground(checked); if (checked) activity.startBackground() else activity.stopBackground() }); Text("Background") } }
        Spacer(Modifier.height(8.dp)); Text("Cloud mode is optional. The API key is encrypted with Android Keystore.", style = MaterialTheme.typography.bodySmall, color = Color.Gray); OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("Optional OpenAI API key") }, singleLine = true); Spacer(Modifier.height(6.dp)); Button({ vm.setOpenAiApiKey(apiKey); info = "Cloud credential saved in Android Keystore." }, Modifier.fillMaxWidth()) { Text("Save Cloud Key") }
        Spacer(Modifier.height(8.dp)); Text("Astra never silently records audio, activates the camera, captures the screen, or bypasses Android security.", style = MaterialTheme.typography.bodySmall, color = Color.Gray); Spacer(Modifier.height(24.dp))
    }
}
