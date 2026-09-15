from pathlib import Path

p=Path('tools/upgrade_intelligence_ui.py')
s=p.read_text(encoding='utf-8')
s=s.replace('orientation=LinearLayout.VERTICAL;padding=22', 'orientation=LinearLayout.VERTICAL')
s=s.replace('orientation = LinearLayout.VERTICAL; padding = 22', 'orientation = LinearLayout.VERTICAL')
s=s.replace('padding=22', '')
s=s.replace('padding = 22', '')
p.write_text(s,encoding='utf-8')

q=Path('app/src/main/java/com/astra/ai/AstraProviderActivity.kt')
if q.exists():
    x=q.read_text(encoding='utf-8')
    x=x.replace('orientation=LinearLayout.VERTICAL;padding=22','orientation=LinearLayout.VERTICAL')
    x=x.replace('orientation = LinearLayout.VERTICAL; padding = 22','orientation = LinearLayout.VERTICAL')
    x=x.replace('padding=22','').replace('padding = 22','')
    x=x.replace('root.setBackgroundColor(android.graphics.Color.rgb(5,6,10)); listOf','root.setPadding(22,22,22,22); root.setBackgroundColor(android.graphics.Color.rgb(5,6,10)); listOf')
    q.write_text(x,encoding='utf-8')

# The previous build had an unresolved assistantName reference in the chat composer.
r=Path('app/src/main/java/com/astra/ai/AstraChatActivity.kt')
if r.exists():
    x=r.read_text(encoding='utf-8')
    x=x.replace('Text("Message $assistantName…")','Text("Message Astra…")')
    x=x.replace('Text(assistantName)','Text("Astra")')
    x=x.replace('Text(assistantName_unused)','Text("Astra")')
    x=x.replace('var assistantName by remember { mutableStateOf("Astra") }','')
    x=x.replace('var assistantName_unused by remember { mutableStateOf("Astra") }','')
    x=x.replace('assistantName = getSharedPreferences("astra_runtime", MODE_PRIVATE).getString("assistant_name", "Astra") ?: "Astra"','')
    x=x.replace('assistantName_unused = getSharedPreferences("astra_runtime", MODE_PRIVATE).getString("assistant_name", "Astra") ?: "Astra"','')
    r.write_text(x,encoding='utf-8')
print('intelligence/provider/chat compile hardening applied')
