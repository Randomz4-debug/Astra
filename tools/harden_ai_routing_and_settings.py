from pathlib import Path

p=Path('app/src/main/java/com/astra/ai/LocalAiGateway.kt')
s=p.read_text(encoding='utf-8')
# A user-configured endpoint is authoritative. Do not silently probe another port.
old='''    private fun endpointCandidates(value: String): List<String> {
        val clean = normalize(value)
        val out = linkedSetOf(clean)
        runCatching {
            val u = URL(clean)
            if (u.port == 12434) out.add(URL(u.protocol, u.host, 11434, "").toString().trimEnd('/'))
        }
        return out.toList()
    }
'''
new='''    private fun endpointCandidates(value: String): List<String> {
        // The saved endpoint is authoritative. Never rewrite or silently substitute a port.
        return listOf(normalize(value))
    }
'''
if old in s: s=s.replace(old,new)
s=s.replace('''            val isOllama = request("$base/api/tags", "GET", null) != null || uri.port == 11434 || base.contains("ollama", true)
            val target = if (isOllama) "$base/api/chat" else "$base/v1/chat/completions"
''','''            val isOllama = request("$base/api/tags", "GET", null) != null
            val target = if (isOllama) "$base/api/chat" else "$base/v1/chat/completions"
''')
p.write_text(s,encoding='utf-8')

# Keep the connection entry point available from Advanced Settings on every generated build.
settings=Path('app/src/main/java/com/astra/ai/AstraAdvancedSettingsActivity.kt')
s=settings.read_text(encoding='utf-8')
needle='item{Text("LIVE ASSIST MODE",style=MaterialTheme.typography.titleLarge);'
insert='item{OutlinedButton({a.startActivity(Intent(a, AstraConnectedAppsActivity::class.java))},Modifier.fillMaxWidth()){Text("Connected Apps")}}\n  '
if 'AstraConnectedAppsActivity::class.java' not in s: s=s.replace(needle,insert+needle)
settings.write_text(s,encoding='utf-8')
print('Hardened endpoint authority and Connected Apps navigation.')
