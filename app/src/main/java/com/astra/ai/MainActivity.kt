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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import kotlin.math.PI
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private lateinit var voice: MultilingualVoiceController
    private lateinit var capture: ScreenCaptureManager
    private lateinit var camera: AstraCameraManager
    private lateinit var lan: AstraLanServer
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val assistantRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Toast.makeText(this, if (isDefaultAssistant()) "Astra is now the default assistant." else "Astra was not selected as the default assistant.", Toast.LENGTH_LONG).show()
    }
    private val files = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) lifecycleScope.launch(Dispatchers.IO) {
            val intake = AstraFileIntake(this@MainActivity)
            val imported = uris.mapNotNull { intake.importUri(it) }
            withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Imported ${imported.size} file(s) into Astra workspace", Toast.LENGTH_SHORT).show() }
        }
    }
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val ok = runCatching { capture.attachResult(r.resultCode, r.data) }.getOrDefault(false)
        Toast.makeText(this, if (ok) "Screen access enabled" else "Screen access not granted", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS))
        voice = MultilingualVoiceController(this)
        capture = ScreenCaptureManager(this)
        camera = AstraCameraManager(this)
        lan = AstraLanServer(applicationContext)
        setContent {
            val colors = darkColorScheme(primary = Color(0xFFFF2146), secondary = Color(0xFFB40025), tertiary = Color(0xFF4B8DFF), background = Color(0xFF05060A), surface = Color(0xFF0D0E14))
            MaterialTheme(colorScheme = colors) { AstraScreen(this) }
        }
    }

    fun requestAssistantRole() {
        if (android.os.Build.VERSION.SDK_INT < 29) { Toast.makeText(this, "Assistant role requires Android 10+", Toast.LENGTH_SHORT).show(); return }
        val rm = getSystemService(RoleManager::class.java)
        runCatching {
            if (rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                if (rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) Toast.makeText(this, "Astra is already the default assistant.", Toast.LENGTH_SHORT).show()
                else assistantRole.launch(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
            } else startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                .onFailure { Toast.makeText(this, "Default assistant settings could not be opened: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    fun isDefaultAssistant(): Boolean = android.os.Build.VERSION.SDK_INT >= 29 && runCatching { getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    fun listen(l: String, r: (String) -> Unit, s: (Boolean) -> Unit) = voice.listen(l, r, s)
    fun alwaysListen(l: String, r: (String) -> Unit) = voice.startAlwaysListening(l, r)
    fun stopListening() = voice.stop()
    fun speak(t: String, l: String) = voice.speak(t, l)
    fun voices() = voice.availableVoices()
    fun selectVoice(n: String) = voice.setVoice(n)
    fun startBackground() { runCatching { if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, AstraForegroundService::class.java)) else startService(Intent(this, AstraForegroundService::class.java)) } }
    fun openWebUi() = runCatching { startActivity(Intent(this, AstraWebActivity::class.java)) }
    fun screenAccess() = runCatching { projection.launch(capture.permissionIntent()) }.onFailure { Toast.makeText(this, "Screen sharing could not open: ${it.message}", Toast.LENGTH_LONG).show() }
    fun readScreen(cb: (String) -> Unit) {
        if (!capture.hasPermission()) return cb("Grant Screen Access first.")
        capture.capture { bmp ->
            if (bmp == null) return@capture cb("Screen capture failed. Grant access again.")
            lifecycleScope.launch(Dispatchers.Default) { val t = LocalOcrEngine().read(bmp); bmp.recycle(); withContext(Dispatchers.Main) { cb(t.ifBlank { "No readable text found." }) } }
        }
    }
    fun cam(front: Boolean = false) = runCatching { startActivity(Intent(this, AstraCameraActivity::class.java).putExtra("front", front)) }
    fun pick() = runCatching { files.launch(arrayOf("*/*")) }
    fun accessibility() = runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    fun notifications() = runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    fun battery() = runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    fun overlay() = runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }

    fun webUrl(): String = "http://${ip()}:8765"
    fun copyWebUrl() {
        runCatching {
            if (!lan.isRunning()) lan.start(8765)
            val value = webUrl()
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Astra Web UI", value))
            Toast.makeText(this, "Web UI URL copied: $value", Toast.LENGTH_LONG).show()
        }.onFailure { Toast.makeText(this, "Could not copy Web UI URL: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    fun lanToggle(on: Boolean, callback: (String) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = if (on) { if (!lan.start(8765)) "Could not start LAN server. Port 8765 may already be in use." else "LAN UI: ${webUrl()}" } else { lan.stop(); "LAN server stopped." }
            withContext(Dispatchers.Main) { if (!isFinishing && !isDestroyed) callback(result) }
        }
    }
    private fun ip(): String = runCatching { NetworkInterface.getNetworkInterfaces()?.asSequence()?.flatMap { it.inetAddresses.asSequence() }?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(":") == false }?.hostAddress ?: "127.0.0.1" }.getOrDefault("127.0.0.1")

    override fun onDestroy() {
        if (::capture.isInitialized) capture.release()
        if (::voice.isInitialized) voice.release()
        super.onDestroy()
    }
}

@Composable
private fun AstraWave(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "astra_wave")
    val phase by transition.animateFloat(0f, (2f * PI).toFloat(), animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1500, easing = androidx.compose.animation.core.LinearEasing)), label = "phase")
    Canvas(modifier.clip(RoundedCornerShape(26.dp)).background(Brush.radialGradient(listOf(Color(0xFF23050D), Color(0xFF08090E))))) {
        val center = size.height / 2f
        val colors = listOf(Color(0xFF1E63FF), Color(0xFF63C7FF), Color(0xFFB9F4FF), Color(0xFF4C8CFF), Color(0xFF1E63FF))
        for (layer in 0..2) {
            val path = Path(); val amp = size.height * (0.10f + layer * 0.055f); val frequency = 0.018f + layer * 0.004f
            var first = true; var x = 0f
            while (x <= size.width) {
                val envelope = (1f - kotlin.math.abs(x - size.width / 2f) / (size.width / 2f)).coerceAtLeast(0f)
                val y = center + sin(x * frequency + phase * (1f + layer * .15f)) * amp * (0.25f + envelope * .9f)
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                x += 4f
            }
            drawPath(path, Brush.horizontalGradient(colors), style = Stroke(width = if (layer == 0) 5f else 2f, cap = StrokeCap.Round))
        }
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF4C8CFF), Color.Transparent)), Offset(0f, center), Offset(size.width, center), 1.5f)
    }
}

