from pathlib import Path

MAIN = Path('app/src/main/java/com/astra/ai/AstraMainActivity.kt')
CONN = Path('app/src/main/java/com/astra/ai/AstraConnectedAppsActivity.kt')

text = MAIN.read_text(encoding='utf-8')
text = text.replace('    LaunchedEffect(page) { if (page == 1) discoverAllModels() }\n', '    // Model discovery is explicitly user-triggered; opening Settings never performs network model discovery.\n')
text = text.replace('''                localModels = localResult
                openAiModels = cloudResult
                if (endpoint.contains(":12434") && localResult.isNotEmpty()) { endpoint = endpoint.replace(":12434", ":11434"); local.configure(endpoint, model) }
                if (model.isBlank() && localResult.isNotEmpty()) { model = localResult.first(); local.configure(endpoint, model) }
                if (openAiModel.isBlank() && cloudResult.isNotEmpty()) openAiModel = cloudResult.first()
                modelStatus = buildString { append(diagnosis); append("\\nDetected ${localResult.size} local model(s)"); if (openAi.hasApiKey()) append(" • ${cloudResult.size} OpenAI model(s)") else append(" • OpenAI key not configured") }
                discoveringModels = false
''','''                localModels = localResult
                openAiModels = cloudResult
                modelStatus = buildString { append(diagnosis); append("\\nDetected ${localResult.size} local model(s)"); if (openAi.hasApiKey()) append(" • ${cloudResult.size} OpenAI model(s)") else append(" • OpenAI key not configured") }
                discoveringModels = false
''')
text = text.replace('''                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ local.configure(endpoint, model); info = "Local AI settings saved." }, Modifier.weight(1f)) { Text("Save") }; OutlinedButton({ discoverAllModels() }, Modifier.weight(1f), enabled = !discoveringModels) { Text(if (discoveringModels) "Detecting…" else "Discover Models") } }
''','''                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { local.configure(endpoint, model); info = "Local AI settings saved." }, modifier = Modifier.weight(1f), content = { Text("Save") })
                        OutlinedButton(onClick = {
                            if (!discoveringModels) {
                                discoveringModels = true
                                modelStatus = "Fetching local models…"
                                a.lifecycleScope.launch(Dispatchers.IO) {
                                    val result = runCatching { local.discoverModels() }.getOrDefault(emptyList())
                                    val diagnosis = runCatching { local.diagnose() }.getOrDefault("Local discovery finished.")
                                    withContext(Dispatchers.Main) {
                                        localModels = result
                                        modelStatus = "$diagnosis\\nDetected ${result.size} local model(s)"
                                        discoveringModels = false
                                    }
                                }
                            }
                        }, modifier = Modifier.weight(1f), enabled = !discoveringModels, content = { Text(if (discoveringModels) "Fetching…" else "Fetch Local Models") })
                    }
''')
text = text.replace('FilterChip(model == item, { model = item; local.configure(endpoint, item) }, label = { Text(item) })','FilterChip(selected = model == item, onClick = { model = item; local.configure(endpoint, item); localModels = emptyList(); modelStatus = "Selected local model: $item" }, label = { Text(item) })')
text = text.replace('FilterChip(openAiModel == item, { openAiModel = item; openAi.setModel(item) }, label = { Text(item) })','FilterChip(selected = openAiModel == item, onClick = { openAiModel = item; openAi.setModel(item); openAiModels = emptyList(); openAiStatus = "Selected OpenAI model: $item" }, label = { Text(item) })')
old = '''                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ showOpenAiKey = !showOpenAiKey }, Modifier.weight(1f)) { Text(if (showOpenAiKey) "Hide Key" else "Show Key") }; Button({ openAi.saveApiKey(openAiKey); openAiKey = ""; openAiStatus = "API key saved securely on this device." }, Modifier.weight(1f)) { Text("Save Key") } }
                    OutlinedTextField(openAiModel, { openAiModel = it }, Modifier.fillMaxWidth(), label = { Text("OpenAI model") }, singleLine = true, supportingText = { Text("Choose a detected model or enter one manually.") })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ openAi.setModel(openAiModel); openAiStatus = "Model saved: ${openAiModel.trim()}" }, Modifier.weight(1f)) { Text("Save Model") }; OutlinedButton({ a.lifecycleScope.launch { openAiStatus = openAi.testConnection() } }, Modifier.weight(1f)) { Text("Test") } }
'''
new = '''                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showOpenAiKey = !showOpenAiKey }, modifier = Modifier.weight(1f), content = { Text(if (showOpenAiKey) "Hide Key" else "Show Key") })
                        Button(onClick = {
                            val value = openAiKey.trim()
                            if (value.isBlank()) {
                                openAiStatus = "Enter an API key first."
                            } else {
                                a.lifecycleScope.launch(Dispatchers.IO) {
                                    runCatching { openAi.saveApiKey(value) }
                                        .onSuccess {
                                            withContext(Dispatchers.Main) {
                                                openAiKey = ""
                                                openAiModels = emptyList()
                                                openAiStatus = "API key saved. Models will only be fetched when you tap Fetch OpenAI Models."
                                            }
                                        }
                                        .onFailure { e -> withContext(Dispatchers.Main) { openAiStatus = "Could not save API key: ${e.message ?: "storage error"}" } }
                                }
                            }
                        }, modifier = Modifier.weight(1f), content = { Text("Save Key") })
                    }
                    OutlinedTextField(openAiModel, { openAiModel = it }, Modifier.fillMaxWidth(), label = { Text("OpenAI model") }, singleLine = true, supportingText = { Text("Enter a model manually or use Fetch OpenAI Models.") })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { openAi.setModel(openAiModel); openAiModels = emptyList(); openAiStatus = "Model saved: ${openAiModel.trim()}" }, modifier = Modifier.weight(1f), content = { Text("Save Model") })
                        OutlinedButton(onClick = {
                            if (!discoveringModels) {
                                discoveringModels = true
                                openAiStatus = "Fetching OpenAI models…"
                                a.lifecycleScope.launch(Dispatchers.IO) {
                                    val result = runCatching { openAi.discoverModels() }.getOrDefault(emptyList())
                                    withContext(Dispatchers.Main) {
                                        openAiModels = result
                                        discoveringModels = false
                                        openAiStatus = if (result.isEmpty()) "No OpenAI models were returned. Check the key/network." else "Fetched ${result.size} OpenAI model(s)."
                                    }
                                }
                            }
                        }, modifier = Modifier.weight(1f), enabled = !discoveringModels && openAi.hasApiKey(), content = { Text(if (discoveringModels) "Fetching…" else "Fetch OpenAI Models") })
                    }
                    OutlinedButton(onClick = {
                        a.lifecycleScope.launch(Dispatchers.IO) {
                            val result = openAi.testConnection()
                            withContext(Dispatchers.Main) { openAiStatus = result }
                        }
                    }, modifier = Modifier.fillMaxWidth(), enabled = !discoveringModels, content = { Text("Test OpenAI Connection") })
'''
if old not in text:
    raise SystemExit('OpenAI UI block not found')
