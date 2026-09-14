package com.astra.ai

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    private lateinit var voice: MultilingualVoiceController
    private lateinit var capture: ScreenCaptureManager
    private lateinit var camera: AstraCameraManager
    private lateinit var lan: AstraLanServer
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val files = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) lifecycleScope.launch {
            val n = uris.mapNotNull { AstraFileIntake(this@MainActivity).importUri(it) }.size
            Toast.makeText(this@MainActivity, "Imported $n file(s)", Toast.LENGTH_SHORT).show()
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
        lan = AstraLanServer(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { AstraScreen(this) } }
    }

    fun requestAssistantRole() {
        if (android.os.Build.VERSION.SDK_INT < 29) { Toast.makeText(this, "Assistant role requires Android 10+", Toast.LENGTH_SHORT).show(); return }
        val rm = getSystemService(RoleManager::class.java)
        if (!rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) { Toast.makeText(this, "This device does not expose the Assistant role.", Toast.LENGTH_LONG).show(); return }
        if (rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) { Toast.makeText(this, "Astra is already the default assistant.", Toast.LENGTH_SHORT).show(); return }
        runCatching { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 901) }
            .onFailure { Toast.makeText(this, "Could not open Assistant role settings: ${it.message}", Toast.LENGTH_LONG).show() }
    }

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
            lifecycleScope.launch { val t = LocalOcrEngine().read(bmp); bmp.recycle(); cb(t.ifBlank { "No readable text found." }) }
        }
    }
    fun cam(cb: (String) -> Unit) {
        camera.bind(this) { ok ->
            if (!ok) return@bind cb("Camera permission unavailable.")
            camera.takePhoto { f ->
                if (f == null) return@takePhoto cb("Photo capture failed.")
                lifecycleScope.launch { val b = android.graphics.BitmapFactory.decodeFile(f.absolutePath); val t = if (b != null) LocalOcrEngine().read(b) else ""; b?.recycle(); cb(t.ifBlank { "Photo saved; no readable text found." }) }
            }
        }
    }
    fun pick() = runCatching { files.launch(arrayOf("*/*")) }
    fun accessibility() = runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    fun notifications() = runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    fun battery() = runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    fun overlay() = runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }

    fun lanToggle(on: Boolean, callback: (String) -> Unit) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (on) {
                    if (!lan.start(8765)) "Could not start LAN server. Another service may already use port 8765."
                    else "LAN UI: http://${ip()}:8765"
                } else {
                    lan.stop()
                    "LAN server stopped."
                }
            }
            if (!isFinishing && !isDestroyed) callback(result)
        }
    }

    private fun ip(): String = runCatching {
        NetworkInterface.getNetworkInterfaces()?.asSequence()?.flatMap { it.inetAddresses.asSequence() }
            ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(":") == false }?.hostAddress ?: "<phone-ip>"
    }.getOrDefault("<phone-ip>")

    override fun onDestroy() {
        if (::lan.isInitialized) lan.stop()
        if (::capture.isInitialized) capture.release()
        if (::voice.isInitialized) voice.release()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AstraScreen(a: MainActivity, vm: AstraViewModel = viewModel(factory = AstraViewModel.factory(a))) {
    val ui by vm.ui.collectAsState()
    val rms by VoiceTelemetry.rms.collectAsState()
    val live by VoiceTelemetry.listening.collectAsState()
    val runtime = remember { AstraAgentRuntime(a) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val chatsStore = remember { AstraChatStore(a) }
    var text by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf("auto") }
    var endpoint by remember { mutableStateOf("http://127.0.0.1:11434") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("auto") }
    var always by remember { mutableStateOf(false) }
    var lanOn by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(0) }
    var voices by remember { mutableStateOf(false) }
    var chatRefresh by remember { mutableIntStateOf(0) }
    var selectedChatId by remember { mutableStateOf(runtime.currentChatId()) }
    var renameTarget by remember { mutableStateOf<AstraChatStore.Chat?>(null) }
    var renameText by remember { mutableStateOf("") }

    val drawerItems = listOf("Chat" to "💬", "Chats" to "🗂", "AI & Models" to "🧠", "Voice & Listening" to "🎙", "Permissions" to "🔐", "Files & Workspace" to "📁", "LAN / APIs" to "🌐", "Privacy" to "🛡")
    val refreshChats: () -> Unit = { chatRefresh++ }

    LaunchedEffect(ui.response) { if (ui.response.isNotBlank()) refreshChats() }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet {
                Text("ASTRA", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(20.dp))
                drawerItems.forEachIndexed { i, item ->
                    val target = when (i) { 0 -> 0; 1 -> 3; 2, 3, 6, 7 -> 1; else -> 2 }
                    NavigationDrawerItem(
                        label = { Text(item.first) }, icon = { Text(item.second) }, selected = page == target,
                        onClick = {
                            page = target
                            scope.launch { runCatching { drawer.close() } }
                        }, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("Android permissions remain user-controlled.", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(16.dp))
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Astra") },
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch {
                                runCatching { if (drawer.isClosed) drawer.open() else drawer.close() }
                            }
                        }) { Text("☰") }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = page == 0, onClick = { page = 0 }, icon = { Text("💬") }, label = { Text("Chat") })
                    NavigationBarItem(selected = page == 1, onClick = { page = 1 }, icon = { Text("⚙") }, label = { Text("Settings") })
                    NavigationBarItem(selected = page == 2, onClick = { page = 2 }, icon = { Text("🔑") }, label = { Text("Access") })
                    NavigationBarItem(selected = page == 3, onClick = { page = 3 }, icon = { Text("🗂") }, label = { Text("Chats") })
                }
            }
        ) { p ->
            Column(Modifier.padding(p).fillMaxSize().background(Color(0xFF08090D)).verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(110.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .16f)), contentAlignment = Alignment.Center) { Text("A", style = MaterialTheme.typography.displayLarge) }
                Text("ASTRA", style = MaterialTheme.typography.headlineMedium)
                Text(if (live) "LISTENING • LIVE" else ui.state.name, color = Color.LightGray)
                Row(Modifier.fillMaxWidth().height(45.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(28) { i -> Box(Modifier.width(5.dp).height((4 + rms.coerceIn(0f, 10f) * (1 + i % 6)).coerceAtMost(38f).dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) }
                }
                Text("Microphone level ${rms.toInt()} dB", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

                if (page == 0) {
                    Text("Current chat", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                    if (ui.transcript.isNotBlank()) Text("You: ${ui.transcript}", Modifier.fillMaxWidth(), color = Color.LightGray)
                    if (ui.response.isNotBlank()) Text("Astra: ${ui.response}", Modifier.fillMaxWidth())
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Message Astra") })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ if (text.isNotBlank()) { vm.ask(text); text = "" } }, Modifier.weight(1f)) { Text("Send") }
                        OutlinedButton({ if (live) a.stopListening() else a.listen(lang, { text = it; vm.ask(it) }, {}) }, Modifier.weight(1f)) { Text(if (live) "Stop" else "Listen") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ a.pick() }, Modifier.weight(1f)) { Text("Attach files") }
                        OutlinedButton({ a.openWebUi() }, Modifier.weight(1f)) { Text("Open Web UI") }
                    }
                }

                Text("AI MODE", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("auto", "online", "offline").forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m; vm.setAiMode(m) }, label = { Text(m.uppercase()) }) }
                }

                if (page == 1) {
                    Text("OLLAMA / LOCAL AI", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("Ollama/LAN base URL") }, singleLine = true)
                    OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model (blank = auto discover)") }, singleLine = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ LocalAiGateway(a).configure(endpoint, model); info = "Ollama saved." }, Modifier.weight(1f)) { Text("Save") }
                        OutlinedButton({ a.lifecycleScope.launch { val x = LocalAiGateway(a).discoverOllamaModels(); info = if (x.isEmpty()) "No Ollama models found." else "Found: ${x.joinToString()}" } }, Modifier.weight(1f)) { Text("Discover") }
                    }
                    Text("VOICE & LISTENING", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(lang, { lang = it }, Modifier.fillMaxWidth(), label = { Text("Language / locale (auto supported)") }, singleLine = true)
                    Button({ voices = true }, Modifier.fillMaxWidth()) { Text("Choose installed voice") }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Always listen and auto-submit speech")
                        Switch(always, { always = it; vm.setAlwaysListen(it) })
                    }
                    Text("Always-listening requires explicit enablement and uses a visible Android microphone foreground service.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text("LAN / API", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (lanOn) "LAN Web UI ON" else "LAN Web UI OFF")
                        Switch(lanOn, { newValue ->
                            lanOn = newValue
                            info = if (newValue) "Starting LAN Web UI…" else "Stopping LAN Web UI…"
                            a.lanToggle(newValue) { result -> info = result; lanOn = newValue && result.startsWith("LAN UI:") }
                        })
                    }
                    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Optional OpenAI API key") }, singleLine = true)
                    Button({ vm.setOpenAiApiKey(key); info = "Cloud key saved with Android Keystore." }, Modifier.fillMaxWidth()) { Text("Save API Key") }
                    Text("Memory", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                    Text("Astra memory and chat history are stored locally. Chat history keeps up to one year for AI context and can be deleted from Chats.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }

                if (page == 2) {
                    Text("LOCKSCREEN ASSISTANT", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                    Button({ a.requestAssistantRole() }, Modifier.fillMaxWidth()) { Text("Set Astra as Default Assistant") }
                    Text("Once selected as the system assistant, Android can invoke Astra from the lock screen/keyguard. The OS still controls secure actions.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text("ACCESS & FILES", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedButton({ a.screenAccess() }, Modifier.weight(1f)) { Text("Screen Access") }; OutlinedButton({ a.readScreen { info = it } }, Modifier.weight(1f)) { Text("Read Screen") } }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedButton({ a.accessibility() }, Modifier.weight(1f)) { Text("Accessibility") }; OutlinedButton({ a.notifications() }, Modifier.weight(1f)) { Text("Notifications") } }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedButton({ a.cam { info = it } }, Modifier.weight(1f)) { Text("Camera + OCR") }; OutlinedButton({ a.pick() }, Modifier.weight(1f)) { Text("Import Files") } }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedButton({ a.battery() }, Modifier.weight(1f)) { Text("Battery") }; OutlinedButton({ a.overlay() }, Modifier.weight(1f)) { Text("Overlay") } }
                }

                if (page == 3) {
                    Text("CHAT HISTORY", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button({ selectedChatId = runtime.newChat(); refreshChats() }, Modifier.weight(1f)) { Text("New chat") }
                        OutlinedButton({ runtime.clearAllChats(); selectedChatId = runtime.currentChatId(); refreshChats(); info = "All chats deleted." }, Modifier.weight(1f)) { Text("Delete all") }
                    }
                    val chatList = chatsStore.listChats().also { chatRefresh }
                    chatList.forEach { chat ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(chat.title, maxLines = 1)
                                    Text(java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(chat.updatedAt)), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                TextButton({ selectedChatId = chat.id; runtime.selectChat(chat.id); refreshChats() }) { Text("Open") }
                                TextButton({ renameTarget = chat; renameText = chat.title }) { Text("Rename") }
                                TextButton({ runtime.deleteChat(chat.id); if (selectedChatId == chat.id) selectedChatId = runtime.currentChatId(); refreshChats() }) { Text("Delete") }
                            }
                        }
                    }
                    Text("MESSAGES", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                    val selectedMessages = chatsStore.messages(selectedChatId, 0L, 500)
                    if (selectedMessages.isEmpty()) Text("No messages in this chat yet.", color = Color.Gray, modifier = Modifier.fillMaxWidth())
                    selectedMessages.takeLast(100).forEach { m ->
                        Text("${if (m.role == "user") "You" else "Astra"}: ${m.text}", Modifier.fillMaxWidth().padding(vertical = 4.dp), color = if (m.role == "user") Color.LightGray else Color.White)
                    }
                }

                if (info.isNotBlank()) Card(Modifier.fillMaxWidth()) { Text(info, Modifier.padding(12.dp)) }
                Spacer(Modifier.height(18.dp))
                Text("Astra uses local/LAN/cloud AI and user-authorized Android tools. Android security, permission, lockscreen and call restrictions still apply.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }

    if (voices) AlertDialog(onDismissRequest = { voices = false }, title = { Text("Choose TTS voice") }, text = { LazyColumn { items(a.voices().take(80)) { v -> TextButton({ a.selectVoice(v.name); voices = false }, Modifier.fillMaxWidth()) { Text("${v.name} • ${v.locale}") } } } }, confirmButton = { TextButton({ voices = false }) { Text("Close") } })

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(renameText, { renameText = it }, Modifier.fillMaxWidth(), singleLine = true) },
            confirmButton = { TextButton({ runtime.renameChat(target.id, renameText); renameTarget = null; refreshChats() }) { Text("Save") } },
            dismissButton = { TextButton({ renameTarget = null }) { Text("Cancel") } }
        )
    }
}
