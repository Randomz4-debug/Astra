from pathlib import Path
import re

# Normalize every backslash run before the regex whitespace token to exactly
# two backslashes, which is the correct representation inside a Kotlin string.
runtime = Path("app/src/main/java/com/astra/ai/AstraAgentRuntime.kt")
s = runtime.read_text(encoding="utf-8")
s = re.sub(r'\\+s', r'\\\\s', s)
runtime.write_text(s, encoding="utf-8")

# Give recursive JSON-builder functions explicit return types so Kotlin's
# type inference does not recurse through apply/forEach/nodeJson.
auto = Path("app/src/main/java/com/astra/ai/AstraAutomationEngine.kt")
s = auto.read_text(encoding="utf-8")
s = s.replace('private fun nodeJson(n: Node) = JSONObject().apply', 'private fun nodeJson(n: Node): JSONObject = JSONObject().apply')
s = s.replace('private fun workflowJson(w: Workflow) = JSONObject().apply', 'private fun workflowJson(w: Workflow): JSONObject = JSONObject().apply')
auto.write_text(s, encoding="utf-8")

print("Latest Kotlin compile fixes applied.")
