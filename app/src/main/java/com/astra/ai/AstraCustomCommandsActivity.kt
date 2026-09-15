package com.astra.ai

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class AstraCustomCommandsActivity : ComponentActivity() {
    private val store by lazy { AstraCustomCommandStore(this) }
    private var commands by mutableStateOf(emptyList<AstraCustomCommandStore.Command>())
    private var trigger by mutableStateOf("")
    private var actions by mutableStateOf("")
    private var editingTrigger by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        commands = store.list()
        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("Custom Commands", style = MaterialTheme.typography.headlineMedium)
                    Text("Create a trigger and a sequence of actions. Saved commands can be edited later.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(trigger, { trigger = it }, Modifier.fillMaxWidth(), label = { Text("Trigger text / voice phrase") }, singleLine = true)
                    OutlinedTextField(actions, { actions = it }, Modifier.fillMaxWidth().height(120.dp), label = { Text("Actions separated by ;") }, supportingText = { Text("Example: open_app:YouTube;wait:1000;click:Search;type:hello\nAlso: say:Hello;assist_chat;repeat:3,click:Like") })
                    Button(onClick = {
                        runCatching {
                            val old = editingTrigger
                            if (old != null && !old.equals(trigger.trim(), true)) store.delete(old)
                            store.addOrUpdate(trigger, actions)
                        }.onSuccess {
                            editingTrigger = null
                            trigger = ""
                            actions = ""
                            commands = store.list()
                        }.onFailure {
                            Toast.makeText(this@AstraCustomCommandsActivity, it.message ?: "Could not save", Toast.LENGTH_LONG).show()
                        }
                    }, Modifier.fillMaxWidth()) { Text(if (editingTrigger == null) "Save custom command" else "Update custom command") }
                    if (editingTrigger != null) {
                        OutlinedButton(onClick = { editingTrigger = null; trigger = ""; actions = "" }, Modifier.fillMaxWidth()) { Text("Cancel edit") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Default terminate command: say/type `terminate` to stop all active custom commands.", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(commands, key = { it.uuid }) { c ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("${c.id}  •  ${c.trigger}", style = MaterialTheme.typography.titleMedium)
                                    Text(c.actions, style = MaterialTheme.typography.bodySmall)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Switch(checked = c.enabled, onCheckedChange = { store.setEnabled(c.trigger, it); commands = store.list() })
                                        OutlinedButton(onClick = { editingTrigger = c.trigger; trigger = c.trigger; actions = c.actions }) { Text("Edit") }
                                        OutlinedButton(onClick = {
                                            store.delete(c.trigger)
                                            if (editingTrigger.equals(c.trigger, true)) { editingTrigger = null; trigger = ""; actions = "" }
                                            commands = store.list()
                                        }) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
