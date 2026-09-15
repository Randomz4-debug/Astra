from pathlib import Path
p=Path('tools/upgrade_intelligence_ui.py')
s=p.read_text(encoding='utf-8')
s=s.replace('orientation=LinearLayout.VERTICAL;padding=22', 'orientation=LinearLayout.VERTICAL')
s=s.replace('root.setBackgroundColor(android.graphics.Color.rgb(5,6,10)); listOf', 'root.setPadding(22,22,22,22); root.setBackgroundColor(android.graphics.Color.rgb(5,6,10)); listOf')
p.write_text(s, encoding='utf-8')
q=Path('app/src/main/java/com/astra/ai/AstraProviderActivity.kt')
if q.exists():
    x=q.read_text(encoding='utf-8').replace('orientation=LinearLayout.VERTICAL;padding=22','orientation=LinearLayout.VERTICAL').replace('root.setBackgroundColor(android.graphics.Color.rgb(5,6,10)); listOf','root.setPadding(22,22,22,22); root.setBackgroundColor(android.graphics.Color.rgb(5,6,10)); listOf')
    q.write_text(x,encoding='utf-8')
print('compile hardening applied')
