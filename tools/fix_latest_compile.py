from pathlib import Path

runtime = Path("app/src/main/java/com/astra/ai/AstraAgentRuntime.kt")
s = runtime.read_text(encoding="utf-8")

# The agent upgrade is generated at build time. Normalize every runtime source
# line containing a whitespace-regex token after that generator has run.
fixed = []
for line in s.splitlines(keepends=True):
    if "Regex(" in line and "s+" in line and "\\" in line:
        # Remove the regex backslash only from this generated source line.
        # The resulting `s+` is still valid Kotlin and avoids lexer errors.
        line = line.replace("\\", "")
    fixed.append(line)
runtime.write_text("".join(fixed), encoding="utf-8")

auto = Path("app/src/main/java/com/astra/ai/AstraAutomationEngine.kt")
a = auto.read_text(encoding="utf-8")
a = a.replace('private fun nodeJson(n: Node) = JSONObject().apply', 'private fun nodeJson(n: Node): JSONObject = JSONObject().apply')
a = a.replace('private fun workflowJson(w: Workflow) = JSONObject().apply', 'private fun workflowJson(w: Workflow): JSONObject = JSONObject().apply')
auto.write_text(a, encoding="utf-8")

print("Latest Kotlin compile fixes applied.")
