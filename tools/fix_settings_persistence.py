from pathlib import Path

path = Path("app/src/main/java/com/astra/ai/AstraMainActivity.kt")
text = path.read_text(encoding="utf-8")

# Discovery used to silently replace a user-saved custom Ollama port (notably :12434)
# with the default :11434 endpoint. Discovery must never mutate user configuration.
bad = '                if (endpoint.contains(":12434") && localResult.isNotEmpty()) { endpoint = endpoint.replace(":12434", ":11434"); local.configure(endpoint, model) }\n'
if bad in text:
    text = text.replace(bad, "")

# Reload persisted settings when the Settings page is entered. This makes navigation
# and recomposition reflect the values actually stored by the Save buttons.
old = '    LaunchedEffect(page) { if (page == 1) discoverAllModels() }'
new = '''    LaunchedEffect(page) {
        if (page == 1) {
            endpoint = local.endpoint()
            model = local.model()
            openAiModel = openAi.model()
            discoverAllModels()
        }
    }'''
if old in text:
    text = text.replace(old, new)

path.write_text(text, encoding="utf-8")
print("Fixed settings persistence: custom local endpoints are no longer rewritten during discovery.")
