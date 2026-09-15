from pathlib import Path
p=Path('app/src/main/java/com/astra/ai/AstraAutomationActivity.kt')
s=p.read_text(encoding='utf-8')
s=s.replace('Button(onClick={', 'Button({')
p.write_text(s, encoding='utf-8')
print('Automation Compose syntax normalized')
