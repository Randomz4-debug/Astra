from pathlib import Path

ROOT = Path('app/src/main/java/com/astra/ai')

# -----------------------------------------------------------------------------
# Real-time core: shared event bus, performance profiles, overlay primitives and
# a safe game-analysis state bus. These are deliberately independent of the LLM
# so latency-sensitive systems can run locally.
# -----------------------------------------------------------------------------
(ROOT / 'AstraRealtimeCore.kt').write_text(r'''package com.astra.ai

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/** Low-latency event backbone shared by perception, interaction and overlay systems. */
object AstraRealtimeBus {
    private val events = MutableSharedFlow<AstraRealtimeEvent>(extraBufferCapacity = 128)
    val stream: SharedFlow<AstraRealtimeEvent> = events.asSharedFlow()
    private val latest = ConcurrentHashMap<String, AstraRealtimeEvent>()

    fun publish(event: AstraRealtimeEvent) {
        latest[event.key] = event
        events.tryEmit(event)
    }

    fun latest(key: String): AstraRealtimeEvent? = latest[key]
}

sealed class AstraRealtimeEvent(val key: String, val timestamp: Long = SystemClock.uptimeMillis()) {
    data class ObjectDetected(val id: Long, val label: String, val confidence: Float, val left: Float, val top: Float, val right: Float, val bottom: Float) : AstraRealtimeEvent("object:$id")
    data class ScreenTarget(val id: Long, val label: String, val confidence: Float, val left: Float, val top: Float, val right: Float, val bottom: Float) : AstraRealtimeEvent("screen:$id")
    data class Gesture(val type: String, val x: Float, val y: Float) : AstraRealtimeEvent("gesture:$type")
    data class GameState(val state: Map<String, String>) : AstraRealtimeEvent("game:state")
}

enum class AstraPerformanceProfile { POWER_SAVE, BALANCED, PERFORMANCE, REALTIME }

object AstraPerformanceController {
    @Volatile var profile: AstraPerformanceProfile = AstraPerformanceProfile.PERFORMANCE
    @Volatile var targetRenderFps: Int = 60
    @Volatile var detectorIntervalMs: Long = 66L

    fun setProfile(value: AstraPerformanceProfile) {
        profile = value
        when (value) {
            AstraPerformanceProfile.POWER_SAVE -> { targetRenderFps = 30; detectorIntervalMs = 160 }
            AstraPerformanceProfile.BALANCED -> { targetRenderFps = 60; detectorIntervalMs = 100 }
            AstraPerformanceProfile.PERFORMANCE -> { targetRenderFps = 90; detectorIntervalMs = 66 }
            AstraPerformanceProfile.REALTIME -> { targetRenderFps = 120; detectorIntervalMs = 40 }
        }
    }
}

data class AstraOverlayShape(
    val type: Type,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val label: String = "",
    val confidence: Float = 0f
) {
    enum class Type { BOX, CIRCLE, LINE, ARROW, POINT, POLYGON, LABEL }
}

/** State bus for permitted game analysis/testing/recorded-gameplay integrations. */
object AstraGameAnalysisBus {
    private val values = ConcurrentHashMap<String, String>()
    fun put(key: String, value: String) { values[key] = value; AstraRealtimeBus.publish(AstraRealtimeEvent.GameState(values.toMap())) }
    fun get(key: String): String? = values[key]
    fun snapshot(): Map<String, String> = values.toMap()
    fun clear() = values.clear()
}

class AstraRealtimeScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
''', encoding='utf-8')

# -----------------------------------------------------------------------------
# Accessibility: faster taps, explicit long-press/double-tap, semantic scrolling,
# and realtime gesture events. Existing intelligent screen-map upgrades remain intact.
# -----------------------------------------------------------------------------
p = ROOT / 'AstraAccessibilityService.kt'
s = p.read_text(encoding='utf-8')
s = s.replace('StrokeDescription(path, 0, 80)', 'StrokeDescription(path, 0, 35)')
s = s.replace('duration.coerceIn(100, 2000)', 'duration.coerceIn(60, 1200)')

