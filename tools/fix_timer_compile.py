from pathlib import Path
import re

p = Path("app/src/main/java/com/astra/ai/AstraTooling.kt")
s = p.read_text(encoding="utf-8")

# Android system timer support.
if "import android.provider.AlarmClock" not in s:
    s = s.replace("import android.provider.Settings\n", "import android.provider.Settings\nimport android.provider.AlarmClock\n")

# Deterministic timer execution: actually start the Android timer instead of only opening Clock.
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

# Hardened parser lives inside LocalCommandEngine.handle(), where t/lower are defined.
if "ASTRA_TIMER_HARDENED" not in s:
    marker = '        if (lower == "custom commands" || lower == "open custom commands"'
    i = s.find(marker)
    if i < 0:
        raise SystemExit("LocalCommandEngine insertion marker not found")
    block = '''        // ASTRA_TIMER_HARDENED: deterministic timer commands bypass the LLM.
        val timerSeconds = run {
            val compact = lower.replace(Regex("\\\\s*-\\\\s*"), "-")
            val hhmm = Regex("(?:timer|countdown).*?(\\\\d{1,2}):(\\\\d{2})").find(compact)
            if (hhmm != null) {
                (hhmm.groupValues[1].toInt() * 60 + hhmm.groupValues[2].toInt()).coerceAtLeast(1)
            } else {
                val m = Regex("(\\\\d+(?:\\\\.\\\\d+)?)\\\\s*(?:-|\\\\s)?\\\\s*(seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h)\\\\b").find(compact)
                if (m == null || (!compact.contains("timer") && !compact.contains("countdown"))) null
                else {
                    val amount = m.groupValues[1].toDoubleOrNull()
                    val unit = m.groupValues[2]
                    amount?.let {
                        when {
                            unit.startsWith("h") -> (it * 3600).toInt()
                            unit.startsWith("m") -> (it * 60).toInt()
                            else -> it.toInt()
                        }.takeIf { seconds -> seconds > 0 }
                    }
                }
            }
        }
        if (timerSeconds != null && (lower.contains("timer") || lower.contains("countdown"))) {
            return router.execute("setTimer", mapOf("seconds" to timerSeconds.toString(), "skipUi" to "false"))
        }

'''
    s = s[:i] + block + s[i:]

# Remove any previously injected broken timer block that references variables outside handle().
s = re.sub(
    r'\n\s*val timerSeconds = parseTimerSeconds\(t\).*?\n\s*\}\n',
    '\n',
    s,
    flags=re.S,
)

p.write_text(s, encoding="utf-8")
print("Timer compile/runtime fix applied.")
