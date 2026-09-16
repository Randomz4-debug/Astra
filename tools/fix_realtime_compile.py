from pathlib import Path
import re

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

# final_realtime_hardening.py adds an older timer parser/call after the stable
# timer implementation. Normalize that generated block here. Do not rewrite the
# stable DeviceTools timer or the stable class-level parser.
p = ROOT / "AstraTooling.kt"
s = p.read_text(encoding="utf-8")

# Remove the legacy parser by its unique parameter name.
legacy_parser = "    private fun parseTimerSeconds(input: String): Int? {"
while legacy_parser in s:
    start = s.index(legacy_parser)
    brace = s.index("{", start)
    depth = 0
    end = None
    for i in range(brace, len(s)):
        if s[i] == "{":
            depth += 1
        elif s[i] == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    if end is None:
        raise SystemExit("Could not locate end of legacy timer parser")
    s = s[:start] + s[end:].lstrip("\n")

# Remove the legacy timer dispatch block emitted immediately inside handle().
s = re.sub(
    r'\n\s*val timerSeconds = parseTimerSeconds\(t\)\n\s*if \(timerSeconds != null && \(lower\.contains\("set"\).*?\n\s*\}\n',
    "\n",
    s,
    flags=re.S,
)

# Remove any duplicate clean timer declaration, keeping the first one.
needle = "        val timerSeconds = parseTimerSeconds(t)"
first = s.find(needle)
if first >= 0:
    second = s.find(needle, first + len(needle))
    while second >= 0:
        line_start = s.rfind("\n", 0, second) + 1
        line_end = s.find("\n", second)
        if line_end < 0:
            line_end = len(s)
        s = s[:line_start] + s[line_end:]
        second = s.find(needle, first + len(needle))

# Ensure the stable timer parser and call still exist. If another upgrade stage
# removed them, fail loudly instead of generating a broken APK.
required = [
    "private fun parseTimerSeconds(text: String): Int?",
    "val timerSeconds = parseTimerSeconds(t)",
    '"setTimer" -> device.timer',
    "AlarmClock.ACTION_SET_TIMER",
]
missing = [x for x in required if x not in s]
if missing:
    raise SystemExit("Stable timer implementation missing after realtime normalization: " + ", ".join(missing))

p.write_text(s, encoding="utf-8")

# Ensure the deterministic timer verifier remains the final timer check.
fix = Path("tools/fix_timer_compile.py")
if fix.exists():
    exec(compile(fix.read_text(encoding="utf-8"), str(fix), "exec"), {})

print("Realtime compile hardening applied; timer symbols normalized.")
