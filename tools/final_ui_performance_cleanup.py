from pathlib import Path
import re

MAIN = Path('app/src/main/java/com/astra/ai/AstraMainActivity.kt')
s = MAIN.read_text(encoding='utf-8')

# Remove the OpenAI model-discovery button injected by the older model UI upgrade.
marker = 'openAiStatus = "Fetching OpenAI models…"'
pos = s.find(marker)
if pos >= 0:
    start = s.rfind('OutlinedButton(onClick = {', 0, pos)
    if start >= 0:
        depth = 0
        in_string = False
        escaped = False
        end = None
        for i in range(start, len(s)):
            ch = s[i]
            if in_string:
                if escaped:
                    escaped = False
                elif ch == '\\':
                    escaped = True
                elif ch == '"':
                    in_string = False
                continue
            if ch == '"':
                in_string = True
            elif ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
                if depth == 0:
                    end = i + 1
                    break
        if end:
            s = s[:start] + s[end:]

# Remove any remaining OpenAI available-model UI block, regardless of whether the older
# generator rendered it as one line or several lines.
while 'openAiModels' in s:
    pos = s.find('openAiModels')
    line_start = s.rfind('\n', 0, pos) + 1
    line_end = s.find('\n', pos)
    if line_end < 0:
        line_end = len(s)
    line = s[line_start:line_end]
    if '{' in line and '}' in line:
        s = s[:line_start] + s[line_end + (1 if line_end < len(s) else 0):]
        continue
    # If this is a multiline if block, locate its nearest preceding if and balance braces.
    if_start = s.rfind('if (', 0, line_start)
    if if_start >= 0 and if_start > s.rfind('\n', 0, line_start - 1) - 2000:
        brace = s.find('{', if_start, line_end + 200)
        if brace >= 0:
            depth = 0
            end = None
            for i in range(brace, len(s)):
                if s[i] == '{':
                    depth += 1
                elif s[i] == '}':
                    depth -= 1
                    if depth == 0:
                        end = i + 1
                        break
            if end:
                s = s[:if_start] + s[end:]
                continue
    # Last-resort: remove only the offending source line. This is safe for generated model-list UI.
    s = s[:line_start] + s[line_end + (1 if line_end < len(s) else 0):]

# OpenAI model discovery is no longer part of the UI at all.
s = s.replace('    var openAiModels by remember { mutableStateOf<List<String>>(emptyList()) }\n', '')
s = s.replace('                openAiModels = cloudResult\n', '')
s = s.replace('                                                openAiModels = emptyList()\n', '')
s = s.replace('                        openAiModels = emptyList()\n', '')
s = s.replace('            val cloudResult = runCatching { openAi.discoverModels() }.getOrDefault(emptyList())\n', '')
s = s.replace('                if (openAiModel.isBlank() && cloudResult.isNotEmpty()) openAiModel = cloudResult.first()\n', '')
s = re.sub(r'if \(openAi\.hasApiKey\(\)\) append\(" • \$\{cloudResult\.size\} OpenAI model\(s\)"\) else append\(" • OpenAI key not configured"\)', 'append(" • OpenAI model selection is manual")', s)

# Critical jank fix: VoiceTelemetry.rms can update many times per second. Keep it out of the
# large AstraHome composition so scrolling and navigation do not recompose the whole screen.
s = s.replace('    val rms by VoiceTelemetry.rms.collectAsState()\n', '')
s = s.replace('                Spacer(Modifier.height(10.dp)); AstraWave(); Text("Voice activity ${rms.toInt()} dB", color = Color.Gray, style = MaterialTheme.typography.bodySmall)\n', '                Spacer(Modifier.height(10.dp)); AstraVoiceActivity(live)\n')

# Replace the continuously animated home-screen wave with a static, cheap Canvas. The dedicated
# Siri voice session remains animated separately.
start = s.find('@Composable\nprivate fun AstraWave()')
end = s.find('\n@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)', start)
if start >= 0 and end > start:
    wave = '''@Composable\nprivate fun AstraWave() {\n    Canvas(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF07080C))) {\n        val center = size.height / 2f\n        val path = Path()\n        var x = 0f\n        var first = true\n        while (x <= size.width) {\n            val y = center + sin(x * 0.017f) * size.height * 0.10f\n            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)\n            x += 8f\n        }\n        drawPath(path, Brush.horizontalGradient(listOf(Color(0xFF1554FF), Color(0xFF61C7FF), Color(0xFF1554FF))), style = Stroke(3f, cap = StrokeCap.Round))\n    }\n}\n\n@Composable\nprivate fun AstraVoiceActivity(live: Boolean) {\n    val rms by VoiceTelemetry.rms.collectAsState()\n    Column {\n        AstraWave()\n        Text(if (live) "Voice activity ${rms.toInt()} dB" else "Voice activity idle", color = Color.Gray, style = MaterialTheme.typography.bodySmall)\n    }\n}\n'''
    s = s[:start] + wave + s[end:]

# Never trigger model discovery merely because the user changes navigation tabs.
s = re.sub(r'\n\s*LaunchedEffect\(page\) \{ if \(page == 1\) discoverAllModels\(\) \}', '', s)

MAIN.write_text(s, encoding='utf-8')
print('Final UI cleanup applied: OpenAI model UI removed; high-frequency voice state isolated; home wave made static.')
