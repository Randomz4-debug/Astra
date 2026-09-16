from pathlib import Path

P = Path("app/src/main/java/com/astra/ai/AstraTooling.kt")
s = P.read_text(encoding="utf-8")

# Add the Android timer import exactly once.
if "import android.provider.AlarmClock" not in s:
    marker = "import android.provider.Settings\n"
    if marker not in s:
        raise SystemExit("Settings import marker not found")
    s = s.replace(marker, marker + "import android.provider.AlarmClock\n", 1)

# Add the timer implementation inside DeviceTools, immediately before maps().
if "fun timer(seconds: Int, skipUi: Boolean = false)" not in s:
    marker = "    fun maps(query: String): ToolResult ="
    if marker not in s:
        raise SystemExit("DeviceTools maps() marker not found")
    method = '''    fun timer(seconds: Int, skipUi: Boolean = false): ToolResult {
        if (seconds <= 0) return ToolResult(false, "Timer duration must be greater than zero.")
        return runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(true, "Android timer started for ${seconds}s.")
        }.getOrElse { error ->
            ToolResult(false, "Android could not start the timer: ${error.message ?: "unknown error"}")
        }
    }
'''
    s = s.replace(marker, method + marker, 1)

# Register the tool exactly once.
spec = 'ToolSpec("openMaps", "Open a location/search in Maps."),'
if 'ToolSpec("setTimer",' not in s:
    if spec not in s:
        raise SystemExit("ToolSpec insertion marker not found")
    s = s.replace(spec, spec + '\n        ToolSpec("setTimer", "Start an Android system timer for a duration in seconds."),', 1)

# Route the tool exactly once.
route = '"openBrowser" -> device.browser(args["url"].orEmpty()); "openMaps" -> device.maps(args["query"].orEmpty()); "call" -> device.call(args["number"].orEmpty()); "pickFile" -> device.filePicker()'
if '"setTimer" -> device.timer' not in s:
    if route not in s:
        raise SystemExit("ToolRouter execution marker not found")
    replacement = '"openBrowser" -> device.browser(args["url"].orEmpty()); "openMaps" -> device.maps(args["query"].orEmpty()); "setTimer" -> device.timer(args["seconds"]?.toIntOrNull() ?: 0, args["skipUi"] == "true"); "call" -> device.call(args["number"].orEmpty()); "pickFile" -> device.filePicker()'
    s = s.replace(route, replacement, 1)

handle_marker = '    suspend fun handle(text: String): ToolResult? {'
if handle_marker not in s:
    raise SystemExit("LocalCommandEngine.handle() not found")

# Locate the handle function using brace counting. This avoids regex look-ahead
# accidentally consuming unrelated commands or closing the class incorrectly.
handle_start = s.index(handle_marker)
brace_start = s.index("{", handle_start)
depth = 0
handle_end = None
for i in range(brace_start, len(s)):
    ch = s[i]
    if ch == "{":
        depth += 1
    elif ch == "}":
        depth -= 1
        if depth == 0:
            handle_end = i + 1
            break
if handle_end is None:
    raise SystemExit("Could not find end of LocalCommandEngine.handle()")

handle = s[handle_start:handle_end]

# Remove any timer call previously injected into handle. Only lines between the
# exact timer marker and the next normal command are removed.
lines = handle.splitlines()
clean = []
skip = False
for line in lines:
    stripped = line.strip()
    if stripped.startswith("// ASTRA_TIMER_HARDENED"):
        skip = True
        continue
    if skip:
        if stripped.startswith('if (lower == "list apis"'):
            skip = False
            clean.append(line)
        continue
    if "val timerSeconds = parseTimerSeconds(t)" in line:
        continue
    clean.append(line)
handle = "\n".join(clean)

# Keep one parser as a class-level method. Remove duplicate generated copies if
# they exist, then insert one immediately before handle().
parser_signature = "    private fun parseTimerSeconds(text: String): Int? {"
while s.count(parser_signature) > 0:
    first = s.index(parser_signature)
    brace = s.index("{", first)
    depth = 0
    parser_end = None
    for i in range(brace, len(s)):
        if s[i] == "{":
            depth += 1
        elif s[i] == "}":
            depth -= 1
            if depth == 0:
                parser_end = i + 1
                break
    if parser_end is None:
        raise SystemExit("Could not find end of parseTimerSeconds()")
    s = s[:first] + s[parser_end:].lstrip("\n")

parser = '''    private fun parseTimerSeconds(text: String): Int? {
        val value = text.trim().lowercase()
        if (!value.contains("timer") && !value.contains("countdown")) return null
        val numberText = value.dropWhile { !it.isDigit() }
            .takeWhile { it.isDigit() || it == '.' }
        val number = numberText.toDoubleOrNull() ?: return null
        val seconds = when {
            value.contains("hour") || value.contains(" hr") || value.endsWith("h") -> (number * 3600.0).toInt()
            value.contains("minute") || value.contains(" min") || value.endsWith("m") -> (number * 60.0).toInt()
            else -> number.toInt()
        }
        return seconds.takeIf { it > 0 }
    }

'''
# Recompute handle location after parser cleanup.
handle_start = s.index(handle_marker)
s = s[:handle_start] + parser + s[handle_start:]

# Rebuild the handle slice after insertion and add one timer call immediately
# after the local t/lower declarations. t and lower therefore remain in scope.
handle_start = s.index(handle_marker)
brace_start = s.index("{", handle_start)
depth = 0
handle_end = None
for i in range(brace_start, len(s)):
    if s[i] == "{":
        depth += 1
    elif s[i] == "}":
        depth -= 1
        if depth == 0:
            handle_end = i + 1
            break
handle = s[handle_start:handle_end]
needle = "        val t = text.trim(); val lower = normalized(t)"
if needle not in handle:
    raise SystemExit("t/lower declaration not found inside handle()")
addition = '''
        val timerSeconds = parseTimerSeconds(t)
        if (timerSeconds != null) {
            return router.execute(
                "setTimer",
                mapOf("seconds" to timerSeconds.toString(), "skipUi" to "false")
            )
        }'''
if "val timerSeconds = parseTimerSeconds(t)" not in handle:
    pos = handle.index(needle) + len(needle)
    handle = handle[:pos] + addition + handle[pos:]
    s = s[:handle_start] + handle + s[handle_end:]

P.write_text(s, encoding="utf-8")
print("Timer upgrade generator completed safely.")
