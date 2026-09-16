from pathlib import Path

ROOT = Path("app/src/main/java/com/astra/ai")

p = ROOT / "AstraAccessibilityService.kt"
s = p.read_text(encoding="utf-8")
if "fun longPress(x: Float" not in s:
    marker = "    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 350L): Boolean {"
    addition = '''    fun longPress(x: Float, y: Float, duration: Long = 650L): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceIn(450, 1800))).build(), null, null)
        if (ok) AstraRealtimeBus.publish(AstraRealtimeEvent.Gesture("long_press", x, y))
        return ok
    }

    fun doubleTap(x: Float, y: Float): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val first = GestureDescription.StrokeDescription(path, 0, 35)
        val second = GestureDescription.StrokeDescription(path, 110, 35)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(first).addStroke(second).build(), null, null)
        if (ok) AstraRealtimeBus.publish(AstraRealtimeEvent.Gesture("double_tap", x, y))
        return ok
    }

'''
    if marker not in s:
        raise SystemExit("gesture marker missing")
    s = s.replace(marker, addition + marker)
p.write_text(s, encoding="utf-8")

# final_realtime_hardening.py historically injects a local timer parser and timer
# dispatch into AstraTooling.kt. Those generated fragments are now deliberately
# removed; timer execution is provided by DeviceTools + ToolRouter instead.
p = ROOT / "AstraTooling.kt"
s = p.read_text(encoding="utf-8")

# Remove every parseTimerSeconds function, regardless of the old parameter name.
def remove_functions(source: str, signatures):
    for signature in signatures:
        while signature in source:
            start = source.index(signature)
            brace = source.index("{", start)
            depth = 0
            end = None
            for i in range(brace, len(source)):
                if source[i] == "{":
                    depth += 1
                elif source[i] == "}":
                    depth -= 1
                    if depth == 0:
                        end = i + 1
                        break
            if end is None:
                raise SystemExit("Could not locate generated timer parser end")
            source = source[:start] + source[end:].lstrip("\n")
    return source

s = remove_functions(s, [
    "    private fun parseTimerSeconds(input: String): Int? {",
    "    private fun parseTimerSeconds(text: String): Int? {",
])

# Remove every generated timer local declaration and the if block that follows it.
# Since these declarations are no longer wanted anywhere, the cleanup is safe and
# avoids scope-sensitive regex look-ahead.
needle = "val timerSeconds = parseTimerSeconds(t)"
while needle in s:
    pos = s.index(needle)
    line_start = s.rfind("\n", 0, pos) + 1
    line_end = s.find("\n", pos)
    if line_end < 0:
        line_end = len(s)
    cursor = line_end
    while cursor < len(s) and s[cursor] in " \t\r\n":
        cursor += 1
    remove_end = line_end
    if s.startswith("if (timerSeconds", cursor):
        open_brace = s.find("{", cursor)
        if open_brace >= 0:
            depth = 0
            for i in range(open_brace, len(s)):
                if s[i] == "{":
                    depth += 1
                elif s[i] == "}":
                    depth -= 1
                    if depth == 0:
                        remove_end = i + 1
                        break
    s = s[:line_start] + s[remove_end:]

# Verify that timer execution itself remains intact. Do not require a parser here.
for required in [
    '"setTimer" -> device.timer',
    "AlarmClock.ACTION_SET_TIMER",
    "fun timer(seconds: Int, skipUi: Boolean = false)",
]:
    if required not in s:
        raise SystemExit("Stable timer tool missing after realtime cleanup: " + required)

p.write_text(s, encoding="utf-8")

fix = Path("tools/fix_timer_compile.py")
if fix.exists():
    exec(compile(fix.read_text(encoding="utf-8"), str(fix), "exec"), {})

print("Realtime compile hardening applied; generated local timer parser code removed.")
