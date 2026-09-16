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

# final_realtime_hardening.py still emits a legacy timer parser/call. Remove all
# generated timer parser/call fragments and then place exactly one stable call
# inside LocalCommandEngine.handle(), immediately after t/lower are declared.
p = ROOT / "AstraTooling.kt"
s = p.read_text(encoding="utf-8")

# Remove every legacy parser whose parameter is named input.
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
        raise SystemExit("Could not locate legacy timer parser end")
    s = s[:start] + s[end:].lstrip("\n")

handle_marker = "    suspend fun handle(text: String): ToolResult? {"
if handle_marker not in s:
    raise SystemExit("LocalCommandEngine.handle() missing")

# Locate handle with brace counting.
def function_bounds(source: str, marker: str):
    start = source.index(marker)
    brace = source.index("{", start)
    depth = 0
    for i in range(brace, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return start, i + 1
    raise SystemExit("Could not locate handle end")

handle_start, handle_end = function_bounds(s, handle_marker)
handle = s[handle_start:handle_end]

# Remove every generated timer declaration and its immediately following if block
# from the handle, regardless of the exact condition text used by old scripts.
while "val timerSeconds = parseTimerSeconds(t)" in handle:
    pos = handle.index("val timerSeconds = parseTimerSeconds(t)")
    line_start = handle.rfind("\n", 0, pos) + 1
    after_decl = handle.find("\n", pos)
    if after_decl < 0:
        after_decl = len(handle)
    cursor = after_decl
    while cursor < len(handle) and handle[cursor] in " \t\r\n":
        cursor += 1
    if handle.startswith("if (timerSeconds", cursor):
        open_brace = handle.find("{", cursor)
        if open_brace >= 0:
            depth = 0
            block_end = None
            for i in range(open_brace, len(handle)):
                if handle[i] == "{":
                    depth += 1
                elif handle[i] == "}":
                    depth -= 1
                    if depth == 0:
                        block_end = i + 1
                        break
            if block_end is None:
                raise SystemExit("Could not locate generated timer dispatch block")
            remove_end = block_end
        else:
            remove_end = after_decl
    else:
        remove_end = after_decl
    handle = handle[:line_start] + handle[remove_end:]

# Ensure exactly one class-level stable parser exists. Remove duplicate text-based
# parsers before inserting one canonical copy.
parser_sig = "    private fun parseTimerSeconds(text: String): Int? {"
while s.count(parser_sig) > 0:
    start = s.index(parser_sig)
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
        raise SystemExit("Could not locate timer parser end")
    s = s[:start] + s[end:].lstrip("\n")

parser = '''    private fun parseTimerSeconds(text: String): Int? {
        val value = text.trim().lowercase()
        if (!value.contains("timer") && !value.contains("countdown")) return null
        val numberText = value.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() || it == '.' }
        val number = numberText.toDoubleOrNull() ?: return null
        val seconds = when {
            value.contains("hour") || value.contains(" hr") || value.endsWith("h") -> (number * 3600.0).toInt()
            value.contains("minute") || value.contains(" min") || value.endsWith("m") -> (number * 60.0).toInt()
            else -> number.toInt()
        }
        return seconds.takeIf { it > 0 }
    }

'''
# Recompute handle position after parser cleanup and insert parser immediately before handle.
handle_start = s.index(handle_marker)
s = s[:handle_start] + parser + s[handle_start:]

# Recompute handle and insert exactly one call after the t/lower declaration.
handle_start, handle_end = function_bounds(s, handle_marker)
handle = s[handle_start:handle_end]
needle = "        val t = text.trim(); val lower = normalized(t)"
if needle not in handle:
    raise SystemExit("t/lower declaration missing inside handle")
addition = '''
        val timerSeconds = parseTimerSeconds(t)
        if (timerSeconds != null) {
            return router.execute("setTimer", mapOf("seconds" to timerSeconds.toString(), "skipUi" to "false"))
        }'''
pos = handle.index(needle) + len(needle)
handle = handle[:pos] + addition + handle[pos:]
s = s[:handle_start] + handle + s[handle_end:]

required = [
    "private fun parseTimerSeconds(text: String): Int?",
    "val timerSeconds = parseTimerSeconds(t)",
    '"setTimer" -> device.timer',
    "AlarmClock.ACTION_SET_TIMER",
]
for needle in required:
    if s.count(needle) == 0:
        raise SystemExit("Stable timer implementation missing: " + needle)

p.write_text(s, encoding="utf-8")

fix = Path("tools/fix_timer_compile.py")
if fix.exists():
    exec(compile(fix.read_text(encoding="utf-8"), str(fix), "exec"), {})

print("Realtime compile hardening applied; timer implementation normalized inside handle().")
