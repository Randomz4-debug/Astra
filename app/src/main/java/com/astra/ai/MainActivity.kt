package com.astra.ai

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private lateinit var voice: MultilingualVoiceController
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS))
        voice = MultilingualVoiceController(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { AstraScreen(this) } }
    }
    fun listen(language: String, result: (String) -> Unit, state: (Boolean) -> Unit) = voice.listen(language, result, state)
    fun stopListening() = voice.stop()
    fun speak(text: String, language: String) = voice.speak(text, language)
    override fun onDestroy() { voice.release(); super.onDestroy() }
}

@Composable
fun AstraScreen(activity: MainActivity, vm: AstraViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    var input by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("auto") }
    var listening by remember { mutableStateOf(false) }
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label="pulse").animateFloat(1f,1.1f,androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800),androidx.compose.animation.core.RepeatMode.Reverse),label="scale")
    LaunchedEffect(ui.response) { if (ui.response.isNotBlank()) activity.speak(ui.response, language) }
    Column(Modifier.fillMaxSize().background(Color(0xFF08090D)).padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Spacer(Modifier.height(30.dp)); Text("ASTRA",style=MaterialTheme.typography.displaySmall); Text(if(listening) "LISTENING" else ui.state.name,color=Color.LightGray)
        Spacer(Modifier.weight(1f)); Box(Modifier.size(170.dp).scale(pulse.value).background(MaterialTheme.colorScheme.primary.copy(alpha=.16f),CircleShape),contentAlignment=Alignment.Center){Text("A",style=MaterialTheme.typography.displayLarge)}
        Spacer(Modifier.height(18.dp)); Text(ui.transcript,color=Color.LightGray); Text(ui.response)
        Spacer(Modifier.height(16.dp)); OutlinedTextField(language,{language=it},Modifier.fillMaxWidth(),label={Text("Language: auto / en-US / hi-IN / etc.")},singleLine=true)
        Spacer(Modifier.height(8.dp)); OutlinedTextField(input,{input=it},Modifier.fillMaxWidth(),label={Text("Type or dictate in any supported language")})
        Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button({if(listening)activity.stopListening() else activity.listen(language,{text->input=text;vm.ask(text)},{listening=it})},Modifier.weight(1f)){Text(if(listening)"Stop" else "Listen")}
            Button({if(input.isNotBlank()){vm.ask(input);input=""}},Modifier.weight(1f)){Text("Send & Speak")}
        }
        Spacer(Modifier.height(8.dp)); Text("Auto mode uses Android language detection when supported. BCP-47 tags can force a language. Text input accepts Unicode scripts.",style=MaterialTheme.typography.bodySmall,color=Color.Gray)
        Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Row(verticalAlignment=Alignment.CenterVertically){Switch(ui.localOnly,vm::setLocalOnly);Text("Local only")};Row(verticalAlignment=Alignment.CenterVertically){Switch(ui.background,vm::setBackground);Text("Background")}}
    }
}
