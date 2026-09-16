from pathlib import Path

P = Path("app/src/main/java/com/astra/ai/AstraTooling.kt")
s = P.read_text(encoding="utf-8")

# Deterministic cleanup for the old timer generators. They could leave duplicate
# parsers, out-of-scope t/lower references, and malformed Kotlin regex escapes.
def remove_function(source: str, name: str) -> str:
    while True:
        marker = "    private fun " + name + "("
        start = source.find(marker)
        if start < 0:
            return source
        brace = source.find("{", start)
        if brace < 0:
            return source
        depth = 0
        i = brace
        while i < len(source):
            if source[i] == "{":
                depth += 1
            elif source[i] == "}":
                depth -= 1
                if depth == 0:
                    i += 1
                    if i < len(source) and source[i] == "\n":
                        i += 1
                    source = source[:start] + source[i:]
                    break
            i += 1
        else:
            return source

s = remove_function(s, "parseTimerSeconds")
s = remove_function(s, "parseTimerSecondsSafe")

handle_marker = "    suspend fun handle(text: String): ToolResult? {"
start = s.find(handle_marker)
if start < 0:
    raise SystemExit("LocalCommandEngine.handle() not found")
end = s.find("\n    }\n}", start)
if end < 0:
    raise SystemExit("LocalCommandEngine.handle() end not found")
handle = s[start:end]

# Remove any old inline timerMatch parser without touching other commands.
while "        val timerMatch = Regex(" in handle:
    p = handle.find("        val timerMatch = Regex(")
    q = handle.find("        if (timerMatch != null)", p)
    if q < 0:
        handle = handle[:p] + handle[handle.find("\n", p) + 1:]
        continue
    brace = handle.find("{", q)
    depth = 0
    i = brace
    while i < len(handle):
        if handle[i] == "{":
            depth += 1
        elif handle[i] == "}":
            depth -= 1
            if depth == 0:
                i += 1
                if i < len(handle) and handle[i] == "\n":
                    i += 1
                handle = handle[:p] + handle[i:]
                break
        i += 1
    else:
        raise SystemExit("Could not remove legacy timerMatch block")

# One parser, using no Kotlin backslash escapes.
parser = '''    private fun parseTimerSecondsSafe(text: String): Int? {
        val value = text.trim().lowercase()
        if (!value.contains("timer") && !value.contains("countdown")) return null
        var i = 0
        while (i < value.length && !value[i].isDigit()) i++
        if (i >= value.length) return null
        val begin = i
        var dotSeen = false
        while (i < value.length) {
            val c = value[i]
            if (c.isDigit()) i++
            else if (c == '.' && !dotSeen) { dotSeen = true; i++ }
            else break
        }
        val amount = value.substring(begin, i).toDoubleOrNull() ?: return null
        val seconds = when {
            value.contains("hour") || value.contains(" hr") || value.endsWith("h") -> (amount * 3600.0).toInt()
            value.contains("minute") || value.contains(" min") || value.endsWith("m") -> (amount * 60.0).toInt()
            else -> amount.toInt()
        }
        return seconds.takeIf { it > 0 }
    }

'''
insert_at = s.find(handle_marker)
s = s[:insert_at] + parser + s[insert_at:]

start = s.find(handle_marker)
end = s.find("\n    }\n}", start)
handle = s[start:end]
needle = "        val t = text.trim(); val lower = normalized(t)"
if needle not in handle:
    raise SystemExit("t/lower declaration not found")
call = '''
        val timerSecondsSafe = parseTimerSecondsSafe(t)
        if (timerSecondsSafe != null) {
            return router.execute("setTimer", mapOf("seconds" to timerSecondsSafe.toString(), "skipUi" to "false"))
        }'''
if "val timerSecondsSafe = parseTimerSecondsSafe(t)" not in handle:
    pos = handle.find(needle) + len(needle)
    handle = handle[:pos] + call + handle[pos:]
    s = s[:start] + handle + s[end:]

if "import android.provider.AlarmClock" not in s:
    s = s.replace("import android.provider.Settings\n", "import android.provider.Settings\nimport android.provider.AlarmClock\n", 1)
if "fun timer(seconds: Int, skipUi: Boolean = false)" not in s:
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
        }.getOrElse { error ->
            ToolResult(false, "Android could not start the timer: ${error.message ?: "unknown error"}")
        }
    }
'''
    s = s[:i] + method + s[i:]

if 'ToolSpec("setTimer",' not in s:
    marker = 'ToolSpec("openMaps", "Open a location/search in Maps."),'
    if marker not in s:
        raise SystemExit("ToolSpec insertion marker not found")
    s = s.replace(marker, marker + '\n        ToolSpec("setTimer", "Start an Android system timer for a duration in seconds."),', 1)

if '"setTimer" -> device.timer' not in s:
    marker = '"openBrowser" -> device.browser(args["url"].orEmpty()); "openMaps" -> device.maps(args["query"].orEmpty());'
    if marker not in s:
        raise SystemExit("ToolRouter execution marker not found")
    s = s.replace(marker, marker + ' "setTimer" -> device.timer(args["seconds"]?.toIntOrNull() ?: 0, args["skipUi"] == "true");', 1)

P.write_text(s, encoding="utf-8")
print("Timer source normalized: one scope-safe parser, one Android timer tool, no generated regex escapes.")
