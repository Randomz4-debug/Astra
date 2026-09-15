package com.astra.ai

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Advanced controls required by Astra's full assistant specification. */
class AstraAdvancedSettingsActivity: ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{AdvancedSettings(this)}}
}

@Composable private fun AdvancedSettings(a:AstraAdvancedSettingsActivity){
 val prefs=a.getSharedPreferences("astra_runtime",0); val scope=rememberCoroutineScope(); val vault=remember{AstraCredentialVault(a)}
 var localOnly by remember{mutableStateOf(prefs.getBoolean("local_only",false))}; var live by remember{mutableStateOf(LiveAgentSession.isRunning())}; var background by remember{mutableStateOf(prefs.getBoolean("always_listen",false))}; var screenOff by remember{mutableStateOf(prefs.getBoolean("screen_off_mode",false))}; var wake by remember{mutableStateOf(prefs.getString("wake_word","astra").orEmpty())}; var timeout by remember{mutableStateOf(prefs.getLong("conversation_timeout_ms",30000L).toString())}; var status by remember{mutableStateOf("")}; var name by remember{mutableStateOf(prefs.getString("assistant_name","Astra").orEmpty())}
 var credName by remember{mutableStateOf("")}; var credType by remember{mutableStateOf("API Key")}; var credValue by remember{mutableStateOf("")}; var credentials by remember{mutableStateOf(vault.list())}
 LazyColumn(Modifier.fillMaxSize().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{Text("Astra Advanced Settings",style=MaterialTheme.typography.headlineMedium);Text("Assistant, privacy, live assistance and reusable connections",color=MaterialTheme.colorScheme.onSurfaceVariant)}
  item{Text("ASSISTANT",style=MaterialTheme.typography.titleLarge);OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text("Assistant name")});Button({prefs.edit().putString("assistant_name",name.trim().ifBlank{"Astra"}).apply();status="Assistant name saved."},Modifier.fillMaxWidth()){Text("Save name")};OutlinedTextField(wake,{wake=it},Modifier.fillMaxWidth(),label={Text("Wake word")});Button({prefs.edit().putString("wake_word",wake.trim()).apply();status="Wake word saved."},Modifier.fillMaxWidth()){Text("Save wake word")}}
  item{Text("VOICE / BACKGROUND",style=MaterialTheme.typography.titleLarge);SwitchRow("Background listening",background){background=it;prefs.edit().putBoolean("always_listen",it).apply()};SwitchRow("Screen-off mode",screenOff){screenOff=it;prefs.edit().putBoolean("screen_off_mode",it).apply()};OutlinedTextField(timeout,{timeout=it.filter(Char::isDigit)},Modifier.fillMaxWidth(),label={Text("Conversation timeout (ms)")});Button({prefs.edit().putLong("conversation_timeout_ms",timeout.toLongOrNull()?:30000L).apply();status="Voice settings saved."},Modifier.fillMaxWidth()){Text("Save voice settings")}}
  item{Text("LIVE ASSIST MODE",style=MaterialTheme.typography.titleLarge);Text("Stay with me while I use WhatsApp, Telegram, Instagram or another app. Astra uses only permitted notifications and accessibility screen state. It never intercepts third-party call audio.",color=MaterialTheme.colorScheme.onSurfaceVariant);Button({if(!live){LiveAgentSession(a).start();live=true;status="Live Assist started."}else{LiveAgentSession.current()?.stop();live=false;status="Live Assist stopped."}},Modifier.fillMaxWidth()){Text(if(live)"Stop Live Assist" else "Start Live Assist")};OutlinedButton({a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))},Modifier.fillMaxWidth()){Text("Configure Accessibility")};OutlinedButton({a.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))},Modifier.fillMaxWidth()){Text("Configure Notification Access")}}
  item{Text("PRIVACY",style=MaterialTheme.typography.titleLarge);SwitchRow("LOCAL ONLY MODE",localOnly){localOnly=it;prefs.edit().putBoolean("local_only",it).apply();if(it)prefs.edit().putString("ai_mode","offline").apply()};Text(if(localOnly)"Cloud AI, network speech, cloud vision, cloud storage, analytics and telemetry are disabled by Astra's policy layer." else "Online providers may be used according to your AI mode and provider configuration.",color=MaterialTheme.colorScheme.onSurfaceVariant);Button({prefs.edit().putBoolean("allow_analytics",false).putBoolean("allow_telemetry",false).apply();status="Privacy defaults tightened."},Modifier.fillMaxWidth()){Text("Disable analytics & telemetry")}}
  item{Text("CREDENTIALS / CONNECTIONS",style=MaterialTheme.typography.titleLarge);Text("n8n-style reusable credentials. Secret values are encrypted using Android Keystore and are never shown in this list.",color=MaterialTheme.colorScheme.onSurfaceVariant);OutlinedTextField(credName,{credName=it},Modifier.fillMaxWidth(),label={Text("Connection name")});OutlinedTextField(credType,{credType=it},Modifier.fillMaxWidth(),label={Text("Type (Instagram / WhatsApp / Telegram / OAuth / API Key…)")});OutlinedTextField(credValue,{credValue=it},Modifier.fillMaxWidth(),label={Text("Secret / token")});Button({if(credName.isNotBlank()&&credValue.isNotBlank()){vault.save(credName,credType,mapOf("secret" to credValue));credName="";credValue="";credentials=vault.list();status="Credential saved securely."}},Modifier.fillMaxWidth()){Text("Save encrypted credential")};credentials.forEach{c->Card(Modifier.fillMaxWidth()){Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){Column(Modifier.weight(1f)){Text(c.name);Text(c.type,color=MaterialTheme.colorScheme.onSurfaceVariant)};TextButton({vault.delete(c.id);credentials=vault.list()}){Text("Delete")}}}}}
  item{if(status.isNotBlank())Text(status,color=MaterialTheme.colorScheme.primary);Button({a.finish()},Modifier.fillMaxWidth()){Text("Done")}}
 }
}
@Composable private fun SwitchRow(title:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(title);Switch(value,onChange)}}
