from pathlib import Path
import re

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

marker = '        if (lower == "custom commands" || lower == "open custom commands"'
start = s.find(marker)
if start < 0:
    raise SystemExit("LocalCommandEngine insertion marker not found")
handle_start = s.rfind('    suspend fun handle(text: String): ToolResult?', 0, start)
if handle_start < 0:
    raise SystemExit("LocalCommandEngine.handle() not found")

prefix = s[handle_start:start]
# Remove every previously generated timer parser in this handle block, including
# older variants that referenced out-of-scope t/lower variables.
prefix = re.sub(r'\n\s*// ASTRA_TIMER_HARDENED:.*?(?=\n\s*if \(lower == "custom commands")', '\n', prefix, flags=re.S)
prefix = re.sub(r'\n\s*val timerSeconds = run \{.*?(?=\n\s*if \(lower == "custom commands")', '\n', prefix, flags=re.S)
prefix = re.sub(r'\n\s*val timerSeconds = parseTimerSeconds\(t\).*?(?=\n\s*if \(lower == "custom commands")', '\n', prefix, flags=re.S)
# Remove any remaining generated timer assignment/return fragments from older runs.
prefix = re.sub(r'\n\s*if \(timerSeconds != null\) \{\s*\n\s*return router\.execute\("setTimer".*?\n\s*\}', '\n', prefix, flags=re.S)

simple_timer = '''
        // ASTRA_TIMER_HARDENED: deterministic timer parser.
        val astraTimerSeconds = run {
            if (!lower.contains("timer") && !lower.contains("countdown")) {
                null
            } else {
                val colon = lower.indexOf(':')
                if (colon > 0) {
                    val left = lower.substring(0, colon).takeLastWhile { it.isDigit() }.toIntOrNull() ?: 0
                    val right = lower.substring(colon + 1).takeWhile { it.isDigit() }.take(2).toIntOrNull() ?: 0
                    (left * 60 + right).takeIf { it > 0 }
                } else {
                    val amountText = lower.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() || it == '.' }
                    val amount = amountText.toDoubleOrNull()
                    amount?.let {
                        when {
                            lower.contains("hour") || lower.contains(" hr") || lower.endsWith("h") -> (it * 3600).toInt()
                            lower.contains("minute") || lower.contains(" min") || lower.endsWith("m") -> (it * 60).toInt()
                            else -> it.toInt()
                        }.takeIf { seconds -> seconds > 0 }
                    }
                }
            }
        }
        if (astraTimerSeconds != null) {
            return router.execute("setTimer", mapOf("seconds" to astraTimerSeconds.toString(), "skipUi" to "false"))
        }
'''

s = s[:handle_start] + prefix + simple_timer + s[start:]
p.write_text(s, encoding="utf-8")
print("Timer compile/runtime fix applied.")