@Composable
private fun AstraScreen(a: MainActivity, vm: AstraViewModel = viewModel(factory = AstraViewModel.factory(a))) {
    val ui by vm.ui.collectAsState(); val rms by VoiceTelemetry.rms.collectAsState(); val live by VoiceTelemetry.listening.collectAsState()
    val runtime = remember { AstraAgentRuntime(a) }; val chatsStore = remember { AstraChatStore(a) }; val taskManager = remember { AstraTaskManager(a) }
    var text by remember { mutableStateOf("") }; var lang by remember { mutableStateOf("auto") }; var endpoint by remember { mutableStateOf("http://127.0.0.1:11434") }
    var model by remember { mutableStateOf("") }; var key by remember { mutableStateOf("") }; var mode by remember { mutableStateOf("auto") }; var always by remember { mutableStateOf(false) }
    var lanOn by remember { mutableStateOf(false) }; var info by remember { mutableStateOf("") }; var page by remember { mutableStateOf(0) }; var voices by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }; var selectedChatId by remember { mutableStateOf(runtime.currentChatId()) }; var renameTarget by remember { mutableStateOf<AstraChatStore.Chat?>(null) }; var renameText by remember { mutableStateOf("") }; var discovered by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { endpoint = LocalAiGateway(a).endpoint(); model = LocalAiGateway(a).model(); key = SecureSettings(a).getOpenAiApiKey().orEmpty() }

    Box(Modifier.fillMaxSize().background(Color(0xFF05060A))) {
        Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text("Astra", color = Color.White) }, navigationIcon = { IconButton({ drawerOpen = true }) { Text("☰", color = Color.White) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)) }, bottomBar = {
            NavigationBar(containerColor = Color(0xFF0A0B11)) {
                NavigationBarItem(page == 0, { page = 0 }, icon = { Text("●") }, label = { Text("Chat") }); NavigationBarItem(page == 1, { page = 1 }, icon = { Text("⚙") }, label = { Text("Settings") }); NavigationBarItem(page == 2, { page = 2 }, icon = { Text("🔐") }, label = { Text("Access") }); NavigationBarItem(page == 3, { page = 3 }, icon = { Text("▣") }, label = { Text("Chats") })
            }
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(62.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFFF2146), Color(0xFF780015)))), contentAlignment = Alignment.Center) { Text("A", color = Color.White, style = MaterialTheme.typography.headlineLarge) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("ASTRA", color = Color.White, style = MaterialTheme.typography.headlineMedium); Text(if (live) "LISTENING • LIVE" else ui.state.name, color = if (live) Color(0xFF6EDBFF) else Color.LightGray) } }
                    Spacer(Modifier.height(10.dp)); AstraWave(Modifier.fillMaxWidth().height(100.dp)); Text("Voice activity ${rms.toInt()} dB", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
                if (page == 0) {
                    item { Text("Current conversation", color = Color.White, style = MaterialTheme.typography.titleMedium) }
                    val messages = chatsStore.messages(selectedChatId, 0L, 500)
                    if (messages.isEmpty()) item { Text("Say or type anything. Astra can execute supported Android actions and multi-step tasks.", color = Color.Gray) }
                    items(messages.takeLast(100), key = { it.id }) { m -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (m.role == "user") Color(0xFF171923) else Color(0xFF180B10)), shape = RoundedCornerShape(18.dp)) { Text(if (m.role == "user") "You\n${m.text}" else "Astra\n${m.text}", Modifier.padding(13.dp), color = Color.White) } }
                    item {
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Message Astra") }, shape = RoundedCornerShape(18.dp)); Spacer(Modifier.height(7.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ if (text.isNotBlank()) { vm.ask(text); text = "" } }, Modifier.weight(1f)) { Text("Send") }; OutlinedButton({ if (live) a.stopListening() else a.listen(lang, { text = it; vm.ask(it) }, {}) }, Modifier.weight(1f)) { Text(if (live) "Stop" else "Listen") } }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ a.pick() }, Modifier.weight(1f)) { Text("Files") }; OutlinedButton({ a.openWebUi() }, Modifier.weight(1f)) { Text("Web UI") }; OutlinedButton({ if (text.isNotBlank()) { val id = taskManager.start(text); info = "Background task $id started"; text = "" } }, Modifier.weight(1f)) { Text("Background") } }
                    }
                }
                item { Text("AI MODE", color = Color.White, style = MaterialTheme.typography.titleMedium); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("auto", "online", "offline").forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m; vm.setAiMode(m) }, label = { Text(m.uppercase()) }) } } }
                if (page == 1) {
                    item {
                        Text("AI & MODELS", color = Color.White, style = MaterialTheme.typography.titleMedium); OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("Ollama / OpenAI-compatible URL") }, singleLine = true); OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Local model (blank = automatic)") }, singleLine = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ LocalAiGateway(a).configure(endpoint, model); info = "AI endpoint saved." }, Modifier.weight(1f)) { Text("Save") }; OutlinedButton({ a.lifecycleScope.launch(Dispatchers.IO) { val x = runCatching { AiProviderRegistry(a).discoverJson() }.getOrElse { "Discovery error: ${it.message}" }; withContext(Dispatchers.Main) { discovered = x; info = if (x.startsWith("Discovery error")) x else "Model discovery complete." } } }, Modifier.weight(1f)) { Text("Discover") } }
                        if (discovered.isNotBlank()) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF11131B))) { Text(discovered, Modifier.padding(10.dp), color = Color.LightGray, style = MaterialTheme.typography.bodySmall) }
                    }
                    item { Text("VOICE & LISTENING", color = Color.White, style = MaterialTheme.typography.titleMedium); OutlinedTextField(lang, { lang = it }, Modifier.fillMaxWidth(), label = { Text("Language / locale (auto)") }, singleLine = true); Button({ voices = true }, Modifier.fillMaxWidth()) { Text("Choose installed voice") }; Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("Always listen"); Switch(always, { always = it; vm.setAlwaysListen(it) }) }; Text("Always listening uses a visible Android microphone foreground service and must be enabled by you.", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
                    item { Text("LAN / WEB UI", color = Color.White, style = MaterialTheme.typography.titleMedium); Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text(if (lanOn) "LAN server ON" else "LAN server OFF"); Switch(lanOn, { v -> lanOn = v; a.lanToggle(v) { info = it; lanOn = v && it.startsWith("LAN UI:") } }) }; OutlinedTextField(a.webUrl(), {}, Modifier.fillMaxWidth(), label = { Text("Astra Web UI URL") }, readOnly = true, singleLine = true); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ a.copyWebUrl() }, Modifier.weight(1f)) { Text("Copy URL") }; OutlinedButton({ a.openWebUi() }, Modifier.weight(1f)) { Text("Open") } }; Text("Use the copied URL from another device on the same Wi-Fi after turning LAN server ON.", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
                    item { Text("CLOUD", color = Color.White, style = MaterialTheme.typography.titleMedium); OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("OpenAI API key") }, singleLine = true); Button({ vm.setOpenAiApiKey(key); info = "Cloud key saved." }, Modifier.fillMaxWidth()) { Text("Save API Key") } }
                    item { Text("BACKGROUND TASKS", color = Color.White, style = MaterialTheme.typography.titleMedium); taskManager.all().takeLast(8).forEach { t -> Text("${t.id} • ${t.status} • ${t.step}/${t.total}", color = Color.LightGray) } }
                }
                if (page == 2) {
                    item { Text("DEFAULT ASSISTANT", color = Color.White, style = MaterialTheme.typography.titleMedium); Button({ a.requestAssistantRole() }, Modifier.fillMaxWidth()) { Text(if (a.isDefaultAssistant()) "Astra is Default Assistant" else "Set Astra as Default AI Assistant") }; Text("Android's RoleManager controls the final selection. If the role dialog is unavailable, Astra opens the relevant system settings instead.", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
                    item { Text("ANDROID ACCESS", color = Color.White, style = MaterialTheme.typography.titleMedium); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedButton({ a.screenAccess() }, Modifier.weight(1f)) { Text("Screen Access") }; OutlinedButton({ a.readScreen { info = it } }, Modifier.weight(1f)) { Text("Read Screen") } }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedButton({ a.accessibility() }, Modifier.weight(1f)) { Text("Accessibility") }; OutlinedButton({ a.notifications() }, Modifier.weight(1f)) { Text("Notifications") } }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedButton({ a.cam(false) }, Modifier.weight(1f)) { Text("Back Camera") }; OutlinedButton({ a.cam(true) }, Modifier.weight(1f)) { Text("Front Camera") } }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedButton({ a.pick() }, Modifier.weight(1f)) { Text("Multiple Files") }; OutlinedButton({ a.battery() }, Modifier.weight(1f)) { Text("Battery") } }; OutlinedButton({ a.overlay() }, Modifier.fillMaxWidth()) { Text("Overlay Permission") } }
                }
                if (page == 3) {
                    item { Text("CHAT HISTORY", color = Color.White, style = MaterialTheme.typography.titleMedium); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Button({ selectedChatId = runtime.newChat() }, Modifier.weight(1f)) { Text("New chat") }; OutlinedButton({ runtime.clearAllChats(); selectedChatId = runtime.currentChatId() }, Modifier.weight(1f)) { Text("Delete all") } } }
                    val chatList = chatsStore.listChats()
                    items(chatList, key = { "chat_${it.id}" }) { chat -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (chat.id == selectedChatId) Color(0xFF241018) else Color(0xFF11131A))) { Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(chat.title, color = Color.White, maxLines = 1); Text(java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(chat.updatedAt)), color = Color.Gray, style = MaterialTheme.typography.bodySmall) }; TextButton({ selectedChatId = chat.id; runtime.selectChat(chat.id) }) { Text("Open") }; TextButton({ renameTarget = chat; renameText = chat.title }) { Text("Rename") }; TextButton({ runtime.deleteChat(chat.id); selectedChatId = runtime.currentChatId() }) { Text("Delete") } } } }
                }
                if (info.isNotBlank()) item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF17131A))) { Text(info, Modifier.padding(12.dp), color = Color.White) } }
            }
        }
        AnimatedVisibility(drawerOpen, enter = slideInHorizontally(), exit = slideOutHorizontally(), modifier = Modifier.align(Alignment.CenterStart)) {
            Surface(Modifier.fillMaxHeight().width(310.dp), color = Color(0xFF0D0E14), shadowElevation = 18.dp) { Column(Modifier.fillMaxSize().padding(18.dp)) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("ASTRA", color = Color.White, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); IconButton({ drawerOpen = false }) { Text("×", color = Color.White) } }; Text("Siri-style red interface • blue voice wave", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp)); listOf("Chat" to 0, "Chats" to 3, "AI & Models" to 1, "Voice & Listening" to 1, "Permissions" to 2, "Files & Workspace" to 2, "LAN / APIs" to 1, "Privacy" to 1).forEach { (label, target) -> NavigationDrawerItem(label = { Text(label) }, selected = page == target, onClick = { page = target; drawerOpen = false }, modifier = Modifier.padding(vertical = 2.dp)) }; Spacer(Modifier.weight(1f)); Text("Android security and permission controls remain user-controlled.", color = Color.Gray, style = MaterialTheme.typography.bodySmall) } }
        }
        if (drawerOpen) Spacer(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f)))
    }
    if (voices) AlertDialog(onDismissRequest = { voices = false }, title = { Text("Choose TTS voice") }, text = { LazyColumn { items(a.voices().take(80)) { v -> TextButton({ a.selectVoice(v.name); voices = false }, Modifier.fillMaxWidth()) { Text("${v.name} • ${v.locale}") } } } }, confirmButton = { TextButton({ voices = false }) { Text("Close") } })
    renameTarget?.let { target -> AlertDialog(onDismissRequest = { renameTarget = null }, title = { Text("Rename chat") }, text = { OutlinedTextField(renameText, { renameText = it }, Modifier.fillMaxWidth(), singleLine = true) }, confirmButton = { TextButton({ runtime.renameChat(target.id, renameText); renameTarget = null }) { Text("Save") } }, dismissButton = { TextButton({ renameTarget = null }) { Text("Cancel") } }) }
}
