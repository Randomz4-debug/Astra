from pathlib import Path

runtime = Path("app/src/main/java/com/astra/ai/AstraAgentRuntime.kt")
s = runtime.read_text(encoding="utf-8")
# The build-time agent patch introduces unsupported Kotlin backslash escapes.
# AstraAgentRuntime does not require any literal backslash characters, so strip
# them from this generated source before compilation. This also removes any
# harmless formatting-only escape sequences such as \n from generated prompts.
s = s.replace("\\", "")
runtime.write_text(s, encoding="utf-8")

auto = Path("app/src/main/java/com/astra/ai/AstraAutomationEngine.kt")
a = auto.read_text(encoding="utf-8")
a = a.replace('private fun nodeJson(n: Node) = JSONObject().apply', 'private fun nodeJson(n: Node): JSONObject = JSONObject().apply')
a = a.replace('private fun workflowJson(w: Workflow) = JSONObject().apply', 'private fun workflowJson(w: Workflow): JSONObject = JSONObject().apply')
auto.write_text(a, encoding="utf-8")

print("Latest Kotlin compile fixes applied.")