text = text.replace(old, new)
# The local test button is kept explicit too; this removes any ambiguity with Material3 overloads.
text = text.replace('OutlinedButton({ a.lifecycleScope.launch { modelStatus = local.diagnose() } }, Modifier.fillMaxWidth()) { Text("Test Local AI Connection") }', 'OutlinedButton(onClick = { a.lifecycleScope.launch(Dispatchers.IO) { val result = runCatching { local.diagnose() }.getOrDefault("Local AI test failed."); withContext(Dispatchers.Main) { modelStatus = result } } }, modifier = Modifier.fillMaxWidth(), content = { Text("Test Local AI Connection") })')
MAIN.write_text(text, encoding='utf-8')

text = CONN.read_text(encoding='utf-8')
old = '''selected?.let{i->AlertDialog(onDismissRequest={selected=null},title={Text("${i.name} Connection")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text("Available connection mechanisms:");i.methods.forEach{Text("• ${it.name.replace('_',' ')}")};if(i.capabilities.isNotEmpty())Text("Declared capabilities: ${i.capabilities.joinToString()}")}},confirmButton={Button({if(manager.isInstalled(i))manager.openApp(i) else manager.openWebsite(i);selected=null}){Text("Continue")}},dismissButton={TextButton({selected=null}){Text("Cancel")}})}'''
new = '''selected?.let{i->AlertDialog(onDismissRequest={selected=null},title={Text("${i.name} Connection")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(if(manager.isInstalled(i)) "${i.name} is installed. Choose how Astra should connect to it." else "${i.name} is not installed. Use its official website/authentication flow.")
        Text("Available methods: ${i.methods.joinToString { it.name.replace('_',' ') }}")
        Text("Astra will not mark the app connected until a real connection or Android permission is established.")
    }},confirmButton={Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
        if(manager.isInstalled(i)) Button(onClick={manager.openApp(i); selected=null}, content={Text("Open App")})
        if(i.website!=null) OutlinedButton(onClick={manager.openWebsite(i); selected=null}, content={Text("Official Login")})
    }},dismissButton={TextButton(onClick={selected=null}, content={Text("Cancel")})})}'''
if old not in text:
    raise SystemExit('connection dialog block not found')
text = text.replace(old, new)
CONN.write_text(text, encoding='utf-8')
print('patched')
