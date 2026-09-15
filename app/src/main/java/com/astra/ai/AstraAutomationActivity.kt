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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class AstraAutomationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { AutomationScreen(AstraAutomationEngine(this)) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationScreen(engine: AstraAutomationEngine) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf("manual") }
    var triggerValue by remember { mutableStateOf("") }
    var nodeType by remember { mutableStateOf("ai") }
    var nodeValue by remember { mutableStateOf("") }
    var nodeA by remember { mutableStateOf("") }
    var nodeB by remember { mutableStateOf("") }
    var retries by remember { mutableStateOf("0") }
    var nodes by remember { mutableStateOf(listOf<AstraAutomationEngine.Node>()) }
    var workflows by remember { mutableStateOf(engine.list()) }
    var message by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }

    fun refresh() { workflows = engine.list() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Astra Automations", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Text("Local-first workflow builder • triggers • actions • conditions • loops • APIs • AI • app control • history")
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label={Text("Workflow name")})
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick={menu=true}) { Text(trigger) }
            DropdownMenu(menu, { menu=false }) { listOf("manual","interval","delay","webhook","event").forEach { t -> DropdownMenuItem(text={Text(t)}, onClick={ { trigger=t; menu=false } }) } }
            OutlinedTextField(triggerValue, { triggerValue=it }, Modifier.weight(1f), label={Text(if(trigger=="interval") "milliseconds" else "trigger value")})
        }
        Text("Add node")
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            listOf("ai","http","set","condition","loop","foreach","open_app","open_url","click","type","wait","notify","speak","camera","call","scroll","back","home","run_workflow").take(6).forEach { t -> TextButton(onClick={ nodeType=t }) { Text(t) } }
        }
        OutlinedTextField(nodeType, {nodeType=it}, Modifier.fillMaxWidth(), label={Text("Node type")})
        OutlinedTextField(nodeValue, {nodeValue=it}, Modifier.fillMaxWidth(), label={Text("Value / URL / prompt / expression")})
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(nodeA, {nodeA=it}, Modifier.weight(1f), label={Text("A / method / variable")})
            OutlinedTextField(nodeB, {nodeB=it}, Modifier.weight(1f), label={Text("B / body")})
            OutlinedTextField(retries, {retries=it}, Modifier.weight(.45f), label={Text("Retry")})
        }
        Row(Modifier.fillMaxWidth()) {
            Button(onClick={ nodes = nodes + AstraAutomationEngine.Node(nodeType.trim(), nodeValue, nodeA, nodeB, retries.toIntOrNull()?.coerceIn(0,5) ?: 0) }) { Text("Add") }
            Button(onClick={
                if (name.isBlank()) { message="Enter a workflow name."; return@Button }
                val w=engine.create(name, trigger, triggerValue, nodes); message="Saved ${w.name}"; name=""; nodes=emptyList(); refresh()
            }, Modifier.padding(start=6.dp)) { Text("Save workflow") }
        }
        if (nodes.isNotEmpty()) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text("Nodes"); nodes.forEachIndexed { i,n -> Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) { Text("${i+1}. ${n.type}: ${n.value}"); TextButton(onClick={nodes=nodes.filterIndexed { j,_ -> j!=i }}) { Text("Remove") } } } } }
        if (message.isNotBlank()) Text(message)
        Text("Saved workflows")
        LazyColumn(Modifier.weight(1f)) {
            items(workflows) { w ->
                Card(Modifier.fillMaxWidth().padding(vertical=3.dp)) { Column(Modifier.padding(10.dp)) {
                    Text("${w.name} • ${w.trigger.type} ${w.trigger.value} • ${if(w.enabled) "ON" else "OFF"}")
                    Text("${w.nodes.size} nodes")
                    Row {
                        TextButton(onClick={ scope.launch { val e=engine.run(w.id); message="${e.status}: ${e.output}"; refresh() } }) { Text("Run") }
                        TextButton(onClick={engine.setEnabled(w.id,!w.enabled); refresh()}) { Text(if(w.enabled) "Disable" else "Enable") }
                        TextButton(onClick={engine.delete(w.id); refresh()}) { Text("Delete") }
                    }
                } }
            }
        }
        Text("Recent executions")
        engine.history(5).forEach { Text("${it.status} • ${it.workflowId} • ${it.output.take(120)}") }
    }
}
