from pathlib import Path

# Final compile hardening for AstraAgentRuntime. The generated source uses a
# Java/Kotlin regex whitespace token, but it is emitted as a single backslash
# inside a normal Kotlin string. Replace that token with a regex character
# class that contains no backslash at all.
runtime = Path("app/src/main/java/com/astra/ai/AstraAgentRuntime.kt")
s = runtime.read_text(encoding="utf-8")
s = s.replace(r"\s+", "[[:space:]]+")
runtime.write_text(s, encoding="utf-8")

# Recursive JSONObject builders need explicit return types for Kotlin inference.
auto = Path("app/src/main/java/com/astra/ai/AstraAutomationEngine.kt")
a = auto.read_text(encoding="utf-8")
a = a.replace('private fun nodeJson(n: Node) = JSONObject().apply', 'private fun nodeJson(n: Node): JSONObject = JSONObject().apply')
a = a.replace('private fun workflowJson(w: Workflow) = JSONObject().apply', 'private fun workflowJson(w: Workflow): JSONObject = JSONObject().apply')
auto.write_text(a, encoding="utf-8")

print("Latest Kotlin compile fixes applied.")
