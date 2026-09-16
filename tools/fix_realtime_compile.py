from pathlib import Path

p = Path("app/src/main/java/com/astra/ai/AstraAccessibilityService.kt")
s = p.read_text(encoding="utf-8")

if "fun longPress(x: Float" not in s:
    marker = "    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 350L): Boolean {"
    addition = '''    fun longPress(x: Float, y: Float, duration: Long = 650L): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(path, 0, duration.coerceIn(450, 1800))
            ).build(), null, null
        )
        if (ok) AstraRealtimeBus.publish(AstraRealtimeEvent.Gesture("long_press", x, y))
        return ok
    }

    fun doubleTap(x: Float, y: Float): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val first = GestureDescription.StrokeDescription(path, 0, 25)
        val second = GestureDescription.StrokeDescription(path, 90, 25)
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(first).addStroke(second).build(), null, null
        )
        if (ok) AstraRealtimeBus.publish(AstraRealtimeEvent.Gesture("double_tap", x, y))
        return ok
    }

'''
    if marker not in s:
        raise SystemExit("Accessibility swipe marker missing")
    s = s.replace(marker, addition + marker, 1)

p.write_text(s, encoding="utf-8")
print("Realtime gesture compile hardening applied without modifying AstraTooling.kt.")
