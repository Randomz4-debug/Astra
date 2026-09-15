from pathlib import Path

ROOT = Path('app/src/main/java/com/astra/ai')

# -----------------------------------------------------------------------------
# Deterministic device execution: timers must execute, not merely open Clock.
# -----------------------------------------------------------------------------
p = ROOT / 'AstraTooling.kt'
s = p.read_text(encoding='utf-8')
if 'import android.provider.AlarmClock' not in s:
    s = s.replace('import android.provider.Settings\n', 'import android.provider.Settings\nimport android.provider.AlarmClock\n')

if 'fun timer(seconds: Int' not in s:
    marker = '    fun maps(query: String): ToolResult ='
    idx = s.find(marker)
    if idx < 0:
        raise SystemExit('AstraTooling: maps marker not found')
    timer = '''    fun timer(seconds: Int, skipUi: Boolean = false): ToolResult {
        if (seconds <= 0) return ToolResult(false, "Timer duration must be greater than zero.")
        return runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(true, "Android timer intent dispatched for ${seconds}s.")
        }.getOrElse { ToolResult(false, "Android could not start the timer: ${it.message ?: "unknown error"}") }
    }

'''
    s = s[:idx] + timer + s[idx:]

if 'ToolSpec("setTimer"' not in s:
    needle = 'ToolSpec("openMaps", "Open a location/search in Maps."),'
    s = s.replace(needle, needle + ' ToolSpec("setTimer", "Start an Android system timer for a duration in seconds."),')

if '"setTimer" -> device.timer' not in s:
    needle = '"openBrowser" -> device.browser(args["url"].orEmpty()); "openMaps" -> device.maps(args["query"].orEmpty()); "call" -> device.call(args["number"].orEmpty());'
    repl = '"openBrowser" -> device.browser(args["url"].orEmpty()); "openMaps" -> device.maps(args["query"].orEmpty()); "setTimer" -> device.timer(args["seconds"]?.toIntOrNull() ?: 0, args["skipUi"] == "true"); "call" -> device.call(args["number"].orEmpty());'
    if needle not in s:
        raise SystemExit('AstraTooling: execution marker not found')
    s = s.replace(needle, repl)

# Robust timer parser: accepts "5 second timer", "5-second timer", "timer for 5 seconds",
# "set a 5 second timer", "set timer to 00:05", etc.
if 'ASTRA_TIMER_HARDENED' not in s:
    marker = '    suspend fun handle(text: String): ToolResult? {'
    idx = s.find(marker)
    if idx < 0:
        raise SystemExit('AstraTooling: LocalCommandEngine handle marker not found')
    block = '''    // ASTRA_TIMER_HARDENED: deterministic timer commands bypass the LLM.
    private fun parseTimerSeconds(input: String): Int? {
        val t = input.lowercase().replace('–', '-').replace('—', '-').trim()
        val compact = t.replace(Regex("\\\\s*-\\\\s*"), "-")
        val hhmm = Regex("(?:timer|countdown).*?(\\\\d{1,2}):(\\\\d{2})").find(compact)
        if (hhmm != null) return (hhmm.groupValues[1].toInt() * 60 + hhmm.groupValues[2].toInt()).coerceAtLeast(1)
        val m = Regex("(\\\\d+(?:\\\\.\\\\d+)?)\\\\s*(?:-|\\\\s)?\\\\s*(seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h)\\\\b").find(compact)
        if (m == null || (!compact.contains("timer") && !compact.contains("countdown"))) return null
        val amount = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2]
        return when {
            unit.startsWith("h") -> (amount * 3600).toInt()
            unit.startsWith("m") -> (amount * 60).toInt()
            else -> amount.toInt()
        }.takeIf { it > 0 }
    }

'''
    s = s[:idx] + block + s[idx:]
    call = '''        val timerSeconds = parseTimerSeconds(t)
        if (timerSeconds != null && (lower.contains("set") || lower.contains("start") || lower.contains("make") || lower.contains("create") || lower.contains("timer") || lower.contains("countdown"))) {
            return router.execute("setTimer", mapOf("seconds" to timerSeconds.toString(), "skipUi" to "false"))
        }
'''
    s = s.replace(marker, marker + call)
