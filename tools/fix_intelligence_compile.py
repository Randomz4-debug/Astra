from pathlib import Path

# The generated provider activity is deliberately dependency-light; remove the accidental
# Compose-style `padding` property from its Android ViewGroup builder in every formatting variant.
p = Path('tools/upgrade_intelligence_ui.py')
s = p.read_text(encoding='utf-8')
s = s.replace('orientation=LinearLayout.VERTICAL;padding=22', 'orientation=LinearLayout.VERTICAL')
s = s.replace('orientation = LinearLayout.VERTICAL; padding = 22', 'orientation = LinearLayout.VERTICAL')
s = s.replace('padding=22', '')
s = s.replace('padding = 22', '')
p.write_text(s, encoding='utf-8')

q = Path('app/src/main/java/com/astra/ai/AstraProviderActivity.kt')
if q.exists():
    x = q.read_text(encoding='utf-8')
    x = x.replace('orientation=LinearLayout.VERTICAL;padding=22', 'orientation=LinearLayout.VERTICAL')
    x = x.replace('orientation = LinearLayout.VERTICAL; padding = 22', 'orientation = LinearLayout.VERTICAL')
    x = x.replace('padding=22', '')
    x = x.replace('padding = 22', '')
    x = x.replace('root.setBackgroundColor(android.graphics.Color.rgb(5,6,10)); listOf', 'root.setPadding(22,22,22,22); root.setBackgroundColor(android.graphics.Color.rgb(5,6,10)); listOf')
    q.write_text(x, encoding='utf-8')

# The chat screen's assistant-name substitution is intentionally scoped to AstraChatScreen.
# A global placeholder replacement can leak into ChatInput, where assistantName is not in scope.
r = Path('app/src/main/java/com/astra/ai/AstraChatActivity.kt')
if r.exists():
    x = r.read_text(encoding='utf-8').replace('Text("Message $assistantName…")', 'Text("Message Astra…")')
    r.write_text(x, encoding='utf-8')

print('intelligence/provider/chat compile hardening applied')
