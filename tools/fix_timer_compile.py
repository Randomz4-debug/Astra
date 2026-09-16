from pathlib import Path

p = Path("app/src/main/java/com/astra/ai/AstraTooling.kt")
s = p.read_text(encoding="utf-8")

if "import android.provider.AlarmClock" not in s:
    s = s.replace("import android.provider.Settings\n", "import android.provider.Settings\nimport android.provider.AlarmClock\n")

if "fun timer(seconds: Int" not in s:
    marker = "    fun maps(query: String): ToolResult ="
    i = s.find(marker)
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
        }.getOrElse { ToolResult(false, "Android could not start the timer: ${it.message ?: "unknown error"}") }
    }
'''
    s = s[:i] + method + s[i:]

if 'ToolSpec("setTimer"' not in s:
    needle = 'ToolSpec("openMaps", "Open a location/search in Maps."),'
    if needle not in s:
        raise SystemExit("ToolSpec insertion marker not found")
    s = s.replace(needle, needle + '\n        ToolSpec("setTimer", "Start an Android system timer for a duration in seconds."),')

if '"setTimer" -> device.timer' not in s:
    needle = '"openBrowser" -> device.browser(args["url"].orEmpty()); "openMaps" -> device.maps(args["query"].orEmpty()); "call" -> device.call(args["number"].orEmpty()); "pickFile" -> device.filePicker()'
    repl = '"openBrowser" -> device.browser(args["url"].orEmpty()); "openMaps" -> device.maps(args["query"].orEmpty()); "setTimer" -> device.timer(args["seconds"]?.toIntOrNull() ?: 0, args["skipUi"] == "true"); "call" -> device.call(args["number"].orEmpty()); "pickFile" -> device.filePicker()'
    if needle not in s:
        raise SystemExit("ToolRouter execution marker not found")
    s = s.replace(needle, repl)

# Remove any timer parser injected by previous upgrade scripts between the API
# handling and the custom-command branch. Keep the normal t/lower declarations.
marker = '        if (lower == "custom commands" || lower == "open custom commands"'
start = s.find(marker)
if start < 0:
    raise SystemExit("LocalCommandEngine custom-command marker not found")
handle_start = s.rfind('    suspend fun handle(text: String): ToolResult?', 0, start)
if handle_start < 0:
    raise SystemExit("LocalCommandEngine.handle() not found")

prefix = s[handle_start:start]
# Delete every generated timer declaration/return block in this prefix. This is
# intentionally broad so old broken variants cannot survive another build.
import re
prefix = re.sub(r'\n\s*(?:// ASTRA_TIMER_HARDENED:.*?\n)?\s*val timerSeconds\s*=.*?(?=\n\s*if \(lower == "custom commands")', '\n', prefix, flags=re.S)
prefix = re.sub(r'\n\s*if \(timerSeconds != null\)\s*\{.*?\n\s*\}', '\n', prefix, flags=re.S)
prefix = re.sub(r'\n\s*if \(astraTimerSeconds != null\)\s*\{.*?\n\s*\}', '\n', prefix, flags=re.S)

# Add one stable parser as a class method. It uses no regex, so generated Kotlin
# cannot contain invalid backslash escapes.
if 'private fun parseTimerSeconds(text: String): Int?' not in s:
    insert_at = s.find('    suspend fun handle(text: String): ToolResult?')
    if insert_at < 0:
        raise SystemExit("handle method not found for parser insertion")
    parser = '''    private fun parseTimerSeconds(text: String): Int? {
        val value = text.trim().lowercase()
        if (!value.contains("timer") && !value.contains("countdown")) return null
        val number = value.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return null
        val seconds = when {
            value.contains("hour") || value.contains(" hr") || value.endsWith("h") -> (number * 3600).toInt()
            value.contains("minute") || value.contains(" min") || value.endsWith("m") -> (number * 60).toInt()
            else -> number.toInt()
        }
        return seconds.takeIf { it > 0 }
    }

'''
    s = s[:insert_at] + parser + s[insert_at:]

# Recompute positions after parser insertion and put the stable call immediately
# after t/lower, before other command branches.
handle_start = s.find('    suspend fun handle(text: String): ToolResult?')
body_start = s.find('{', handle_start)
needle = '        val t = text.trim(); val lower = normalized(t)'
line_start = s.find(needle, body_start)
if line_start < 0:
    raise SystemExit("t/lower declaration not found")
line_end = line_start + len(needle)
call = '''
        val timerSeconds = parseTimerSeconds(t)
        if (timerSeconds != null) {
            return router.execute("setTimer", mapOf("seconds" to timerSeconds.toString(), "skipUi" to "false"))
        }'''
# Avoid duplicate stable insertion.
if 'val timerSeconds = parseTimerSeconds(t)' not in s:
    s = s[:line_end] + call + s[line_end:]

p.write_text(s, encoding="utf-8")
print("Timer parser generation hardened.")
