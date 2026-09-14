package com.astra.ai

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class AstraChatActivity : ComponentActivity() {
    private lateinit var voice: MultilingualVoiceController
    private lateinit var tts: TextToSpeech
    private val selectedFiles = mutableStateListOf<AstraFileIntake.Attachment>()
    private val fileStatus = mutableStateMapOf<String, String>()
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importFiles(uris)
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { rememberVisual(it) } }
    private val assistantRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        voice = MultilingualVoiceController(this)
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = Locale.getDefault() }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 91)
        setContent { AstraChatTheme { AstraChatScreen() } }
    }

    private fun importFiles(uris: List<Uri>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val manager = AstraAttachmentManager(this@AstraChatActivity)
            uris.forEach { uri ->
                val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else "attachment" } ?: "attachment"
                withContext(Dispatchers.Main) { fileStatus[name] = "Reading…" }
                val processed = manager.importAndProcess(uri)
                withContext(Dispatchers.Main) {
                    if (processed != null) {
                        selectedFiles.add(processed.attachment)
                        fileStatus[name] = if (processed.readable) "Ready" else "Stored" 
                    } else fileStatus[name] = "Could not read"
                }
            }
        }
    }

    private fun rememberVisual(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            if (bmp == null) return@launch
            val memory = AstraVisualMemoryStore(this@AstraChatActivity).enroll(bmp, "item", "Saved visual", "Enrolled from user-selected image")
            bmp.recycle()
            withContext(Dispatchers.Main) { Toast.makeText(this@AstraChatActivity, "Saved visual memory: ${memory.name}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun speak(text: String) { if (::tts.isInitialized) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "astra-response") }

    private fun chooseAssistant() {
        if (android.os.Build.VERSION.SDK_INT < 29) { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)); return }
        val role = getSystemService(RoleManager::class.java)
        if (role.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) assistantRole.launch(role.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
    }

    @Composable private fun AstraChatScreen() {
        val runtime = remember { AstraAgentRuntime(this@AstraChatActivity) }
        var input by remember { mutableStateOf(TextFieldValue()) }
        var sending by remember { mutableStateOf(false) }
        var listening by remember { mutableStateOf(false) }
        val messages = remember { mutableStateListOf<ChatLine>() }
        LaunchedEffect(Unit) {
            val id = runtime.currentChatId()
            runtime.messages(id).forEach { messages.add(ChatLine(it.role, it.text)) }
        }
        Scaffold(containerColor = Color(0xFF05060A), topBar = {
            TopAppBar(title = { Column { Text("Astra"); Text(if (sending) "Thinking…" else "Personal AI assistant", style = MaterialTheme.typography.labelSmall, color = Color.Gray) } }, actions = {
                TextButton({ chooseAssistant() }) { Text("Default") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF05060A)))
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (messages.isEmpty()) {
                        Spacer(Modifier.height(80.dp)); Text("What can I help you with?", style = MaterialTheme.typography.headlineSmall, color = Color.White); Text("Ask by text or voice. Attach a file and Astra will read it before answering.", color = Color.Gray)
                    }
                    messages.forEach { line -> MessageBubble(line) }
                }
                ChatInput(input, { input = it }, selectedFiles, fileStatus, sending, listening,
                    onAttach = { picker.launch(arrayOf("*/*")) },
                    onRemove = { selectedFiles.remove(it) },
                    onMic = {
                        if (listening) { voice.stop(); listening = false } else {
                            listening = true
                            voice.listen("auto", { text -> input = TextFieldValue(text); listening = false; send(runtime, text, messages) }, { listening = false })
                        }
                    },
                    onSend = { val text = input.text.trim(); if (text.isNotBlank() && !sending) { input = TextFieldValue(); send(runtime, text, messages) } },
                    onVisual = { imagePicker.launch("image/*") }
                )
            }
        }
    }

    private fun send(runtime: AstraAgentRuntime, text: String, messages: MutableList<ChatLine>) {
        val attachmentNames = selectedFiles.map { it.name }
        val prompt = if (attachmentNames.isEmpty()) text else "$text\n\nUSER ATTACHMENTS: ${attachmentNames.joinToString(", ")}. Use the processed attachment content available in Astra's workspace."
        messages.add(ChatLine("user", if (attachmentNames.isEmpty()) text else "$text\n📎 ${attachmentNames.joinToString(", ")}"))
        val filesToClear = selectedFiles.toList()
        selectedFiles.clear()
        lifecycleScope.launch {
            val answer = runtime.handle(prompt, false)
            messages.add(ChatLine("assistant", answer))
            speak(answer)
            if (filesToClear.isNotEmpty()) Toast.makeText(this@AstraChatActivity, "Attachment(s) remain available in this chat workspace.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() { if (::voice.isInitialized) voice.release(); if (::tts.isInitialized) tts.shutdown(); super.onDestroy() }

    data class ChatLine(val role: String, val text: String)
}

@Composable private fun MessageBubble(line: AstraChatActivity.ChatLine) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.role == "user") Arrangement.End else Arrangement.Start) {
        Surface(color = if (line.role == "user") Color(0xFF20242E) else Color.Transparent, shape = RoundedCornerShape(18.dp)) {
            Text(line.text, color = Color.White, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

@Composable private fun ChatInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    files: List<AstraFileIntake.Attachment>,
    status: Map<String, String>,
    sending: Boolean,
    listening: Boolean,
    onAttach: () -> Unit,
    onRemove: (AstraFileIntake.Attachment) -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onVisual: () -> Unit
) {
    Surface(color = Color(0xFF111318), shape = RoundedCornerShape(28.dp), tonalElevation = 2.dp, modifier = Modifier.padding(10.dp).fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            if (files.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    files.forEach { file ->
                        Surface(color = Color(0xFF20232B), shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.widthIn(max = 180.dp)) { Text(file.name, color = Color.White, maxLines = 1); Text(status[file.name] ?: "Ready", color = if (status[file.name] == "Reading…") Color(0xFF8FB8FF) else Color.Gray, style = MaterialTheme.typography.labelSmall) }
                                TextButton({ onRemove(file) }, contentPadding = PaddingValues(2.dp)) { Text("×") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onAttach) { Text("＋", color = Color.White, style = MaterialTheme.typography.headlineSmall) }
                IconButton(onVisual) { Text("◉", color = Color.White) }
                OutlinedTextField(value, onValueChange, Modifier.weight(1f), placeholder = { Text("Message Astra…") }, maxLines = 6, colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent), shape = RoundedCornerShape(20.dp))
                IconButton(onMic) { Text(if (listening) "■" else "🎙", color = if (listening) Color(0xFFFF2146) else Color.White) }
                IconButton(onSend, enabled = value.text.isNotBlank() && !sending) { Text("➤", color = Color(0xFF61C7FF), style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}

@Composable private fun AstraChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFF2146), secondary = Color(0xFF4C8CFF), background = Color(0xFF05060A), surface = Color(0xFF111318)), content = content)
}
