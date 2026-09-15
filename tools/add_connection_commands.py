from pathlib import Path
p=Path('app/src/main/java/com/astra/ai/AstraAgentRuntime.kt')
s=p.read_text(encoding='utf-8')
needle='        val customResult = runCatching { customCommands.handle(clean) }.getOrNull(); if (customResult != null) return customResult.message\n'
insert='''        val connectionTools = AstraConnectionTools(appContext)\n        if (lower in setOf("what apps are connected", "show connected apps", "list connected apps", "connected apps")) {\n            val apps = connectionTools.getConnectedApps()\n            return if (apps.isBlank()) "No apps are connected to Astra." else "Connected apps: $apps"\n        }\n        if (lower == "connect app" || lower == "connect apps" || lower == "open connected apps") {\n            return runCatching { appContext.startActivity(Intent(appContext, AstraConnectedAppsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Opening Connected Apps." }.getOrElse { "Could not open Connected Apps: ${it.message}" }\n        }\n        if (lower.startsWith("open app ")) return connectionTools.openApp(clean.substringAfter("open app ").trim())\n        if (lower.startsWith("disconnect ")) return connectionTools.disconnect(clean.substringAfter("disconnect ").trim())\n'''
if 'val connectionTools = AstraConnectionTools(appContext)' not in s:
    s=s.replace(needle,needle+insert)
p.write_text(s,encoding='utf-8')
print('Added natural-language Connected Apps commands.')
