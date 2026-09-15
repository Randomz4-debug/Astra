package com.astra.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class AstraConnectedAppsActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state); setContent { MaterialTheme { ConnectedApps(this) } } }
}

@Composable private fun ConnectedApps(a:AstraConnectedAppsActivity) {
    val manager=remember{AstraConnectionManager(a)}
    var records by remember{mutableStateOf(manager.records())}
    var installed by remember{mutableStateOf(emptyList<AstraConnectionManager.Integration>())}
    var query by remember{mutableStateOf("")}
    var selected by remember{mutableStateOf<AstraConnectionManager.Integration?>(null)}
    var custom by remember{mutableStateOf(false)}
    var name by remember{mutableStateOf("")}; var url by remember{mutableStateOf("")}; var auth by remember{mutableStateOf("none")}
    LaunchedEffect(Unit){installed=manager.discoverInstalledApps()}
    Scaffold(topBar={TopAppBar(title={Text("Connected Apps")})}){p->LazyColumn(Modifier.fillMaxSize().padding(p).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text("One central connection manager. Astra uses official authentication, Android permissions, intents, or your own API. It never asks for passwords or OTPs.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item{OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("Search Apps")})}
        item{Button({custom=true},Modifier.fillMaxWidth()){Text("+ Add Custom Integration")}}
        item{Text("CONNECTED APPS",style=MaterialTheme.typography.titleLarge)}
        items(records.filter{it.integration.name.contains(query,true)}){r->ElevatedCard({selected=r.integration},Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(r.integration.name);Text(if(r.state==AstraConnectionManager.State.CONNECTED)"● Connected" else "○ Not connected",color=MaterialTheme.colorScheme.onSurfaceVariant);if(r.accounts.isNotEmpty())Text("Accounts: "+r.accounts.joinToString{it.accountName})}}}
        item{Text("INSTALLED APPS",style=MaterialTheme.typography.titleLarge)}
        items(installed.filter{it.name.contains(query,true)}.take(40)){i->OutlinedButton({selected=i},Modifier.fillMaxWidth()){Text("${i.name} • ${i.packageName ?: "service"}")}}
        item{OutlinedButton({a.finish()},Modifier.fillMaxWidth()){Text("Done")}}
    }}
    selected?.let{i->AlertDialog(onDismissRequest={selected=null},title={Text("${i.name} Connection")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text("Available connection mechanisms:");i.methods.forEach{Text("• ${it.name.replace('_',' ')}")};if(i.capabilities.isNotEmpty())Text("Declared capabilities: ${i.capabilities.joinToString()}")}},confirmButton={Button({if(manager.isInstalled(i))manager.openApp(i) else manager.openWebsite(i);selected=null}){Text("Continue")}},dismissButton={TextButton({selected=null}){Text("Cancel")}})}
    if(custom)AlertDialog(onDismissRequest={custom=false},title={Text("Custom Integration")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text("Name")});OutlinedTextField(url,{url=it},Modifier.fillMaxWidth(),label={Text("Base URL")});Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("none","api_key","oauth","websocket").forEach{FilterChip(auth==it,{auth=it},label={Text(it)})}}}},confirmButton={Button({if(name.isNotBlank()&&url.isNotBlank()){manager.addCustomIntegration(name.trim(),url.trim(),auth);records=manager.records();custom=false}},enabled=name.isNotBlank()&&url.isNotBlank()){Text("Save")}},dismissButton={TextButton({custom=false}){Text("Cancel")}})
}
