from pathlib import Path
import re

runtime = Path("app/src/main/java/com/astra/ai/AstraAgentRuntime.kt")
s = runtime.read_text(encoding="utf-8")

# Eliminate the generated \s+ token completely. Java/Kotlin regex supports
# the POSIX space class, so no Kotlin string backslash escaping is needed.
s = re.sub(r'\\+s\+', '[[:space:]]+', s)

# Remove generated greeting normalizers as a second line of defense.
def replace_function(source: str, signature: str, next_signature: str, body: str) -> str:
    start = source.find(signature)
    end = source.find(next_signature, start)
    if start >= 0 and end > start:
        return source[:start] + body + source[end:]
    return source

s = replace_function(
    s,
    '    private fun isGreeting(text: String): Boolean {',
    '    private fun isCannedGreeting(text: String): Boolean {',
    '''    private fun isGreeting(text: String): Boolean {
        val n = text.lowercase().trim().split(" ").filter { it.isNotBlank() }.joinToString(" ")
        return n in setOf("hi", "hello", "hey", "hey astra", "hi astra", "hello astra", "good morning", "good afternoon", "good evening")
    }

'''
)
s = replace_function(
    s,
    '    private fun isCannedGreeting(text: String): Boolean {',
    '    suspend fun automationReason',
    '''    private fun isCannedGreeting(text: String): Boolean {
        val n = text.lowercase().trim().split(" ").filter { it.isNotBlank() }.joinToString(" ")
        return n == "hi i am astra and i am here to help you." ||
            n == "hi, i am astra and i am here to help you." ||
            n == "hi i am astra and i'm here to help you." ||
            (n.length < 100 && n.contains("i am astra") && n.contains("here to help"))
    }

'''
)
runtime.write_text(s, encoding="utf-8")

auto = Path("app/src/main/java/com/astra/ai/AstraAutomationEngine.kt")
a = auto.read_text(encoding="utf-8")
a = a.replace('private fun nodeJson(n: Node) = JSONObject().apply', 'private fun nodeJson(n: Node): JSONObject = JSONObject().apply')
a = a.replace('private fun workflowJson(w: Workflow) = JSONObject().apply', 'private fun workflowJson(w: Workflow): JSONObject = JSONObject().apply')
auto.write_text(a, encoding="utf-8")

print("Latest Kotlin compile fixes applied.")
