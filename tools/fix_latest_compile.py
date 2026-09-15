from pathlib import Path
import re

runtime = Path("app/src/main/java/com/astra/ai/AstraAgentRuntime.kt")
s = runtime.read_text(encoding="utf-8")
# Replace one-or-more literal backslashes before s+ with a regex class that
# needs no Kotlin string escaping. This handles both generated \s+ and \\s+.
s = re.sub(r"\\+s\+", "[[:space:]]+", s)
runtime.write_text(s, encoding="utf-8")

auto = Path("app/src/main/java/com/astra/ai/AstraAutomationEngine.kt")
a = auto.read_text(encoding="utf-8")
a = a.replace('private fun nodeJson(n: Node) = JSONObject().apply', 'private fun nodeJson(n: Node): JSONObject = JSONObject().apply')
a = a.replace('private fun workflowJson(w: Workflow) = JSONObject().apply', 'private fun workflowJson(w: Workflow): JSONObject = JSONObject().apply')
auto.write_text(a, encoding="utf-8")

print("Latest Kotlin compile fixes applied.")