p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# Faster accessibility gestures + richer screen map.
# -----------------------------------------------------------------------------
p = ROOT / 'AstraAccessibilityService.kt'
s = p.read_text(encoding='utf-8')
s = s.replace('StrokeDescription(path, 0, 80)', 'StrokeDescription(path, 0, 25)')
s = s.replace('duration.coerceIn(100, 2000)', 'duration.coerceIn(60, 1200)')
if 'fun longPress(' not in s:
    marker = '    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 350L): Boolean {'
    addition = '''    fun longPress(x: Float, y: Float, duration: Long = 650L): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, duration.coerceIn(450, 1800))
        ).build(), null, null)
        if (ok) AstraRealtimeBus.publish(AstraRealtimeEvent.Gesture("long_press", x, y))
        return ok
    }

    fun doubleTap(x: Float, y: Float): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val first = GestureDescription.StrokeDescription(path, 0, 25)
        val second = GestureDescription.StrokeDescription(path, 90, 25)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(first).addStroke(second).build(), null, null)
        if (ok) AstraRealtimeBus.publish(AstraRealtimeEvent.Gesture("double_tap", x, y))
        return ok
    }

'''
    if marker not in s: raise SystemExit('Accessibility swipe marker missing')
    s = s.replace(marker, addition + marker)

old_tap = '''        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 25)).build(), null, null)
    }
'''
if old_tap in s and 'AstraRealtimeEvent.Gesture("tap"' not in s:
    s = s.replace(old_tap, '''        val ok = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 25)).build(), null, null)
        if (ok) AstraRealtimeBus.publish(AstraRealtimeEvent.Gesture("tap", x, y))
        return ok
    }
''', 1)

if 'fun readInteractiveScreen(): String' not in s:
    marker = '    fun readScreen(): String = _screenText.value\n'
    addition = '''    fun readScreen(): String = _screenText.value

    fun readInteractiveScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val out = StringBuilder(); var count = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || count >= 220) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable) {
                val r = android.graphics.Rect(); node.getBoundsInScreen(r)
                if (!r.isEmpty && node.isVisibleToUser) {
                    out.append(count++).append(" | text=").append(text.take(120))
                        .append(" | desc=").append(desc.take(120))
                        .append(" | class=").append(node.className?.toString().orEmpty().take(80))
                        .append(" | clickable=").append(node.isClickable)
                        .append(" | editable=").append(node.isEditable)
                        .append(" | scrollable=").append(node.isScrollable)
                        .append(" | bounds=").append(r.left).append(',').append(r.top).append(',').append(r.right).append(',').append(r.bottom).append('\\n')
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root); return out.toString().take(24000)
    }

    fun activePackageName(): String = rootInActiveWindow?.packageName?.toString().orEmpty()
'''
    if marker not in s: raise SystemExit('Accessibility read marker missing')
    s = s.replace(marker, addition)

p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# Agent loop: remove unnecessary waits and use live UI evidence.
# -----------------------------------------------------------------------------
p = ROOT / 'AstraAgenticExecutor.kt'
s = p.read_text(encoding='utf-8')
s = s.replace('"openApp", "openBrowser", "openMaps", "openCamera" -> 1400L', '"openApp", "openBrowser", "openMaps", "openCamera" -> 450L')
s = s.replace('"clickScreen", "typeScreen", "tapScreen" -> 650L', '"clickScreen", "typeScreen", "tapScreen" -> 100L')
s = s.replace('"scrollScreen", "swipeScreen" -> 800L', '"scrollScreen", "swipeScreen" -> 120L')
s = s.replace('else -> 300L', 'else -> 100L')
if 'LIVE INTERACTIVE SCREEN MAP' not in s:
    needle = 'CURRENT VISIBLE SCREEN:\\n${screen.ifBlank { "(unavailable)" }}'
    repl = 'CURRENT VISIBLE SCREEN TEXT:\\n${screen.ifBlank { "(unavailable)" }}\\n\\nLIVE INTERACTIVE SCREEN MAP:\\n${AstraAccessibilityService.current()?.readInteractiveScreen().orEmpty().ifBlank { "(unavailable)" }}\\n\\nACTIVE APP PACKAGE:\\n${AstraAccessibilityService.current()?.activePackageName().orEmpty().ifBlank { "(unknown)" }}'
    s = s.replace(needle, repl)
if 'waitForUiChange(' not in s:
    marker = '    private fun screen(): String = AstraAccessibilityService.current()?.readScreen().orEmpty().trim().take(18000)\n'
    if marker in s:
        replacement = '''    private fun screen(): String = AstraAccessibilityService.current()?.readScreen().orEmpty().trim().take(18000)

    private suspend fun waitForUiChange(previous: String): String {
        repeat(18) {
            val current = screen()
            if (current.isNotBlank() && current != previous) return current
            delay(75)
        }
        return screen()
    }
'''
        s = s.replace(marker, replacement)
