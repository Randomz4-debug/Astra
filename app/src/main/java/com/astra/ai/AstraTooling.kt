package com.astra.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
    fun maps(query: String): ToolResult = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult(true, "Opening Maps for $query.") }.getOrElse { ToolResult(false, "No maps application is available.") }
    fun call(number: String): ToolResult { val cleaned = PhoneNumberUtils.normalizeNumber(number); if (cleaned.isBlank()) return ToolResult(false, "I need a phone number to place the call."); return CallManager(context).placeCall(cleaned, confirmed = true) }
    fun filePicker(): ToolResult = runCatching { context.startActivity(Intent(context, AstraFilePickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult(true, "Opening the file picker. I’ll read the selected file and show it in Astra Chat.") }.getOrElse { ToolResult(false, "The file picker could not open: ${it.message}") }
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
    private val apps = AppManager(context); private val device = DeviceTools(context)
    val tools = listOf(ToolSpec("openApp", "Open an installed application."), ToolSpec("goHome", "Return to the launcher."), ToolSpec("openSettings", "Open Android Settings."), ToolSpec("openCamera", "Open Astra Camera."), ToolSpec("capturePhoto", "Open Astra Camera and capture a photo."), ToolSpec("openBrowser", "Open a web page."), ToolSpec("openMaps", "Open a location/search in Maps."), ToolSpec("call", "Place a phone call to a supplied number."), ToolSpec("pickFile", "Open the Android file picker and process the selected file."), ToolSpec("replyToLatestNotification", "Reply to the latest notification when it exposes a reply action.", true), ToolSpec("remember", "Store a user-requested local memory."), ToolSpec("forgetMemory", "Delete a user-requested local memory.", true), ToolSpec("clearMemory", "Delete all local Astra memory.", true))
    suspend fun execute(name: String, args: Map<String, String>, confirmed: Boolean = false): ToolResult = withContext(Dispatchers.Main) {
        val spec = tools.firstOrNull { it.name == name } ?: return@withContext ToolResult(false, "Unknown tool.")
        if (spec.requiresConfirmation && !confirmed) return@withContext ToolResult(false, "CONFIRMATION_REQUIRED")
        when (name) {
            "openApp" -> apps.open(args["name"].orEmpty())
            "goHome" -> device.home()
            "openSettings" -> device.settings()
            "openCamera" -> device.camera(front = args["front"] == "true")
            "capturePhoto" -> device.camera(front = args["front"] == "true", autoCapture = true)
            "openBrowser" -> device.browser(args["url"].orEmpty())
            "openMaps" -> device.maps(args["query"].orEmpty())
            "call" -> device.call(args["number"].orEmpty())
            "pickFile" -> device.filePicker()
            "replyToLatestNotification" -> { val latest = AstraNotificationListenerService.latest() ?: return@withContext ToolResult(false, "There are no available notifications."); AstraNotificationListenerService.current()?.reply(latest.key, args["message"].orEmpty()) ?: ToolResult(false, "Notification access is not enabled.") }
            else -> ToolResult(false, "Tool is not configured.")
        }
    }
}

class LocalCommandEngine(private val context: Context) {
    private val router = ToolRouter(context)
    private fun normalized(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ").removePrefix("please ").removePrefix("could you ").removePrefix("can you ").removePrefix("would you ").removePrefix("hey astra ").removePrefix("astra ").trim()

    suspend fun handle(text: String): ToolResult? {
        val t = text.trim(); val lower = normalized(t)
        if (lower == "custom commands" || lower == "open custom commands" || lower == "custom command settings" || lower == "manage custom commands") return runCatching { context.startActivity(Intent(context, AstraCustomCommandsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult(true, "Opening Custom Commands settings.") }.getOrElse { ToolResult(false, "Could not open Custom Commands settings: ${it.message}") }
        if (lower.contains("file") && (lower.contains("get") || lower.contains("pick") || lower.contains("choose") || lower.contains("attach") || lower.contains("upload") || lower.contains("select") || lower == "file")) return router.execute("pickFile", emptyMap())
        if (lower.contains("selfie") && (lower.contains("take") || lower.contains("capture") || lower.contains("photo") || lower.contains("picture") || lower.contains("pic"))) return router.execute("capturePhoto", mapOf("front" to "true"))
        if ((lower.contains("front camera") || lower.contains("front cam")) && (lower.contains("take") || lower.contains("capture") || lower.contains("photo") || lower.contains("picture") || lower.contains("pic") || lower.contains("snap"))) return router.execute("capturePhoto", mapOf("front" to "true"))
        if ((lower.contains("take") || lower.contains("capture") || lower.contains("snap") || lower.contains("click")) && (lower.contains("photo") || lower.contains("picture") || lower.contains("pic") || lower.contains("camera"))) return router.execute("capturePhoto", mapOf("front" to "false"))
        if (lower.contains("camera") && (lower.contains("open") || lower == "camera" || lower.contains("show"))) return router.execute("openCamera", emptyMap())
        val urlCandidate = when { lower.startsWith("http://") || lower.startsWith("https://") -> t; lower.startsWith("open url ") -> t.substring(9).trim(); lower.startsWith("open website ") -> t.substring(13).trim(); lower.startsWith("go to ") && t.substring(6).contains(".") -> t.substring(6).trim(); else -> "" }
        if (urlCandidate.isNotBlank()) return router.execute("openBrowser", mapOf("url" to urlCandidate))
        if (lower.startsWith("open browser") || lower.startsWith("browse to ")) return router.execute("openBrowser", mapOf("url" to t.substringAfter(" ").removePrefix("browser ").removePrefix("to ").trim()))
        if (lower.startsWith("open maps") || lower.startsWith("find on maps ") || lower.startsWith("navigate to ") || lower.startsWith("take me to ")) return router.execute("openMaps", mapOf("query" to t.substringAfter(" ").removePrefix("maps ").removePrefix("on maps ").removePrefix("to ").removePrefix("me to ").trim()))
        if (lower == "home" || lower.contains("go home") || lower.contains("take me home")) return router.execute("goHome", emptyMap())
        if (lower.contains("open settings") || lower == "settings") return router.execute("openSettings", emptyMap())
        if (lower.startsWith("call ") || lower.startsWith("dial ")) return router.execute("call", mapOf("number" to t.substringAfter(' ')), confirmed = true)
        val appVerbs = listOf("open ", "launch ", "start ", "run ", "play ")
        for (verb in appVerbs) if (lower.startsWith(verb)) return router.execute("openApp", mapOf("name" to t.substring(verb.length).trim().removePrefix("the ").removePrefix("my ").removePrefix("a ").removePrefix("an ").trim()))
        return null
    }
}
