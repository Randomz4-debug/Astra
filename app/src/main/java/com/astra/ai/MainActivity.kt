package com.astra.ai

import android.Manifest
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
 private lateinit var voice:MultilingualVoiceController;private lateinit var screenCapture:ScreenCaptureManager;private lateinit var camera:AstraCameraManager;private lateinit var lanServer:AstraLanServer
 private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){}
 private val picker=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){u->if(u.isNotEmpty())lifecycleScope.launch{val n=u.mapNotNull{AstraFileIntake(this@MainActivity).import(it)}.size;Toast.makeText(this@MainActivity,"Imported $n file(s)",Toast.LENGTH_SHORT).show()}}
 private val projection=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){r->val ok=runCatching{screenCapture.attachResult(r.resultCode,r.data)}.getOrDefault(false);Toast.makeText(this,if(ok)"Screen access enabled" else"Screen access not granted",Toast.LENGTH_SHORT).show()}
 override fun onCreate(b:Bundle?){super.onCreate(b);permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA,Manifest.permission.POST_NOTIFICATIONS));voice=MultilingualVoiceController(this);screenCapture=ScreenCaptureManager(this);camera=AstraCameraManager(this);lanServer=AstraLanServer(this);setContent{MaterialTheme(colorScheme=darkColorScheme()){AstraScreen(this)}}}
 fun listen(l:String,r:(String)->Unit,s:(Boolean)->Unit)=voice.listen(l,r,s);fun alwaysListen(l:String,r:(String)->Unit)=voice.startAlwaysListening(l,r);fun stopListening()=voice.stop();fun speak(t:String,l:String)=voice.speak(t,l);fun voices()=voice.availableVoices();fun selectVoice(n:String)=voice.setVoice(n)
 fun startBackground(){if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(Intent(this,AstraForegroundService::class.java))else startService(Intent(this,AstraForegroundService::class.java))};fun stopBackground()=stopService(Intent(this,AstraForegroundService::class.java));fun openWebUi()=startActivity(Intent(this,AstraWebActivity::class.java))
 fun requestScreenAccess(){runCatching{projection.launch(screenCapture.permissionIntent())}.onFailure{Toast.makeText(this,"Could not open screen sharing: ${it.message}",Toast.LENGTH_LONG).show()}}
 fun readScreen(cb:(String)->Unit){if(!screenCapture.hasPermission())return cb("Grant Screen Access first.");screenCapture.capture{b->if(b==null)return@capture cb("Screen capture failed. Grant access again.");lifecycleScope.launch{val t=LocalOcrEngine().read(b);b.recycle();cb(t.ifBlank{"No readable text found."})}}}
 fun cameraOcr(cb:(String)->Unit){camera.bind(this){ok->if(!ok)return@bind cb("Camera permission unavailable.");camera.takePhoto{f->if(f==null)return@takePhoto cb("Photo capture failed.");lifecycleScope.launch{val b=android.graphics.BitmapFactory.decodeFile(f.absolutePath);val t=if(b!=null)LocalOcrEngine().read(b)else"";b?.recycle();cb(t.ifBlank{"Photo saved; no readable text found."})}}}}
 fun pickFiles()=picker.launch(arrayOf("*/*"));fun openAccessibility()=startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));fun openNotifications()=startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));fun openBattery()=startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));fun openOverlay()=startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
 fun toggleLan(on:Boolean):String{if(on)return if(lanServer.start(8765))"LAN UI: http://${lanIp()}:8765" else"Could not start LAN server.";lanServer.stop();return"LAN server stopped."};private fun lanIp()=runCatching{NetworkInterface.getNetworkInterfaces().asSequence().flatMap{it.inetAddresses.asSequence()}.firstOrNull{!it.isLoopbackAddress&&it.hostAddress?.contains(":")==false}?.hostAddress?:"<phone-ip>"}.getOrDefault("<phone-ip>")
 override fun onDestroy(){if(::lanServer.isInitialized)lanServer.stop();if(::screenCapture.isInitialized)screenCapture.release();if(::voice.isInitialized)voice.release();super.onDestroy()}
}