p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# Input translation: ensure it is actually used before command planning.
# -----------------------------------------------------------------------------
p = ROOT / 'AstraAgentRuntime.kt'
s = p.read_text(encoding='utf-8')
if 'private val promptTranslator' not in s:
    needle = '    private val connectionTools = AstraConnectionTools(appContext)\n'
    if needle in s: s = s.replace(needle, needle + '    private val promptTranslator = AstraPromptTranslationService(appContext)\n')
if 'val executionInput = runCatching { promptTranslator.translateForAstra(clean) }' not in s:
    needle = '        chats.append(chatId, "user", clean)\n'
    if needle in s:
        s = s.replace(needle, needle + '        val executionInput = runCatching { promptTranslator.translateForAstra(clean) }.getOrDefault(clean)\n', 1)
# Use normalized input for execution/planning while retaining original input in history.
s = s.replace('shouldUseAgenticExecutor(clean)', 'shouldUseAgenticExecutor(executionInput)')
s = s.replace('agenticExecutor.run(clean)', 'agenticExecutor.run(executionInput)')
s = s.replace('planner.plan(clean)', 'planner.plan(executionInput)')
p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# Realtime vision dependency + analyzer. This is an independent, local pipeline;
# it does not send camera frames to the LLM.
# -----------------------------------------------------------------------------
gradle = Path('app/build.gradle.kts')
g = gradle.read_text(encoding='utf-8')
if 'com.google.mlkit:object-detection:' not in g:
    g = g.replace('implementation("com.google.mlkit:text-recognition:16.0.1")', 'implementation("com.google.mlkit:text-recognition:16.0.1")\n    implementation("com.google.mlkit:object-detection:17.0.2")')
gradle.write_text(g, encoding='utf-8')

vision = ROOT / 'AstraRealtimeVisionAnalyzer.kt'
vision.write_text(r'''package com.astra.ai

import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fast local object detection/tracking bridge. Camera frames stay on-device and only
 * compact detection events are published to AstraRealtimeBus.
 */
class AstraRealtimeVisionAnalyzer(
    private val onShapes: (List<AstraOverlayShape>) -> Unit
) : ImageAnalysis.Analyzer {
    private val busy = AtomicBoolean(false)
    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )
    @Volatile private var lastRun = 0L

    override fun analyze(image: ImageProxy) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRun < AstraPerformanceController.detectorIntervalMs || !busy.compareAndSet(false, true)) {
            image.close(); return
        }
        lastRun = now
        val media = image.image
        if (media == null) { image.close(); busy.set(false); return }
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { objects ->
                val shapes = objects.mapIndexed { index, obj ->
                    val b = obj.boundingBox
                    val label = obj.labels.maxByOrNull { it.confidence }?.let { it.text } ?: "Object"
                    val confidence = obj.labels.maxByOrNull { it.confidence }?.confidence ?: 0f
                    AstraRealtimeBus.publish(AstraRealtimeEvent.ObjectDetected(index.toLong(), label, confidence, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()))
                    AstraOverlayShape(AstraOverlayShape.Type.BOX, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), label, confidence)
                }
                onShapes(shapes)
            }
            .addOnCompleteListener { image.close(); busy.set(false) }
    }

    fun close() { detector.close() }
}

class AstraOverlayView(context: android.content.Context) : android.view.View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    @Volatile private var shapes: List<AstraOverlayShape> = emptyList()

    fun setShapes(value: List<AstraOverlayShape>) { shapes = value; postInvalidateOnAnimation() }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = android.graphics.Color.rgb(110, 90, 255)
        val current = shapes
        for (s in current) {
            when (s.type) {
                AstraOverlayShape.Type.BOX -> canvas.drawRect(s.left, s.top, s.right, s.bottom, paint)
                AstraOverlayShape.Type.CIRCLE -> canvas.drawOval(s.left, s.top, s.right, s.bottom, paint)
                else -> Unit
            }
            if (s.label.isNotBlank()) {
                paint.style = android.graphics.Paint.Style.FILL
                paint.textSize = 28f
                canvas.drawText(if (s.confidence > 0f) "${s.label} ${(s.confidence * 100).toInt()}%" else s.label, s.left, (s.top - 10f).coerceAtLeast(30f), paint)
                paint.style = android.graphics.Paint.Style.STROKE
            }
        }
    }
}
''', encoding='utf-8')

print('Astra realtime hardening applied')
