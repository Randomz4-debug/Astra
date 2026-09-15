from pathlib import Path

ROOT = Path('app/src/main')
main = ROOT / 'java/com/astra/ai/AstraMainActivity.kt'
text = main.read_text(encoding='utf-8')

# Cohesive Siri-inspired palette: near-black surfaces with cool violet/blue accents.
replacements = {
    'primary = Color(0xFFFF2146)': 'primary = Color(0xFF8B7CFF)',
    'secondary = Color(0xFFB40025)': 'secondary = Color(0xFF6E62E8)',
    'tertiary = Color(0xFF4C8CFF)': 'tertiary = Color(0xFF5AC8FA)',
    'Color(0xFFFF2146)': 'Color(0xFF8B7CFF)',
    'Color(0xFF780015)': 'Color(0xFF4E46A8)',
    'Color(0xFF26050D)': 'Color(0xFF171329)',
    'Color(0xFF1554FF)': 'Color(0xFF6E62E8)',
    'Color(0xFF61C7FF)': 'Color(0xFF5AC8FA)',
}
for old, new in replacements.items():
    text = text.replace(old, new)

# Make the main shell calmer and more iOS-like without changing any business logic.
text = text.replace(
    'TopAppBar(title = { Text("ASTRA", color = Color.White) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))',
    'TopAppBar(title = { Text("Astra", color = Color.White, style = MaterialTheme.typography.titleLarge) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))'
)
text = text.replace(
    'NavigationBar(containerColor = Color(0xFF0A0B11))',
    'NavigationBar(containerColor = Color(0xFF090A10), tonalElevation = 0.dp)'
)
text = text.replace(
    'Text("Voice activity ${rms.toInt()} dB", color = Color.Gray, style = MaterialTheme.typography.bodySmall)',
    'Text(if (live) "Listening" else "Ready", color = Color(0xFFB8B7C8), style = MaterialTheme.typography.bodySmall)'
)
main.write_text(text, encoding='utf-8')

# XML theme used by non-Compose activities: consistent dark assistant chrome.
theme = ROOT / 'res/values/themes.xml'
if theme.exists():
    t = theme.read_text(encoding='utf-8')
    t = t.replace('<item name="android:statusBarColor">#08090D</item>', '<item name="android:statusBarColor">#030408</item>')
    t = t.replace('<item name="android:navigationBarColor">#08090D</item>', '<item name="android:navigationBarColor">#030408</item>')
    theme.write_text(t, encoding='utf-8')

print('Siri-inspired UI upgrade applied')
