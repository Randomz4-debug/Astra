package com.astra.ai

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import kotlin.math.PI
import kotlin.math.sin

class AstraMainActivity : ComponentActivity() {
    private lateinit var voice: MultilingualVoiceController
    private lateinit var capture: ScreenCaptureManager
    private lateinit var lan: AstraLanServer
    private val files = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) lifecycleScope.launch(Dispatchers.IO) {
            val intake = AstraFileIntake(this@AstraMainActivity)
            val count = uris.count { intake.importUri(it) != null }
            withContext(Dispatchers.Main) { Toast.makeText(this@AstraMainActivity, "Astra imported $count file(s)", Toast.LENGTH_SHORT).show() }
        }
    }
    private val assistantRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { Toast.makeText(this, if (isDefaultAssistant()) "Astra is now the default assistant." else "Astra was not selected.", Toast.LENGTH_LONG).show() }
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) { Toast.makeText(this, "Screen access was not granted.", Toast.LENGTH_SHORT).show(); return@registerForActivityResult }
        runCatching { val service = Intent(this, AstraForegroundService::class.java).setAction(AstraForegroundService.ACTION_START_PROJECTION); if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service) }
            .onFailure { Toast.makeText(this, "Could not start screen-access service: ${it.message}", Toast.LENGTH_LONG).show(); return@onFailure }
        lifecycleScope.launch { delay(300); val ok = runCatching { capture.attachResult(result.resultCode, result.data) }.getOrDefault(false); Toast.makeText(this@AstraMainActivity, if (ok) "Screen access enabled." else "Screen access failed. Try again.", Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        voice = MultilingualVoiceController(this)
        capture = ScreenCaptureManager(this)
        lan = AstraLanServer(applicationContext)
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS), 90)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFF2146), secondary = Color(0xFFB40025), tertiary = Color(0xFF4C8CFF), background = Color(0xFF05060A), surface = Color(0xFF0D0E14))) { AstraHome(this) } }
    }

    fun isDefaultAssistant() = android.os.Build.VERSION.SDK_INT >= 29 && runCatching { getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    fun chooseDefaultAssistant() {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val role = getSystemService(RoleManager::class.java)
        runCatching {
            if (role.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) assistantRole.launch(role.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
            else startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } }
    }
    fun listen(lang: String, result: (String) -> Unit) = voice.listen(lang, result, {})
    fun stopListening() = voice.stop()
    fun speak(text: String) { if (text.isNotBlank()) voice.speak(text) }
    fun openAccessibility() = runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    fun openNotifications() = runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    fun openBattery() = runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    fun openOverlay() = runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
    fun openCamera(front: Boolean) = runCatching { startActivity(Intent(this, AstraCameraActivity::class.java).putExtra("front", front)) }
    fun chooseFiles() = runCatching { files.launch(arrayOf("*/*")) }
    fun screenAccess() = runCatching { projection.launch(capture.permissionIntent()) }.onFailure { Toast.makeText(this, "Screen access could not open: ${it.message}", Toast.LENGTH_LONG).show() }
    fun readScreen(done: (String) -> Unit) {
        val accessibilityText = AstraAccessibilityService.current()?.readScreen().orEmpty().trim()
        if (accessibilityText.isNotBlank()) { done(accessibilityText); return }
        if (!capture.hasPermission()) { done("Enable Accessibility Access or Screen Access first."); return }
        capture.capture { bitmap ->
            if (bitmap == null) { done("Screen capture failed. Re-enable Screen Access."); return@capture }
            lifecycleScope.launch(Dispatchers.Default) { val text = runCatching { LocalOcrEngine().read(bitmap) }.getOrDefault(""); bitmap.recycle(); withContext(Dispatchers.Main) { done(text.ifBlank { "No readable text was found on the current screen." }) } }
        }
    }
    fun webUrl() = "http://${localIp()}:8765"
    fun copyWebUrl() { lifecycleScope.launch(Dispatchers.IO) { val ok = lan.start(8765) || lan.isRunning(); val value = webUrl(); withContext(Dispatchers.Main) { if (ok) { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Astra Web UI", value)); Toast.makeText(this@AstraMainActivity, "Copied $value", Toast.LENGTH_LONG).show() } else Toast.makeText(this@AstraMainActivity, "LAN server could not start.", Toast.LENGTH_LONG).show() } } }
    fun lan(on: Boolean, done: (String) -> Unit) { lifecycleScope.launch(Dispatchers.IO) { val r = if (on) if (lan.start(8765)) "Web UI: ${webUrl()}" else "Could not start LAN server." else { lan.stop(); "LAN server stopped." }; withContext(Dispatchers.Main) { done(r) } } }
    private fun localIp(): String = runCatching { NetworkInterface.getNetworkInterfaces()?.asSequence()?.flatMap { it.inetAddresses.asSequence() }?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(":") == false }?.hostAddress ?: "127.0.0.1" }.getOrDefault("127.0.0.1")
    override fun onDestroy() { if (::capture.isInitialized) capture.release(); if (::voice.isInitialized) voice.release(); super.onDestroy() }
}

@Composable
private fun AstraWave() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "astra_wave")
    val phase by transition.animateFloat(0f, (2 * PI).toFloat(), androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1500, easing = androidx.compose.animation.core.LinearEasing)), label = "phase")
    Canvas(Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(26.dp)).background(Brush.radialGradient(listOf(Color(0xFF26050D), Color(0xFF07080C))))) {
        val center = size.height / 2f
        val gradient = Brush.horizontalGradient(listOf(Color(0xFF1554FF), Color(0xFF61C7FF), Color(0xFFE4FBFF), Color(0xFF61C7FF), Color(0xFF1554FF)))
        repeat(3) { layer ->
            val path = Path(); var x = 0f; var first = true
            while (x <= size.width) {
                val env = (1f - kotlin.math.abs(x - size.width / 2f) / (size.width / 2f)).coerceAtLeast(0f)
                val y = center + sin(x * (0.017f + layer * .004f) + phase * (1f + layer * .15f)) * size.height * (.08f + layer * .035f) * (.25f + env)
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y); x += 4f
            }
            drawPath(path, gradient, style = Stroke(if (layer == 0) 5f else 2f, cap = StrokeCap.Round))
        }
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF4C8CFF), Color.Transparent)), Offset(0f, center), Offset(size.width, center), 1f)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AstraHome(a: AstraMainActivity, vm: AstraViewModel = viewModel(factory = AstraViewModel.factory(a))) {
    val ui by vm.ui.collectAsState()
    val live by VoiceTelemetry.listening.collectAsState()
    val rms by VoiceTelemetry.rms.collectAsState()
    val runtime = remember { AstraAgentRuntime(a) }
    val store = remember { AstraCustomCommandStore(a) }
    val chatStore = remember { AstraChatStore(a) }
    var page by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("auto") }
    var info by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf("") }
    var actions by remember { mutableStateOf("") }
    var lanOn by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var localModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var openAiModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelStatus by remember { mutableStateOf("") }
    var discoveringModels by remember { mutableStateOf(false) }
    var openAiKey by remember { mutableStateOf("") }
    var openAiModel by remember { mutableStateOf("gpt-5.6-luna") }
    var showOpenAiKey by remember { mutableStateOf(false) }
    var openAiStatus by remember { mutableStateOf("") }
    val openAi = remember { OpenAiSettings(a) }
    val local = remember { LocalAiGateway(a) }

    LaunchedEffect(Unit) { endpoint = local.endpoint(); model = local.model(); openAiModel = openAi.model() }

    fun discoverAllModels() {
        if (discoveringModels) return
        discoveringModels = true
        modelStatus = "Detecting local and cloud models…"
        a.lifecycleScope.launch(Dispatchers.IO) {
            val localResult = runCatching { local.discoverModels() }.getOrDefault(emptyList())
            val cloudResult = runCatching { openAi.discoverModels() }.getOrDefault(emptyList())
            val diagnosis = runCatching { local.diagnose() }.getOrDefault("Local discovery finished.")
            withContext(Dispatchers.Main) {
                localModels = localResult
                openAiModels = cloudResult
                if (endpoint.contains(":12434") && localResult.isNotEmpty()) { endpoint = endpoint.replace(":12434", ":11434"); local.configure(endpoint, model) }
                if (model.isBlank() && localResult.isNotEmpty()) { model = localResult.first(); local.configure(endpoint, model) }
                if (openAiModel.isBlank() && cloudResult.isNotEmpty()) openAiModel = cloudResult.first()
                modelStatus = buildString { append(diagnosis); append("\nDetected ${localResult.size} local model(s)"); if (openAi.hasApiKey()) append(" • ${cloudResult.size} OpenAI model(s)") else append(" • OpenAI key not configured") }
                discoveringModels = false
            }
        }
    }

    LaunchedEffect(page) { if (page == 1) discoverAllModels() }
    LaunchedEffect(ui.state, ui.response) { if (ui.state == AssistantState.SPEAKING && ui.response.isNotBlank()) a.speak(ui.response) }

    Scaffold(
        containerColor = Color(0xFF05060A),
        topBar = { TopAppBar(title = { Text("ASTRA", color = Color.White) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)) },
        bottomBar = { NavigationBar(containerColor = Color(0xFF0A0B11)) {
            NavigationBarItem(page == 0, { page = 0 }, icon = { Text("●") }, label = { Text("Chat") })
            NavigationBarItem(page == 1, { page = 1 }, icon = { Text("⚙") }, label = { Text("Settings") })
            NavigationBarItem(page == 2, { page = 2 }, icon = { Text("🔐") }, label = { Text("Access") })
            NavigationBarItem(page == 3, { page = 3 }, icon = { Text("⌘") }, label = { Text("APIs") })
        } }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(62.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFFF2146), Color(0xFF780015)))), contentAlignment = Alignment.Center) { Text("A", color = Color.White, style = MaterialTheme.typography.headlineLarge) }; Spacer(Modifier.width(12.dp)); Column { Text("ASTRA", color = Color.White, style = MaterialTheme.typography.headlineMedium); Text(if (live) "LISTENING • LIVE" else ui.state.name, color = Color(0xFFB8BBC6)) }
                }
                Spacer(Modifier.height(10.dp)); AstraWave(); Text("Voice activity ${rms.toInt()} dB", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
            if (page == 0) {
                item {
                    Text("Ask Astra", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), label = { Text("Message or command") }, shape = RoundedCornerShape(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ if (message.isNotBlank()) { vm.ask(message); message = "" } }, Modifier.weight(1f)) { Text("Send") }; OutlinedButton({ if (live) a.stopListening() else a.listen("auto") { vm.ask(it) } }, Modifier.weight(1f)) { Text(if (live) "Stop" else "Listen") } }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ a.chooseFiles() }, Modifier.weight(1f)) { Text("Files") }; OutlinedButton({ a.openCamera(false) }, Modifier.weight(1f)) { Text("Camera") }; OutlinedButton({ if (message.isNotBlank()) { AstraTaskManager(a).start(message); info = "Background task started"; message = "" } }, Modifier.weight(1f)) { Text("Background") } }
                    if (ui.response.isNotBlank()) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF11131B))) { Text(ui.response, Modifier.padding(14.dp), color = Color.White) }
                }
                item { Text("AI MODE", color = Color.White, style = MaterialTheme.typography.titleMedium); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("auto", "online", "offline").forEach { m -> FilterChip(mode == m, { mode = m; vm.setAiMode(m) }, label = { Text(m.uppercase()) }) } } }
            }
            if (page == 1) {
                item {
                    Text("CUSTOM COMMANDS", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Create commands from Settings or by voice. Example: astra-talk → open_app:WhatsApp;assist_chat", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(trigger, { trigger = it }, Modifier.fillMaxWidth(), label = { Text("Trigger / voice phrase") }, singleLine = true)
                    OutlinedTextField(actions, { actions = it }, Modifier.fillMaxWidth().height(120.dp), label = { Text("Actions separated by ;") }, supportingText = { Text("open_app:YouTube;wait:1000;click:Search;type:hello;assist_chat") })
                    Button({ runCatching { store.addOrUpdate(trigger, actions); trigger = ""; actions = ""; info = "Custom command saved." }.onFailure { info = it.message ?: "Could not save command." } }, Modifier.fillMaxWidth()) { Text("Add custom command") }
                    Text("Global stop command: terminate", color = Color(0xFFFF6D82))
                    store.list().forEach { c -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF11131B))) { Column(Modifier.padding(12.dp)) { Text("${c.id} • ${c.trigger}", color = Color.White, style = MaterialTheme.typography.titleMedium); Text(c.actions, color = Color.LightGray, style = MaterialTheme.typography.bodySmall); Row(verticalAlignment = Alignment.CenterVertically) { Switch(c.enabled, { store.setEnabled(c.trigger, it) }); Text("Enabled", color = Color.Gray); Spacer(Modifier.weight(1f)); OutlinedButton({ store.delete(c.trigger) }) { Text("Delete") } } } } }
                }
                item {
                    Text("AI & MODELS", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Astra automatically checks the configured Ollama/OpenAI-compatible server and your OpenAI account. You can still enter a model manually.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("Ollama / compatible URL") }, singleLine = true, supportingText = { Text("Ollama normally uses http://YOUR-PC-IP:11434") })
                    OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model (manual or choose below)") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ local.configure(endpoint, model); info = "Local AI settings saved." }, Modifier.weight(1f)) { Text("Save") }; OutlinedButton({ discoverAllModels() }, Modifier.weight(1f), enabled = !discoveringModels) { Text(if (discoveringModels) "Detecting…" else "Discover Models") } }
                    if (localModels.isNotEmpty()) {
                        Text("LOCAL / OLLAMA MODELS", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { localModels.forEach { item -> FilterChip(model == item, { model = item; local.configure(endpoint, item) }, label = { Text(item) }) } }
                    }
                    if (openAiModels.isNotEmpty()) {
                        Text("OPENAI MODELS", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { openAiModels.forEach { item -> FilterChip(openAiModel == item, { openAiModel = item; openAi.setModel(item) }, label = { Text(item) }) } }
                    }
                    if (modelStatus.isNotBlank()) Text(modelStatus, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({ a.lifecycleScope.launch { modelStatus = local.diagnose() } }, Modifier.fillMaxWidth()) { Text("Test Local AI Connection") }
                }
                item {
                    Text("OPENAI", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Use OpenAI for Astra's online reasoning. The API key is stored locally using Android Keystore encryption.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(openAiKey, { openAiKey = it }, Modifier.fillMaxWidth(), label = { Text("OpenAI API key") }, singleLine = true, visualTransformation = if (showOpenAiKey) VisualTransformation.None else PasswordVisualTransformation())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ showOpenAiKey = !showOpenAiKey }, Modifier.weight(1f)) { Text(if (showOpenAiKey) "Hide Key" else "Show Key") }; Button({ openAi.saveApiKey(openAiKey); openAiKey = ""; openAiStatus = "API key saved securely on this device." }, Modifier.weight(1f)) { Text("Save Key") } }
                    OutlinedTextField(openAiModel, { openAiModel = it }, Modifier.fillMaxWidth(), label = { Text("OpenAI model") }, singleLine = true, supportingText = { Text("Choose a detected model or enter one manually.") })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ openAi.setModel(openAiModel); openAiStatus = "Model saved: ${openAiModel.trim()}" }, Modifier.weight(1f)) { Text("Save Model") }; OutlinedButton({ a.lifecycleScope.launch { openAiStatus = openAi.testConnection() } }, Modifier.weight(1f)) { Text("Test") } }
                    OutlinedButton({ openAi.clearApiKey(); openAiKey = ""; openAiModels = emptyList(); openAiStatus = "OpenAI API key cleared from Astra." }, Modifier.fillMaxWidth()) { Text("Clear API Key") }
                    Text(if (openAi.hasApiKey()) "Status: API key configured" else "Status: API key not configured", color = if (openAi.hasApiKey()) Color(0xFF69E6A5) else Color(0xFFFF9AA9), style = MaterialTheme.typography.bodySmall)
                    if (openAiStatus.isNotBlank()) Text(openAiStatus, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
                item { Text("LAN WEB UI", color = Color.White, style = MaterialTheme.typography.titleMedium); Row(verticalAlignment = Alignment.CenterVertically) { Switch(lanOn, { lanOn = it; a.lan(it) { info = it } }); Text(if (lanOn) "ON" else "OFF", color = Color.White) }; OutlinedTextField(a.webUrl(), {}, Modifier.fillMaxWidth(), label = { Text("Astra Web UI URL") }, readOnly = true, singleLine = true); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ a.copyWebUrl() }, Modifier.weight(1f)) { Text("Copy URL") }; OutlinedButton({ a.startActivity(Intent(a, AstraWebActivity::class.java)) }, Modifier.weight(1f)) { Text("Open") } } }
                item { Text("DEFAULT ASSISTANT", color = Color.White, style = MaterialTheme.typography.titleMedium); Button({ a.chooseDefaultAssistant() }, Modifier.fillMaxWidth()) { Text(if (a.isDefaultAssistant()) "Astra is Default AI" else "Set Astra as Default AI") } }
            }
            if (page == 2) {
                item { Text("SCREEN & APP ACCESS", color = Color.White, style = MaterialTheme.typography.titleMedium); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ a.screenAccess() }, Modifier.weight(1f)) { Text("Enable Screen Access") }; OutlinedButton({ a.readScreen { info = it } }, Modifier.weight(1f)) { Text("Read Current Screen") } }; Text("Accessibility text is used first; OCR is the fallback after Screen Access is granted.", color = Color.Gray, style = MaterialTheme.typography.bodySmall); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ a.openAccessibility() }, Modifier.weight(1f)) { Text("Accessibility Access") }; OutlinedButton({ a.openNotifications() }, Modifier.weight(1f)) { Text("Notifications") } } }
                item { Text("CAMERA", color = Color.White, style = MaterialTheme.typography.titleMedium); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ a.openCamera(false) }, Modifier.weight(1f)) { Text("Back + Capture") }; OutlinedButton({ a.openCamera(true) }, Modifier.weight(1f)) { Text("Front + Capture") } }; Text("Astra Camera can flip, capture and open the saved photo in Gallery.", color = Color.Gray) }
                item { Text("SYSTEM", color = Color.White, style = MaterialTheme.typography.titleMedium); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ a.openBattery() }, Modifier.weight(1f)) { Text("Battery") }; OutlinedButton({ a.openOverlay() }, Modifier.weight(1f)) { Text("Overlay") } } }
            }
            if (page == 3) {
    item { AstraApiPanel() }
    item { Text("CHAT HISTORY", color = Color.White, style = MaterialTheme.typography.titleMedium); Button({ runtime.newChat(); info = "New chat created." }, Modifier.fillMaxWidth()) { Text("New chat") } }
    items(chatStore.listChats(), key = { it.id }) { c -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF11131B))) { Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Text(c.title, color = Color.White, Modifier.weight(1f)); TextButton({ runtime.selectChat(c.id) }) { Text("Open") } } } }
}
            if (info.isNotBlank()) item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1015))) { Text(info, Modifier.padding(12.dp), color = Color.White) } }
        }
    }
}