if 'fun longPress(' not in s:
    marker = '    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 350L): Boolean {'
    addition = '''    fun longPress(x: Float, y: Float, duration: Long = 650L): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val ok = dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceIn(450, 1800)))
                .build(), null, null
        )
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
        raise SystemExit('gesture marker missing')
    s = s.replace(marker, addition + marker)

# Publish tap/swipe events.
s = s.replace('''        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 35)).build(), null, null)
    }

    fun longPress''', '''        val ok = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 35)).build(), null, null)
        if (ok) AstraRealtimeBus.publish(AstraRealtimeEvent.Gesture("tap", x, y))
        return ok
    }

    fun longPress''')

# -----------------------------------------------------------------------------
# Tool layer: direct timer intent + long press/double tap. These bypass the LLM
# for deterministic, latency-sensitive operations.
# -----------------------------------------------------------------------------
p = ROOT / 'AstraTooling.kt'
s = p.read_text(encoding='utf-8')

if 'import android.provider.AlarmClock' not in s:
    s = s.replace('import android.provider.Settings\n', 'import android.provider.Settings\nimport android.provider.AlarmClock\n')

if 'fun timer(' not in s:
    marker = '    fun maps(query: String): ToolResult = '
    timer = '''    fun timer(seconds: Int, skipUi: Boolean = false): ToolResult {
        if (seconds <= 0) return ToolResult(false, "Timer duration must be greater than zero.")
        return runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(true, "Timer action dispatched for ${seconds}s.")
        }.getOrElse { ToolResult(false, "Android could not start the timer: ${it.message}") }
    }

'''
    if marker not in s: raise SystemExit('device marker missing')
    s = s.replace(marker, timer + marker)

if 'fun longPress(x:' not in s:
    marker = '    fun tap(x: Float, y: Float): ToolResult = '
    repl = '''    fun longPress(x: Float, y: Float, duration: Long = 650L): ToolResult = if (service()?.longPress(x, y, duration) == true) ToolResult(true, "Long-pressed screen coordinates $x, $y.") else ToolResult(false, "Long press is unavailable. Enable Accessibility Access.")
    fun doubleTap(x: Float, y: Float): ToolResult = if (service()?.doubleTap(x, y) == true) ToolResult(true, "Double-tapped screen coordinates $x, $y.") else ToolResult(false, "Double tap is unavailable. Enable Accessibility Access.")
