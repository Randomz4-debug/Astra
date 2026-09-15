from pathlib import Path

p=Path('app/src/main/java/com/astra/ai/AstraMainActivity.kt')
s=p.read_text(encoding='utf-8')
needle='item { Text("DEFAULT ASSISTANT", color = Color.White, style = MaterialTheme.typography.titleMedium); Button({ a.chooseDefaultAssistant() }, Modifier.fillMaxWidth()) { Text(if (a.isDefaultAssistant()) "Astra is Default AI" else "Set Astra as Default AI") } }'
insert=needle+'\n                item { Text("ADVANCED ASSISTANT", color = Color.White, style = MaterialTheme.typography.titleMedium); Text("Live Assist, Local Only privacy, wake word, screen-off mode and encrypted app connections.", color = Color.Gray, style = MaterialTheme.typography.bodySmall); OutlinedButton({ a.startActivity(Intent(a, AstraAdvancedSettingsActivity::class.java)) }, Modifier.fillMaxWidth()) { Text("Open Advanced Settings & Connections") } }'
if needle not in s:
    raise SystemExit('DEFAULT ASSISTANT settings anchor not found')
if 'Open Advanced Settings & Connections' not in s:
    s=s.replace(needle,insert,1)
p.write_text(s,encoding='utf-8')
assert 'AstraAdvancedSettingsActivity' in s
assert 'Open Advanced Settings & Connections' in s
