from pathlib import Path

p = Path('app/src/main/java/com/astra/ai/AstraAgentRuntime.kt')
s = p.read_text(encoding='utf-8')
marker = '        if (lower == "api manager" || lower == "api settings" || lower == "manage apis" || lower == "open api manager" || lower == "open api settings") {'
if 'private val automation = AstraAutomationEngine(appContext)' not in s:
    s = s.replace('    private val connectionTools = AstraConnectionTools(appContext)\n', '    private val connectionTools = AstraConnectionTools(appContext)\n    private val automation = AstraAutomationEngine(appContext)\n')

insert = '''        if (lower in setOf("automations", "automation", "workflows", "workflow", "open automations", "open automation builder", "open workflows")) {
            return runCatching { appContext.startActivity(Intent(appContext, AstraAutomationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Opening Astra Automation Builder." }.getOrElse { "Could not open Automation Builder: ${it.message}" }
        }
        if (lower == "list automations" || lower == "show automations" || lower == "list workflows" || lower == "show workflows") {
            val all = automation.list(); return if (all.isEmpty()) "No automations are saved. Say create an automation or open the Automation Builder." else all.joinToString("; ") { "${it.id}: ${it.name} [${it.trigger.type}] ${if (it.enabled) "enabled" else "disabled"}" }
        }
        if (lower.startsWith("run automation ") || lower.startsWith("run workflow ")) {
            val target = clean.substringAfter(' ').substringAfter(' ').trim()
            val w = automation.list().firstOrNull { it.id.equals(target, true) || it.name.equals(target, true) || it.name.contains(target, true) }
                ?: return "I could not find automation '$target'. Say 'list automations' to see them."
            val e = automation.run(w.id); return "Automation '${w.name}' ${e.status}. ${e.output}".trim()
        }
        if (lower.startsWith("enable automation ") || lower.startsWith("disable automation ") || lower.startsWith("enable workflow ") || lower.startsWith("disable workflow ")) {
            val enabled = lower.startsWith("enable"); val target = clean.substringAfter(' ').substringAfter(' ').trim()
            val w = automation.list().firstOrNull { it.id.equals(target, true) || it.name.equals(target, true) || it.name.contains(target, true) }
                ?: return "I could not find automation '$target'."
            automation.setEnabled(w.id, enabled); return "Automation '${w.name}' ${if (enabled) "enabled" else "disabled"}."
        }
        if (lower == "automation history" || lower == "workflow history" || lower == "show automation history") {
            val h = automation.history(10); return if (h.isEmpty()) "No automation executions yet." else h.joinToString("; ") { "${it.status}: ${it.workflowId}: ${it.output.take(160)}" }
        }
        if (lower.startsWith("create automation ") || lower.startsWith("create workflow ")) {
            val spec = clean.substringAfter(' ').substringAfter(' ').trim()
            val split = Regex("(?i)^(.+?)\\s*:\\s*(.+)$").find(spec)
            if (split == null) return "Use: create automation <name>: <step>; <step>; ..."
            val name = split.groupValues[1].trim(); val rawSteps = split.groupValues[2].split(';').map { it.trim() }.filter { it.isNotBlank() }
            val nodes = rawSteps.map { step ->
                val x = step.trim(); val k = x.substringBefore(':', x).trim().lowercase(); val v = if (x.contains(':')) x.substringAfter(':').trim() else x
                when {
                    k in setOf("wait", "delay") -> AstraAutomationEngine.Node("wait", v)
                    k in setOf("open", "open_app", "app", "launch") -> AstraAutomationEngine.Node("open_app", v)
                    k in setOf("url", "open_url", "browser") -> AstraAutomationEngine.Node("open_url", v)
                    k in setOf("say", "speak") -> AstraAutomationEngine.Node("speak", v)
                    k in setOf("ai", "ask_ai", "ask") -> AstraAutomationEngine.Node("ai", v)
                    k in setOf("notify", "notification") -> AstraAutomationEngine.Node("notify", v)
                    k in setOf("click", "tap") -> AstraAutomationEngine.Node("click", v)
                    k in setOf("type", "write") -> AstraAutomationEngine.Node("type", v)
                    k in setOf("http", "rest", "api") -> AstraAutomationEngine.Node("http", v, "GET")
                    k in setOf("loop", "repeat") -> AstraAutomationEngine.Node("loop", v)
                    else -> AstraAutomationEngine.Node("ai", x)
                }
            }
            val trigger = Regex("(?i)^(?:every|interval)\\s+(\\d+)$").find(name)
            val workflow = if (trigger != null) automation.create(name, "interval", trigger.groupValues[1].toLongOrNull()?.times(1000)?.toString() ?: "60000", nodes) else automation.create(name, "manual", "", nodes)
            return "Created automation '${workflow.name}' with ${workflow.nodes.size} nodes."
        }

'''
if 'Opening Astra Automation Builder.' not in s:
    s = s.replace(marker, insert + marker)
p.write_text(s, encoding='utf-8')
print('Automation brain wired into AstraAgentRuntime')
