from pathlib import Path

P = Path("app/src/main/java/com/astra/ai/AstraTooling.kt")
s = P.read_text(encoding="utf-8")

required = {
    "import android.provider.AlarmClock": "AlarmClock import",
    "fun timer(seconds: Int, skipUi: Boolean = false)": "DeviceTools.timer",
    'ToolSpec("setTimer",': "setTimer tool specification",
    '"setTimer" -> device.timer': "setTimer router branch",
    "private fun parseTimerSeconds(text: String): Int?": "timer parser",
    "val timerSeconds = parseTimerSeconds(t)": "timer parser call",
}
missing = [label for needle, label in required.items() if needle not in s]
if missing:
    raise SystemExit("AstraTooling.kt is missing required timer code: " + ", ".join(missing))

# Timer code is now maintained directly in AstraTooling.kt. This script deliberately
# performs no source rewriting so repeated CI upgrade stages cannot create duplicate
# functions, out-of-scope variables, or malformed Kotlin escape sequences.
print("Timer implementation verified; no source rewrite performed.")
