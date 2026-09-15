from pathlib import Path
import re

MAIN = Path('app/src/main/java/com/astra/ai/AstraMainActivity.kt')
s = MAIN.read_text(encoding='utf-8')

# Remove the OpenAI model-discovery button without touching surrounding Compose scopes.
fetch_marker = 'openAiStatus = "Fetching OpenAI models…"'
pos = s.find(fetch_marker)
if pos >= 0:
    start = s.rfind('                        OutlinedButton(onClick = {', 0, pos)
    if start >= 0:
        depth = 0
        end = None
        for i in range(start, len(s)):
            if s[i] == '(':
                depth += 1
            elif s[i] == ')':
                depth -= 1
                if depth == 0:
                    end = i + 1
                    break
        if end:
            s = s[:start] + s[end:]

# Remove the visible OpenAI model-list display.
old_model_display = '''                    if (openAiModels.isNotEmpty()) {
                        Text("OPENAI MODELS", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { openAiModels.forEach { item -> FilterChip(openAiModel == item, { openAiModel = item; openAi.setModel(item) }, label = { Text(item) }) } }
                    }
'''
s = s.replace(old_model_display, '')

# Remove any OpenAI model fetching and automatic selection. Keep a private empty state only if
# the older generated Clear button still references it; it is never populated and never rendered.
s = re.sub(r'^\s*var openAiModels by remember \{ mutableStateOf<List<String>>\(emptyList\(\)\) \}\n', '', s, flags=re.M)
s = re.sub(r'^\s*openAiModels = .*\n', '', s, flags=re.M)
s = re.sub(r'^\s*if \(openAiModel\.isBlank\(\) && cloudResult\.isNotEmpty\(\)\) openAiModel = cloudResult\.first\(\)\n', '', s, flags=re.M)
s = re.sub(r'^\s*val cloudResult = runCatching \{ openAi\.discoverModels\(\) \}\.getOrDefault\(emptyList\(\)\)\n', '', s, flags=re.M)
s = s.replace('openAiModels = emptyList(); ', '')
s = s.replace('; openAiModels = emptyList()', '')
s = re.sub(r'if \(openAi\.hasApiKey\(\)\) append\(" • \$\{cloudResult\.size\} OpenAI model\(s\)"\) else append\(" • OpenAI key not configured"\)', 'append(" • OpenAI model selection is manual")', s)

# Some older generated source can retain a status expression referring to cloudResult after the
# discovery call is removed. Make that status local-only and never perform an OpenAI model call.
if 'cloudResult' in s:
    s = re.sub(r'cloudResult\.size', '0', s)
    s = re.sub(r'val cloudResult\s*=.*\n', '', s)

# If the legacy Clear API Key line still references the old state, remove only that assignment.
s = s.replace('openAi.clearApiKey(); openAiKey = ""; openAiModels = emptyList(); openAiStatus', 'openAi.clearApiKey(); openAiKey = ""; openAiStatus')

# Critical jank fix: RMS updates frequently. Keep them in a tiny child composable instead of
# collecting them in AstraHome, which otherwise invalidates the entire LazyColumn on every update.
s = s.replace('    val rms by VoiceTelemetry.rms.collectAsState()\n', '')
s = s.replace('                Spacer(Modifier.height(10.dp)); AstraWave(); Text("Voice activity ${rms.toInt()} dB", color = Color.Gray, style = MaterialTheme.typography.bodySmall)\n', '                Spacer(Modifier.height(10.dp)); AstraVoiceActivity(live)\n')

# Remove the continuously-running animated home wave. The dedicated Siri voice session remains
# animated; the main screen gets a cheap static waveform so scrolling stays responsive.
start = s.find('@Composable\nprivate fun AstraWave()')
end = s.find('\n@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)', start)
if start >= 0 and end > start:
    wave = '''@Composable\nprivate fun AstraWave() {\n    Canvas(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF07080C))) {\n        val center = size.height / 2f\n        val path = Path()\n        var x = 0f\n        var first = true\n        while (x <= size.width) {\n            val y = center + sin(x * 0.017f) * size.height * 0.10f\n            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)\n            x += 8f\n        }\n        drawPath(path, Brush.horizontalGradient(listOf(Color(0xFF1554FF), Color(0xFF61C7FF), Color(0xFF1554FF))), style = Stroke(3f, cap = StrokeCap.Round))\n    }\n}\n\n@Composable\nprivate fun AstraVoiceActivity(live: Boolean) {\n    val rms by VoiceTelemetry.rms.collectAsState()\n    Column {\n        AstraWave()\n        Text(if (live) "Voice activity ${rms.toInt()} dB" else "Voice activity idle", color = Color.Gray, style = MaterialTheme.typography.bodySmall)\n    }\n}\n'''
    s = s[:start] + wave + s[end:]

# Navigation must never launch model discovery.
s = re.sub(r'\n\s*LaunchedEffect\(page\) \{ if \(page == 1\) discoverAllModels\(\) \}', '', s)

# Final compile hardening: keep legacy OpenAI model state private/empty only when old generated
# code still references it. There is deliberately no UI and no network discovery for it.
if 'openAiModels' in s and 'var openAiModels' not in s:
    anchor = '    var localModels by remember { mutableStateOf<List<String>>(emptyList()) }\n'
    if anchor in s:
        s = s.replace(anchor, anchor + '    var openAiModels by remember { mutableStateOf<List<String>>(emptyList()) }\n', 1)
if 'cloudResult' in s and 'val cloudResult' not in s:
    anchor = '            val localResult = runCatching { local.discoverModels() }.getOrDefault(emptyList())\n'
    if anchor in s:
        s = s.replace(anchor, anchor + '            val cloudResult = emptyList<String>()\n', 1)

MAIN.write_text(s, encoding='utf-8')
print('Final UI cleanup applied: OpenAI model fetching/display removed; legacy state is private and empty; high-frequency voice rendering isolated.')
