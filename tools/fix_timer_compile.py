from pathlib import Path

P = Path("app/src/main/java/com/astra/ai/AstraTooling.kt")
s = P.read_text(encoding="utf-8")

required = {
    "import android.provider.AlarmClock": "AlarmClock import",
    "fun timer(seconds: Int, skipUi: Boolean = false)": "DeviceTools.timer",
    'ToolSpec("setTimer",': "setTimer tool specification",
    '"setTimer" -> device.timer': "setTimer router branch",
    "AlarmClock.ACTION_SET_TIMER": "Android timer intent",
}
missing = [label for needle, label in required.items() if needle not in s]
if missing:
    raise SystemExit("AstraTooling.kt is missing required timer code: " + ", ".join(missing))

# The timer is executed through the stable ToolRouter/DeviceTools path. Earlier
# versions tried to inject a local natural-language parser into LocalCommandEngine;
# that generated duplicate functions and out-of-scope variables. No parser source
# rewriting is allowed here anymore.
print("Stable timer tool verified; no timer parser source rewrite performed.")
