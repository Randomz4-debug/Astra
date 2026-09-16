package com.astra.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.provider.AlarmClock
import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ToolSpec(val name: String, val description: String, val requiresConfirmation: Boolean = false)
data class ToolResult(val ok: Boolean, val message: String)

class AppManager(private val context: Context) {
    fun launchableApps(): Map<String, String> = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).associate { it.loadLabel(context.packageManager).toString() to it.activityInfo.packageName }
    fun open(name: String): ToolResult {
        val wanted = name.trim(); val apps = launchableApps()
        val match = apps.entries.firstOrNull { it.key.equals(wanted, true) } ?: apps.entries.firstOrNull { it.key.contains(wanted, true) || wanted.contains(it.key, true) } ?: return ToolResult(false, "I couldn't find an installed app named $wanted.")
        val launch = context.packageManager.getLaunchIntentForPackage(match.value) ?: return ToolResult(false, "That app cannot be launched.")
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult(true, "Opening ${match.key}.") }.getOrElse { ToolResult(false, "Android could not open ${match.key}: ${it.message}") }
    }
}

class DeviceTools(private val context: Context) {
    fun home(): ToolResult = runCatching { context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult(true, "Going home.") }.getOrElse { ToolResult(false, "Android could not return home: ${it.message}") }
    fun settings(): ToolResult = runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult(true, "Opening Settings.") }.getOrElse { ToolResult(false, "Android could not open Settings: ${it.message}") }
    fun camera(front: Boolean = false, autoCapture: Boolean = false): ToolResult = runCatching { context.startActivity(Intent(context, AstraCameraActivity::class.java).putExtra("front", front).putExtra("autoCapture", autoCapture).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult(true, if (autoCapture) "Opening the ${if (front) "front" else "back"} camera and taking the photo." else "Opening the ${if (front) "front" else "back"} camera.") }.getOrElse { ToolResult(false, "Astra Camera could not open: ${it.message}") }
    fun browser(url: String): ToolResult { val raw = url.trim(); if (raw.isBlank()) return ToolResult(false, "I need a URL to open."); val value = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"; return runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult(true, "Opening the web page.") }.getOrElse { ToolResult(false, "Android could not open that link.") } }
    fun timer(seconds: Int, skipUi: Boolean = false): ToolResult {
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
    fun maps(query: String): ToolResult = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult(true, "Opening Maps for $query.") }.getOrElse { ToolResult(false, "No maps application is available.") }
    fun call(number: String): ToolResult { val cleaned = PhoneNumberUtils.normalizeNumber(number); if (cleaned.isBlank()) return ToolResult(false, "I need a phone number to place the call."); return CallManager(context).placeCall(cleaned, confirmed = true) }
    fun filePicker(): ToolResult = runCatching { context.startActivity(Intent(context, AstraFilePickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult(true, "Opening the file picker. I’ll process the selected file and show it in Astra Chat.") }.getOrElse { ToolResult(false, "The file picker could not open: ${it.message}") }
}

class ScreenTools {
    private fun service(): AstraAccessibilityService? = AstraAccessibilityService.current()
    fun read(): ToolResult { val text = service()?.readScreen().orEmpty().trim(); return if (text.isBlank()) ToolResult(false, "Screen text is unavailable. Enable Astra Accessibility Access, then try again.") else ToolResult(true, text.take(12000)) }
    suspend fun readWithOcr(): ToolResult { val s = service() ?: return ToolResult(false, "Enable Astra Accessibility Access first."); val text = s.readScreenWithOcr().trim(); return if (text.isBlank()) ToolResult(false, "I could not read text from the current screen.") else ToolResult(true, text.take(12000)) }
    fun click(text: String): ToolResult = if (service()?.clickText(text) == true) ToolResult(true, "Clicked '$text'.") else ToolResult(false, "I could not find a clickable item matching '$text'.")
    fun type(text: String): ToolResult = if (service()?.typeText(text) == true) ToolResult(true, "Typed the requested text.") else ToolResult(false, "I could not find an editable field.")
    fun scroll(direction: String): ToolResult { val ok = if (direction.equals("up", true)) service()?.scrollBackward() == true else service()?.scrollForward() == true; return if (ok) ToolResult(true, "Scrolled $direction.") else ToolResult(false, "The current screen could not be scrolled.") }
    fun tap(x: Float, y: Float): ToolResult = if (service()?.tap(x, y) == true) ToolResult(true, "Tapped screen coordinates $x, $y.") else ToolResult(false, "Screen tapping is unavailable. Enable Accessibility Access.")
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): ToolResult = if (service()?.swipe(x1, y1, x2, y2) == true) ToolResult(true, "Swipe completed.") else ToolResult(false, "Screen gestures are unavailable. Enable Accessibility Access.")
    fun global(action: String): ToolResult {
        val s = service() ?: return ToolResult(false, "Enable Astra Accessibility Access first.")
        val ok = when (action.lowercase()) { "back" -> s.globalBack(); "home" -> s.globalHome(); "recents" -> s.globalRecents(); "notifications" -> s.openNotifications(); "quick settings", "quicksettings" -> s.openQuickSettings(); "screenshot" -> s.takeSystemScreenshot(); else -> false }
        return if (ok) ToolResult(true, "Done: $action.") else ToolResult(false, "Could not perform '$action'.")
    }
}

class MemoryManager(context: Context) {
    private val store = AstraMemoryStore(context)
    fun remember(key: String, value: String) = store.put(key, value)
    fun recall(key: String): String? = store.get(key)
    fun all(): Map<String, String> = store.all()
    fun forget(key: String) = store.remove(key)
    fun clear() = store.clear()
    fun storageBytes(): Long = store.bytes()
    fun cleanup() = store.cleanup()
}

class ToolRouter(private val context: Context) {
    private val apps = AppManager(context); private val device = DeviceTools(context); private val screen = ScreenTools(); private val api = AstraApiHub(context)
    val tools = listOf(
        ToolSpec("openApp", "Open an installed application."), ToolSpec("goHome", "Return to the launcher."), ToolSpec("openSettings", "Open Android Settings."),
        ToolSpec("openCamera", "Open Astra Camera."), ToolSpec("capturePhoto", "Open Astra Camera and capture a photo."), ToolSpec("openBrowser", "Open a web page."),
        ToolSpec("openMaps", "Open a location/search in Maps."), ToolSpec("setTimer", "Start an Android system timer for a duration in seconds."), ToolSpec("call", "Place a phone call to a supplied number."), ToolSpec("pickFile", "Open the Android file picker."),
        ToolSpec("readScreen", "Read the current app screen using accessibility text."), ToolSpec("readScreenOcr", "Read visible screen text using local OCR."),
        ToolSpec("clickScreen", "Click a visible screen control by text."), ToolSpec("typeScreen", "Type into the focused/editable screen field."), ToolSpec("scrollScreen", "Scroll the current screen."),
        ToolSpec("tapScreen", "Tap screen coordinates."), ToolSpec("swipeScreen", "Swipe screen coordinates."), ToolSpec("systemScreenAction", "Back, Home, Recents, Notifications, Quick Settings or screenshot."),
        ToolSpec("callApi", "Call any user-configured REST API."), ToolSpec("listApis", "List configured REST APIs."),
        ToolSpec("replyToLatestNotification", "Reply to the latest notification when it exposes a reply action.", true), ToolSpec("forgetMemory", "Delete a user-requested local memory.", true), ToolSpec("clearMemory", "Delete all local Astra memory.", true)
    )

    suspend fun execute(name: String, args: Map<String, String>, confirmed: Boolean = false): ToolResult = withContext(Dispatchers.Main) {
        when (name) {
            "openApp" -> apps.open(args["name"].orEmpty()); "goHome" -> device.home(); "openSettings" -> device.settings()
            "openCamera" -> device.camera(front = args["front"] == "true"); "capturePhoto" -> device.camera(front = args["front"] == "true", autoCapture = true)
            "openBrowser" -> device.browser(args["url"].orEmpty()); "openMaps" -> device.maps(args["query"].orEmpty()); "setTimer" -> device.timer(args["seconds"]?.toIntOrNull() ?: 0, args["skipUi"] == "true"); "call" -> device.call(args["number"].orEmpty()); "pickFile" -> device.filePicker()
            "readScreen" -> screen.read(); "readScreenOcr" -> screen.readWithOcr(); "clickScreen" -> screen.click(args["text"].orEmpty()); "typeScreen" -> screen.type(args["text"].orEmpty())
            "scrollScreen" -> screen.scroll(args["direction"].orEmpty().ifBlank { "down" }); "tapScreen" -> screen.tap(args["x"]?.toFloatOrNull() ?: 0f, args["y"]?.toFloatOrNull() ?: 0f)
            "swipeScreen" -> screen.swipe(args["x1"]?.toFloatOrNull() ?: 0f, args["y1"]?.toFloatOrNull() ?: 0f, args["x2"]?.toFloatOrNull() ?: 0f, args["y2"]?.toFloatOrNull() ?: 0f)
            "systemScreenAction" -> screen.global(args["action"].orEmpty()); "listApis" -> ToolResult(true, api.catalog())
            "callApi" -> {
                val result = api.execute(args["name"].orEmpty(), args["path"], args["method"], args["body"])
                if (result.ok) ToolResult(true, "HTTP ${result.status}\n${result.body}") else ToolResult(false, "API request failed${if (result.status > 0) " (HTTP ${result.status})" else ""}: ${result.error}")
            }
            "replyToLatestNotification" -> { if (!confirmed) return@withContext ToolResult(false, "CONFIRMATION_REQUIRED"); val latest = AstraNotificationListenerService.latest() ?: return@withContext ToolResult(false, "There are no available notifications."); AstraNotificationListenerService.current()?.reply(latest.key, args["message"].orEmpty()) ?: ToolResult(false, "Notification access is not enabled.") }
            else -> ToolResult(false, "Tool is not configured.")
        }
    }

    fun apiCatalog(): String = api.catalog()
}

class LocalCommandEngine(private val context: Context) {
    private val router = ToolRouter(context)
    private val api = AstraApiHub(context)
    private fun normalized(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ").removePrefix("please ").removePrefix("could you ").removePrefix("can you ").removePrefix("would you ").removePrefix("hey astra ").removePrefix("astra ").trim()

    private fun parseTimerSeconds(text: String): Int? {
        val value = text.trim().lowercase()
        if (!value.contains("timer") && !value.contains("countdown")) return null
        val numberText = value.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() || it == '.' }
        val number = numberText.toDoubleOrNull() ?: return null
        val seconds = when {
            value.contains("hour") || value.contains(" hr") || value.endsWith("h") -> (number * 3600.0).toInt()
            value.contains("minute") || value.contains(" min") || value.endsWith("m") -> (number * 60.0).toInt()
            else -> number.toInt()
        }
        return seconds.takeIf { it > 0 }
    }

    suspend fun handle(text: String): ToolResult? {
        val t = text.trim(); val lower = normalized(t)
        val timerSeconds = parseTimerSeconds(t)
        if (timerSeconds != null) return router.execute("setTimer", mapOf("seconds" to timerSeconds.toString(), "skipUi" to "false"))
        if (lower == "list apis" || lower == "show apis" || lower == "what apis do you have") return router.execute("listApis", emptyMap())
        val apiHit = api.all().firstOrNull { lower.contains(it.name.lowercase()) && it.enabled }
        if (apiHit != null && (lower.contains("api") || lower.contains("request") || lower.contains("fetch") || lower.contains("get ") || lower.contains("post ") || lower.contains("send "))) {
            val method = when { lower.contains(" post ") || lower.startsWith("post ") || lower.contains(" send ") -> "POST"; lower.contains(" put ") -> "PUT"; lower.contains(" patch ") -> "PATCH"; lower.contains(" delete ") -> "DELETE"; else -> apiHit.method }
            val after = t.substringAfter(apiHit.name, "").trim()
            val explicitPath = Regex("(?:https?://\\S+|/\\S+)").find(after)?.value
            return router.execute("callApi", mapOf("name" to apiHit.name, "method" to method, "path" to (explicitPath ?: apiHit.defaultPath)))
        }
        if (lower == "custom commands" || lower == "open custom commands" || lower == "custom command settings" || lower == "manage custom commands") return runCatching { context.startActivity(Intent(context, AstraCustomCommandsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult(true, "Opening Custom Commands settings.") }.getOrElse { ToolResult(false, "Could not open Custom Commands settings: ${it.message}") }
        if (lower.contains("read") && lower.contains("screen") || lower.contains("what is on my screen") || lower == "screen" || lower == "read screen") return router.execute("readScreenOcr", emptyMap())
        if (lower.startsWith("click ") || lower.startsWith("tap ")) return router.execute("clickScreen", mapOf("text" to t.substringAfter(' ').trim()))
        if (lower.startsWith("type ") || lower.startsWith("enter ")) return router.execute("typeScreen", mapOf("text" to t.substringAfter(' ').trim()))
        if (lower.contains("scroll up")) return router.execute("scrollScreen", mapOf("direction" to "up"))
        if (lower.contains("scroll down") || lower == "scroll") return router.execute("scrollScreen", mapOf("direction" to "down"))
        if (lower == "go back" || lower == "back") return router.execute("systemScreenAction", mapOf("action" to "back"))
        if (lower == "go home" || lower == "home") return router.execute("systemScreenAction", mapOf("action" to "home"))
        if (lower.contains("recent apps") || lower == "recents") return router.execute("systemScreenAction", mapOf("action" to "recents"))
        if (lower.contains("notifications")) return router.execute("systemScreenAction", mapOf("action" to "notifications"))
        if (lower.contains("quick settings")) return router.execute("systemScreenAction", mapOf("action" to "quick settings"))
        if (lower == "take screenshot" || lower == "screenshot" || lower.contains("capture screen")) return router.execute("systemScreenAction", mapOf("action" to "screenshot"))
        if (lower.contains("file") && (lower.contains("get") || lower.contains("pick") || lower.contains("choose") || lower.contains("attach") || lower.contains("upload") || lower.contains("select") || lower == "file")) return router.execute("pickFile", emptyMap())
        if (lower.contains("selfie") && (lower.contains("take") || lower.contains("capture") || lower.contains("photo") || lower.contains("picture") || lower.contains("pic"))) return router.execute("capturePhoto", mapOf("front" to "true"))
        if ((lower.contains("front camera") || lower.contains("front cam")) && (lower.contains("take") || lower.contains("capture") || lower.contains("photo") || lower.contains("picture") || lower.contains("pic") || lower.contains("snap"))) return router.execute("capturePhoto", mapOf("front" to "true"))
        if ((lower.contains("take") || lower.contains("capture") || lower.contains("snap") || lower.contains("click")) && (lower.contains("photo") || lower.contains("picture") || lower.contains("pic") || lower.contains("camera"))) return router.execute("capturePhoto", mapOf("front" to "false"))
        if (lower.contains("camera") && (lower.contains("open") || lower == "camera" || lower.contains("show"))) return router.execute("openCamera", emptyMap())
        val urlCandidate = when { lower.startsWith("http://") || lower.startsWith("https://") -> t; lower.startsWith("open url ") -> t.substring(9).trim(); lower.startsWith("open website ") -> t.substring(13).trim(); lower.startsWith("go to ") && t.substring(6).contains(".") -> t.substring(6).trim(); else -> "" }
        if (urlCandidate.isNotBlank()) return router.execute("openBrowser", mapOf("url" to urlCandidate))
        if (lower.startsWith("open browser") || lower.startsWith("browse to ")) return router.execute("openBrowser", mapOf("url" to t.substringAfter(" ").removePrefix("browser ").removePrefix("to ").trim()))
        if (lower.startsWith("open maps") || lower.startsWith("find on maps ") || lower.startsWith("navigate to ") || lower.startsWith("take me to ")) return router.execute("openMaps", mapOf("query" to t.substringAfter(" ").removePrefix("maps ").removePrefix("on maps ").removePrefix("to ").removePrefix("me to ").trim()))
        if (lower.contains("open settings") || lower == "settings") return router.execute("openSettings", emptyMap())
        if (lower.startsWith("call ") || lower.startsWith("dial ")) return router.execute("call", mapOf("number" to t.substringAfter(' ')), confirmed = true)
        val appVerbs = listOf("open ", "launch ", "start ", "run ", "play ")
        for (verb in appVerbs) if (lower.startsWith(verb)) return router.execute("openApp", mapOf("name" to t.substring(verb.length).trim().removePrefix("the ").removePrefix("my ").removePrefix("a ").removePrefix("an ").trim()))
        return null
    }
}
