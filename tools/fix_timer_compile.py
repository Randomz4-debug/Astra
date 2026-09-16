from pathlib import Path

P = Path("app/src/main/java/com/astra/ai/AstraTooling.kt")
s = P.read_text(encoding="utf-8")

# This script is intentionally idempotent. The Kotlin source is the source of
# truth; never inject timer code into arbitrary regions of handle().
if "import android.provider.AlarmClock" not in s:
    s = s.replace("import android.provider.Settings\n", "import android.provider.Settings\nimport android.provider.AlarmClock\n", 1)

maps_marker = "    fun maps(query: String): ToolResult ="
if "fun timer(seconds: Int, skipUi: Boolean = false)" not in s:
    i = s.find(maps_marker)
    if i < 0:
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
    s = s[:i] + method + s[i:]

spec_marker = 'ToolSpec("openMaps", "Open a location/search in Maps."),'
if 'ToolSpec("setTimer",' not in s:
    if spec_marker not in s:
        raise SystemExit("ToolSpec insertion marker not found")
    s = s.replace(spec_marker, spec_marker + '\n        ToolSpec("setTimer", "Start an Android system timer for a duration in seconds."),', 1)

router_marker = '"openBrowser" -> device.browser(args["url"].orEmpty()); "openMaps" -> device.maps(args["query"].orEmpty()); "call" -> device.call(args["number"].orEmpty()); "pickFile" -> device.filePicker()'
if '"setTimer" -> device.timer' not in s:
    if router_marker not in s:
        raise SystemExit("ToolRouter execution marker not found")
    replacement = '"openBrowser" -> device.browser(args["url"].orEmpty()); "openMaps" -> device.maps(args["query"].orEmpty()); "setTimer" -> device.timer(args["seconds"]?.toIntOrNull() ?: 0, args["skipUi"] == "true"); "call" -> device.call(args["number"].orEmpty()); "pickFile" -> device.filePicker()'
    s = s.replace(router_marker, replacement, 1)

handle_marker = '    suspend fun handle(text: String): ToolResult? {'
if handle_marker not in s:
    raise SystemExit("LocalCommandEngine.handle() not found")

# Remove only the known generated inline timer blocks from older versions.
# Do not use a broad look-ahead regex that can consume unrelated commands.
start = s.find(handle_marker)
end = s.find('\n    }\n}', start)
if end < 0:
    raise SystemExit("LocalCommandEngine.handle() end not found")
handle = s[start:end]

import re
handle = re.sub(r'\n\s*// ASTRA_TIMER_HARDENED:.*?(?=\n\s*if \(lower == "list apis"|\n\s*if \(lower == "custom commands")', '', handle, flags=re.S)
handle = re.sub(r'\n\s*val (?:astra)?TimerSeconds\s*=.*?(?=\n\s*if \(lower == "list apis"|\n\s*if \(lower == "custom commands")', '', handle, flags=re.S)
handle = re.sub(r'\n\s*if \((?:astra)?TimerSeconds != null\)\s*\{.*?\n\s*\}', '', handle, flags=re.S)

# Ensure exactly one parser exists as a class method, outside handle().
parser_name = '    private fun parseTimerSeconds(text: String): Int? {'
if parser_name not in s:
    insert_at = s.find(handle_marker)
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
    s = s[:insert_at] + parser + s[insert_at:]

# Re-read the handle after parser insertion and insert one safe call after the
# local t/lower declarations. This is the only timer call injected by this file.
start = s.find(handle_marker)
end = s.find('\n    }\n}', start)
handle = s[start:end]
needle = '        val t = text.trim(); val lower = normalized(t)'
if needle not in handle:
    raise SystemExit("t/lower declaration not found")

if 'val timerSeconds = parseTimerSeconds(t)' not in handle:
    addition = '''
        val timerSeconds = parseTimerSeconds(t)
        if (timerSeconds != null) {
            return router.execute(
                "setTimer",
                mapOf("seconds" to timerSeconds.toString(), "skipUi" to "false")
            )
        }'''
    pos = handle.find(needle) + len(needle)
    handle = handle[:pos] + addition + handle[pos:]
    s = s[:start] + handle + s[end:]
else:
    s = s[:start] + handle + s[end:]

P.write_text(s, encoding="utf-8")
print("Timer upgrade generator completed safely.")
