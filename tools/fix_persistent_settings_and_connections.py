from pathlib import Path

root=Path('app/src/main')
main=root/'java/com/astra/ai/AstraMainActivity.kt'
text=main.read_text(encoding='utf-8')
# Never normalize or overwrite a user-supplied Ollama endpoint during model discovery.
bad='if (endpoint.contains(":12434") && localResult.isNotEmpty()) { endpoint = endpoint.replace(":12434", ":11434"); local.configure(endpoint, model) }\n                '
text=text.replace(bad,'')
main.write_text(text,encoding='utf-8')

settings=root/'java/com/astra/ai/AstraAdvancedSettingsActivity.kt'
s=settings.read_text(encoding='utf-8')
needle='item{Text("LIVE ASSIST MODE",style=MaterialTheme.typography.titleLarge);'
insert='item{OutlinedButton({a.startActivity(Intent(a, AstraConnectedAppsActivity::class.java))},Modifier.fillMaxWidth()){Text("Connected Apps")}}\n  '
if 'AstraConnectedAppsActivity::class.java' not in s:
    s=s.replace(needle,insert+needle)
settings.write_text(s,encoding='utf-8')
print('Applied persistent-settings fix and Connected Apps entry point.')