'''
    if marker not in s: raise SystemExit('screen tap marker missing')
    s = s.replace(marker, repl + marker)

# Add tool specifications.
if 'ToolSpec("longPressScreen"' not in s:
    needle = 'ToolSpec("tapScreen", "Tap screen coordinates."),'
    s = s.replace(needle, needle + ' ToolSpec("longPressScreen", "Long-press screen coordinates."), ToolSpec("doubleTapScreen", "Double-tap screen coordinates."),')
if 'ToolSpec("setTimer"' not in s:
    needle = 'ToolSpec("call", "Place a phone call to a supplied number."),'
    s = s.replace(needle, needle + ' ToolSpec("setTimer", "Set/start an Android timer using the system Clock intent."),')

# Add execution cases.
if '"setTimer" -> device.timer' not in s:
    needle = '"openMaps" -> device.maps(args["query"].orEmpty()); "call" -> device.call(args["number"].orEmpty());'
    s = s.replace(needle, '"openMaps" -> device.maps(args["query"].orEmpty()); "setTimer" -> device.timer(args["seconds"]?.toIntOrNull() ?: 0, args["skipUi"] == "true"); "call" -> device.call(args["number"].orEmpty());')
if '"longPressScreen" -> screen.longPress' not in s:
    needle = '"swipeScreen" -> screen.swipe(args["x1"]?.toFloatOrNull() ?: 0f, args["y1"]?.toFloatOrNull() ?: 0f, args["x2"]?.toFloatOrNull() ?: 0f, args["y2"]?.toFloatOrNull() ?: 0f)'
    s = s.replace(needle, needle + '; "longPressScreen" -> screen.longPress(args["x"]?.toFloatOrNull() ?: 0f, args["y"]?.toFloatOrNull() ?: 0f, args["duration"]?.toLongOrNull() ?: 650L); "doubleTapScreen" -> screen.doubleTap(args["x"]?.toFloatOrNull() ?: 0f, args["y"]?.toFloatOrNull() ?: 0f)')

# Deterministic natural-language timer handling.
if 'setTimer' not in s[s.find('class LocalCommandEngine'):]:
    marker = '        if (lower == "list apis" || lower == "show apis" || lower == "what apis do you have") return router.execute("listApis", emptyMap())\n'
    timer_logic = '''        val timerMatch = Regex("(?:set|start|create|make)\\s+(?:a\\s+)?timer\\s+(?:for\\s+)?(\\d+(?:\\.\\d+)?)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)", RegexOption.IGNORE_CASE).find(t)
        if (timerMatch != null) {
            val amount = timerMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val unit = timerMatch.groupValues[2].lowercase()
            val seconds = when { unit.startsWith("hour") || unit.startsWith("hr") -> (amount * 3600).toInt(); unit.startsWith("min") -> (amount * 60).toInt(); else -> amount.toInt() }
            return router.execute("setTimer", mapOf("seconds" to seconds.toString(), "skipUi" to "false"))
        }
'''
    if marker not in s: raise SystemExit('command marker missing')
    s = s.replace(marker, marker + timer_logic)

p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# Make the final agent explicitly prefer the deterministic timer and gesture tools.
# -----------------------------------------------------------------------------
p = ROOT / 'AstraAgenticExecutor.kt'
s = p.read_text(encoding='utf-8')
if 'setTimer' not in s:
    s = s.replace('''5. After every navigation/action, inspect the new screen before deciding the next tap. Never reuse stale coordinates after the UI changes.
''', '''5. After every navigation/action, inspect the new screen before deciding the next tap. Never reuse stale coordinates after the UI changes.
6. For timer requests, prefer the `setTimer` tool instead of merely opening Clock. Verify the resulting Clock/timer state before reporting success.
7. For long-press/double-tap requests, use the dedicated gesture tools instead of simulating them with ordinary taps.
''')
    s = s.replace('''6. For reading a message:''', '''8. For reading a message:''')
    s = s.replace('''7. For WhatsApp contact requests:''', '''9. For WhatsApp contact requests:''')
    s = s.replace('''8. For timers/alarms and similar tasks,''', '''10. For timers/alarms and similar tasks,''')
    s = s.replace('''9. If a tool fails,''', '''11. If a tool fails,''')
    s = s.replace('''10. Never claim a login,''', '''12. Never claim a login,''')
    s = s.replace('''11. `say` is the final answer''', '''13. `say` is the final answer''')
    s = s.replace('''12. Never greet''', '''14. Never greet''')
    s = s.replace('''13. Do not expose''', '''15. Do not expose''')
p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# README: document the new real-time foundation.
# -----------------------------------------------------------------------------
readme = Path('README.md')
if readme.exists():
    text = readme.read_text(encoding='utf-8')
    section = '''\n## Real-time execution upgrade\n\nThe build now includes a latency-oriented realtime core, faster accessibility gestures, explicit long-press/double-tap tools, deterministic Android timer intents, and a shared event/state bus for perception, overlays and permitted game-analysis integrations. Latency-sensitive operations stay local instead of waiting for an LLM round trip.\n'''
    if '## Real-time execution upgrade' not in text:
        readme.write_text(text.rstrip() + section, encoding='utf-8')
