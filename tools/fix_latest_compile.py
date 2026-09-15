from pathlib import Path
import re

runtime = Path("app/src/main/java/com/astra/ai/AstraAgentRuntime.kt")
s = runtime.read_text(encoding="utf-8")

# Remove the problematic Kotlin \s escape from the canned-greeting normalizer
# entirely. This avoids relying on string-literal regex escaping.
start = s.find('    private fun isCannedGreeting(text: String): Boolean {')
end = s.find('    suspend fun automationReason', start)
if start >= 0 and end > start:
    replacement = '''    private fun isCannedGreeting(text: String): Boolean {
        val n = text.lowercase().trim().split(" ").filter { it.isNotBlank() }.joinToString(" ")
        return n == "hi i am astra and i am here to help you." ||
            n == "hi, i am astra and i am here to help you." ||
            n == "hi i am astra and i'm here to help you." ||
            (n.length < 100 && n.contains("i am astra") && n.contains("here to help"))
    }

'''
    s = s[:start] + replacement + s[end:]

# Give recursive JSON-builder functions explicit return types so Kotlin's
# type inference does not recurse through apply/forEach/nodeJson.
auto = Path("app/src/main/java/com/astra/ai/AstraAutomationEngine.kt")
a = auto.read_text(encoding="utf-8")
a = a.replace('private fun nodeJson(n: Node) = JSONObject().apply', 'private fun nodeJson(n: Node): JSONObject = JSONObject().apply')
a = a.replace('private fun workflowJson(w: Workflow) = JSONObject().apply', 'private fun workflowJson(w: Workflow): JSONObject = JSONObject().apply')
auto.write_text(a, encoding="utf-8")

runtime.write_text(s, encoding="utf-8")
print("Latest Kotlin compile fixes applied.")
