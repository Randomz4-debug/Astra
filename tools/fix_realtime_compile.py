from pathlib import Path

ROOT = Path("app/src/main/java/com/astra/ai")

# The realtime upgrade adds tool calls for long-press/double-tap. Make the
# accessibility implementation present even if an earlier upgrade script
# already changed the surrounding gesture code.
p = ROOT / "AstraAccessibilityService.kt"
s = p.read_text(encoding="utf-8")
if "fun longPress(x: Float" not in s:
    marker = "    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 350L): Boolean {"
    addition = '''    fun longPress(x: Float, y: Float, duration: Long = 650L): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val ok = dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceIn(450, 1800)))
                .build(), null, null
        if (ok) AstraRealtimeBus.publish(AstraRealtimeEvent.Gesture("long_press", x, y))
        return ok
    }

    fun doubleTap(x: Float, y: Float): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val first = GestureDescription.StrokeDescription(path, 0, 35)
        val second = GestureDescription.StrokeDescription(path, 110, 35)
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(first).addStroke(second).build(), null, null
        )
        if (ok) AstraRealtimeBus.publish(AstraRealtimeEvent.Gesture("double_tap", x, y))
        return ok
    }

'''
    if marker not in s:
        raise SystemExit("gesture marker missing")
    s = s.replace(marker, addition + marker)
p.write_text(s, encoding="utf-8")

# Previous timer injection used Python string escaping that produced invalid
# Kotlin escapes (\s, \d, etc.). Normalize the generated line to valid Kotlin.
p = ROOT / "AstraTooling.kt"
s = p.read_text(encoding="utf-8")
lines = s.splitlines()
for i, line in enumerate(lines):
    if "val timerMatch = Regex(" in line:
        lines[i] = r'''        val timerMatch = Regex("(?:set|start|create|make)\\s+(?:a\\s+)?timer\\s+(?:for\\s+)?(\\d+(?:\\.\\d+)?)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)", RegexOption.IGNORE_CASE).find(t)'''
        break
else:
    # If a previous build did not inject the timer matcher, add it before the
    # first deterministic screen command branch.
    marker = '        if (lower == "list apis" || lower == "show apis" || lower == "what apis do you have") return router.execute("listApis", emptyMap())'
    timer = r'''        val timerMatch = Regex("(?:set|start|create|make)\\s+(?:a\\s+)?timer\\s+(?:for\\s+)?(\\d+(?:\\.\\d+)?)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)", RegexOption.IGNORE_CASE).find(t)
        if (timerMatch != null) {
            val amount = timerMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val unit = timerMatch.groupValues[2].lowercase()
            val seconds = when {
                unit.startsWith("hour") || unit.startsWith("hr") -> (amount * 3600).toInt()
                unit.startsWith("min") -> (amount * 60).toInt()
                else -> amount.toInt()
            }
            return router.execute("setTimer", mapOf("seconds" to seconds.toString(), "skipUi" to "false"))
        }
'''
    if marker in s:
        s = s.replace(marker, marker + "\n" + timer)
s = "\n".join(lines) + "\n"
p.write_text(s, encoding="utf-8")

print("Realtime compile hardening applied.")
