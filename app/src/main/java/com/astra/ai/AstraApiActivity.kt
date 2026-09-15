package com.astra.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class AstraApiActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFF2146), background = Color(0xFF05060A), surface = Color(0xFF11131B))) {
                ApiManagerScreen(AstraApiHub(this))
            }
        }
    }
}

@Composable
private fun ApiManagerScreen(hub: AstraApiHub) {
    var name by remember { mutableStateOf("") }; var description by remember { mutableStateOf("") }; var baseUrl by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/") }; var method by remember { mutableStateOf("GET") }; var headers by remember { mutableStateOf("") }; var body by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }; var apis by remember { mutableStateOf(hub.all()) }
    fun save() {
        if (name.isBlank() || baseUrl.isBlank()) { status = "Name and base URL are required."; return }
        val parsedHeaders = headers.lines().mapNotNull { line -> val i = line.indexOf(':'); if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim() }.toMap()
        runCatching { hub.save(AstraApiDefinition(name.trim(), description.trim(), baseUrl.trim(), path.trim().ifBlank { "/" }, method.trim().uppercase(), parsedHeaders, body)); apis = hub.all(); status = "Saved $name. Astra can now use this API." }.onFailure { status = "Could not save: ${it.message}" }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("ASTRA API HUB", style = MaterialTheme.typography.headlineMedium); Text("Add as many REST APIs as you want. Astra can use them by name from voice or chat.", color = Color.LightGray)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("API name") }, singleLine = true)
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("What this API is for") }, singleLine = true)
            OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, singleLine = true)
            OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(), label = { Text("Default path") }, singleLine = true)
            OutlinedTextField(method, { method = it }, Modifier.fillMaxWidth(), label = { Text("HTTP method: GET/POST/PUT/PATCH/DELETE") }, singleLine = true)
            OutlinedTextField(headers, { headers = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("Headers, one per line: Authorization: Bearer …") })
            OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("Default request body (optional)") })
            Button({ save() }, Modifier.fillMaxWidth()) { Text("Save API") }; if (status.isNotBlank()) Text(status, color = Color(0xFF69E6A5))
        }
        item { Text("CONFIGURED APIs", style = MaterialTheme.typography.titleLarge) }
        items(apis, key = { it.name }) { api ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF11131B))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(api.name, style = MaterialTheme.typography.titleMedium); Text(api.description.ifBlank { "Custom REST API" }, color = Color.LightGray); Text("${api.method} ${api.baseUrl}${api.defaultPath}", color = Color(0xFF8FC7FF))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ name = api.name; description = api.description; baseUrl = api.baseUrl; path = api.defaultPath; method = api.method; headers = api.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }; body = api.body; status = "Editing ${api.name}" }, Modifier.weight(1f)) { Text("Edit") }
                        OutlinedButton({ hub.delete(api.name); apis = hub.all(); status = "Deleted ${api.name}." }, Modifier.weight(1f)) { Text("Delete") }
                    }
                }
            }
        }
    }
}
