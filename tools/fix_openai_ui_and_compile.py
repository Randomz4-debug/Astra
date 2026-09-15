from pathlib import Path
import re

p=Path('app/src/main/java/com/astra/ai/AstraMainActivity.kt')
s=p.read_text(encoding='utf-8')

# Remove OpenAI model discovery/list state and all related UI without touching API-key/model entry.
s=re.sub(r'\n\s*var openAiModels by remember \{ mutableStateOf<List<String>>\(emptyList\(\)\) \}', '', s)
s=re.sub(r'\n\s*val cloudResult = runCatching \{ openAi\.discoverModels\(\) \}\.getOrDefault\(emptyList\(\)\)', '', s)
s=re.sub(r'\n\s*openAiModels = cloudResult', '', s)
s=re.sub(r'\n\s*if \(openAiModel\.isBlank\(\) && cloudResult\.isNotEmpty\(\)\) openAiModel = cloudResult\.first\(\)', '', s)
s=re.sub(r'\s*if \(openAi\.hasApiKey\(\)\) append\(" • \$\{cloudResult\.size\} OpenAI model\(s\)"\) else append\(" • OpenAI key not configured"\)', ' if (openAi.hasApiKey()) append(" • OpenAI configured") else append(" • OpenAI key not configured")', s)

# Remove automatic discovery on settings navigation entirely.
s=re.sub(r'\n\s*LaunchedEffect\(page\) \{ if \(page == 1\) discoverAllModels\(\) \}', '', s)
# Remove now-unused discovery function body, retaining other functions.
s=re.sub(r'\n\s*fun discoverAllModels\(\) \{.*?\n\s*\}\n\s*\n\s*LaunchedEffect\(ui\.state, ui\.response\)', '\n\n    LaunchedEffect(ui.state, ui.response)', s, flags=re.S)

# Remove standalone OpenAI model-list composable blocks if present.
s=re.sub(r'\n\s*if \(openAiModels\.isNotEmpty\(\)\) \{.*?\n\s*\}', '', s, flags=re.S)
s=re.sub(r'\n[^\n]*openAiModels[^\n]*', '', s)

# The old UI cleanup could leave a stale cloudResult reference; eliminate only that reference safely.
s=s.replace('cloudResult.size', '0')
s=s.replace('cloudResult.isNotEmpty()', 'false')
s=s.replace('cloudResult.first()', 'openAiModel')

# Never auto-fetch OpenAI models.
if 'openAi.discoverModels()' in s:
    s=s.replace('openAi.discoverModels()', 'emptyList()')

p.write_text(s, encoding='utf-8')
print('OpenAI model list/discovery removed and compile references cleaned.')