@Composable
fun AstraScreen(a:MainActivity,vm:AstraViewModel=viewModel(factory=AstraViewModel.factory(a))){
 val ui by vm.ui.collectAsState();val rms by VoiceTelemetry.rms.collectAsState();val live by VoiceTelemetry.listening.collectAsState();var input by remember{mutableStateOf("")};var lang by remember{mutableStateOf("auto")};var endpoint by remember{mutableStateOf("http://127.0.0.1:11434")};var model by remember{mutableStateOf("")};var key by remember{mutableStateOf("")};var mode by remember{mutableStateOf("auto")};var always by remember{mutableStateOf(false)};var info by remember{mutableStateOf("")};var lan by remember{mutableStateOf(false)};var page by remember{mutableStateOf(0)};var drawer=rememberDrawerState(DrawerValue.Closed);var showVoices by remember{mutableStateOf(false)}
 ModalNavigationDrawer(drawerState=drawer,drawerContent={ModalDrawerSheet{Text("ASTRA",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(20.dp));listOf("Chat","AI & Models","Voice & Listening","Permissions","Files & Workspace","LAN / APIs","Privacy").forEachIndexed{i,s->NavigationDrawerItem(label={Text(s)},selected=page==i,onClick={page=if(i==0)0 else 1;a.lifecycleScope.launch{drawer.close()}},modifier=Modifier.padding(horizontal=10.dp),icon={Text(listOf("💬","🧠","🎙","🔐","📁","🌐","🛡")[i])})}}}){
  Scaffold(topBar={TopAppBar(title={Text("Astra")},navigationIcon={IconButton({a.lifecycleScope.launch{drawer.open()}}){Text("☰")}})},bottomBar={NavigationBar{NavigationBarItem(selected=page==0,onClick={page=0},icon={Text("💬")},label={Text("Chat")});NavigationBarItem(selected=page==1,onClick={page=1},icon={Text("⚙")},label={Text("Settings")});NavigationBarItem(selected=page==2,onClick={page=2},icon={Text("🔑")},label={Text("Access")})}}){p->Column(Modifier.padding(p).fillMaxSize().background(Color(0xFF08090D)).verticalScroll(rememberScrollState()).padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally){
   Box(Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha=.16f)),contentAlignment=Alignment.Center){Text("A",style=MaterialTheme.typography.displayLarge)};Text("ASTRA",style=MaterialTheme.typography.headlineMedium);Text(if(live)"LISTENING • LIVE" else ui.state.name,color=Color.LightGray)
   Row(Modifier.fillMaxWidth().height(45.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)){repeat(28){i->Box(Modifier.width(5.dp).height((4+(rms.coerceIn(0f,10f)*(1+i%6))).coerceAtMost(38f).dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))}};Text("Microphone level ${rms.toInt()} dB",style=MaterialTheme.typography.bodySmall,color=Color.Gray)
   if(page==0){if(ui.transcript.isNotBlank())Text("You: ${ui.transcript}",Modifier.fillMaxWidth(),color=Color.LightGray);if(ui.response.isNotBlank())Text("Astra: ${ui.response}",Modifier.fillMaxWidth());OutlinedTextField(input,{input=it},Modifier.fillMaxWidth(),label={Text("Message Astra")});Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({if(input.isNotBlank()){vm.ask(input);input=""}},Modifier.weight(1f)){Text("Send")};OutlinedButton({if(live)a.stopListening()else a.listen(lang,{input=it;vm.ask(it)},{})},Modifier.weight(1f)){Text(if(live)"Stop" else"Listen")}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({a.pickFiles()},Modifier.weight(1f)){Text("Attach files")};OutlinedButton({a.openWebUi()},Modifier.weight(1f)){Text("Open Web UI")}}}
   Text("AI MODE",style=MaterialTheme.typography.titleMedium,modifier=Modifier.fillMaxWidth());Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("auto","online","offline").forEach{m->FilterChip(selected=mode==m,onClick={mode=m;vm.setAiMode(m)},label={Text(m.uppercase())})}}
   if(page!=0){Text("AI / OLLAMA",style=MaterialTheme.typography.titleMedium,modifier=Modifier.fillMaxWidth());OutlinedTextField(endpoint,{endpoint=it},Modifier.fillMaxWidth(),label={Text("Ollama/LAN base URL")},singleLine=true);OutlinedTextField(model,{model=it},Modifier.fillMaxWidth(),label={Text("Model (blank = auto)")},singleLine=true);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({LocalAiGateway(a).configure(endpoint,model);info="Ollama settings saved."},Modifier.weight(1f)){Text("Save")};OutlinedButton({a.lifecycleScope.launch{val x=LocalAiGateway(a).discoverOllamaModels();info=if(x.isEmpty())"No Ollama models found." else"Found: ${x.joinToString()}"}},Modifier.weight(1f)){Text("Discover")}}
    Text("VOICE & LISTENING",style=MaterialTheme.typography.titleMedium,modifier=Modifier.fillMaxWidth());OutlinedTextField(lang,{lang=it},Modifier.fillMaxWidth(),label={Text("Language / locale")},singleLine=true);Button({showVoices=true},Modifier.fillMaxWidth()){Text("Choose installed voice")};Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text("Always listen and auto-submit speech");Switch(always,{always=it;vm.setAlwaysListen(it);if(it){vm.setBackground(true);a.startBackground()}else a.stopListening()})};Text("Always-listening is user-controlled and uses Android's visible microphone foreground service.",style=MaterialTheme.typography.bodySmall,color=Color.Gray)
    Text("ACCESS & FILES",style=MaterialTheme.typography.titleMedium,modifier=Modifier.fillMaxWidth());Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton({a.requestScreenAccess()},Modifier.weight(1f)){Text("Screen Access")};OutlinedButton({a.readScreen{info=it}},Modifier.weight(1f)){Text("Read Screen")}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton({a.openAccessibility()},Modifier.weight(1f)){Text("Accessibility")};OutlinedButton({a.openNotifications()},Modifier.weight(1f)){Text("Notifications")}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton({a.cameraOcr{info=it}},Modifier.weight(1f)){Text("Camera + OCR")};OutlinedButton({a.pickFiles()},Modifier.weight(1f)){Text("Import Files")}}
    Text("LAN / API",style=MaterialTheme.typography.titleMedium,modifier=Modifier.fillMaxWidth());Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text(if(lan)"LAN Web UI ON" else"LAN Web UI OFF");Switch(lan,{lan=it;info=a.toggleLan(it)})};Text("Same-Wi-Fi devices can use PHONE-IP:8765. APIs: /api/models /api/providers /api/chat /api/provider/chat /api/parallel",style=MaterialTheme.typography.bodySmall,color=Color.Gray)
    Text("CLOUD / PRIVACY",style=MaterialTheme.typography.titleMedium,modifier=Modifier.fillMaxWidth());OutlinedTextField(key,{key=it},Modifier.fillMaxWidth(),label={Text("Optional OpenAI API key")},singleLine=true);Button({vm.setOpenAiApiKey(key);info="Cloud key saved with Android Keystore."},Modifier.fillMaxWidth()){Text("Save API Key")};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton({a.openBattery()},Modifier.weight(1f)){Text("Battery")};OutlinedButton({a.openOverlay()},Modifier.weight(1f)){Text("Overlay")}};if(info.isNotBlank())Card(Modifier.fillMaxWidth()){Text(info,Modifier.padding(12.dp))}
   }
   Spacer(Modifier.height(18.dp));Text("Astra can use local/LAN/cloud AI, Android-authorized tools, files and browser-connected services. Platform security, user consent and app permissions still apply.",style=MaterialTheme.typography.bodySmall,color=Color.Gray)
  }}
 }
 if(showVoices)AlertDialog(onDismissRequest={showVoices=false},title={Text("Choose installed TTS voice")},text={LazyColumn{items(a.voices().take(80)){v->TextButton({a.selectVoice(v.name);showVoices=false},Modifier.fillMaxWidth()){Text("${v.name} • ${v.locale}")}}}},confirmButton={TextButton({showVoices=false}){Text("Close")}})
}
