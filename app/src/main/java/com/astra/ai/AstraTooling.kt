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
        val wanted = name.trim()
        val apps = launchableApps()
        val match = apps.entries.firstOrNull { it.key.equals(wanted, true) }
            ?: apps.entries.firstOrNull { it.key.contains(wanted, true) || wanted.contains(it.key, true) }
            ?: return ToolResult(false, "I couldn't find an installed app named $wanted.")
        val launch = context.packageManager.getLaunchIntentForPackage(match.value) ?: return ToolResult(false, "That app cannot be launched.")
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult(true, "Opening ${match.key}.") }.getOrElse { ToolResult(false, "Android could not open ${match.key}: ${it.message}") }
    }
}

class DeviceTools(private val context: Context) {
    fun home(): ToolResult = runCatching { context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult(true, "Going home.") }.getOrElse { ToolResult(false, "Android could not return home: ${it.message}") }
    fun settings(): ToolResult = runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult(true, "Opening Settings.") }.getOrElse { ToolResult(false, "Android could not open Settings: ${it.message}") }
    fun camera(): ToolResult = runCatching { context.startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult(true, "Opening the camera so you can take a picture.") }.getOrElse { ToolResult(false, "No camera app is available.") }
    fun browser(url: String): ToolResult {
        val raw = url.trim()
        if (raw.isBlank()) return ToolResult(false, "I need a URL to open.")
        val value = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
        return runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult(true, "Opening $value.") }.getOrElse { ToolResult(false, "Android could not open that link.") }
    }
    fun maps(query: String): ToolResult = runCatching {
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(query))
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ToolResult(true, "Opening Maps for $query.")
    }.getOrElse { ToolResult(false, "No maps application is available.") }
    fun call(number: String): ToolResult {
        val cleaned = PhoneNumberUtils.normalizeNumber(number)
        if (cleaned.isBlank()) return ToolResult(false, "I need a phone number to place the call.")
        return CallManager(context).placeCall(cleaned, confirmed = true)
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
    private val apps = AppManager(context)
    private val device = DeviceTools(context)
    val tools = listOf(
        ToolSpec("openApp", "Open an installed application."), ToolSpec("goHome", "Return to the launcher."),
        ToolSpec("openSettings", "Open Android Settings."), ToolSpec("openCamera", "Open the camera."),
        ToolSpec("openBrowser", "Open a web page."), ToolSpec("openMaps", "Open a location/search in Maps."),
        ToolSpec("call", "Place a phone call to a supplied number."),
        ToolSpec("replyToLatestNotification", "Reply to the latest notification when it exposes a reply action.", true),
        ToolSpec("remember", "Store a user-requested local memory."), ToolSpec("forgetMemory", "Delete a user-requested local memory.", true), ToolSpec("clearMemory", "Delete all local Astra memory.", true)
    )
    suspend fun execute(name: String, args: Map<String, String>, confirmed: Boolean = false): ToolResult = withContext(Dispatchers.Main) {
        val spec = tools.firstOrNull { it.name == name } ?: return@withContext ToolResult(false, "Unknown tool.")
        if (spec.requiresConfirmation && !confirmed) return@withContext ToolResult(false, "CONFIRMATION_REQUIRED")
        when (name) {
            "openApp" -> apps.open(args["name"].orEmpty())
            "goHome" -> device.home()
            "openSettings" -> device.settings()
            "openCamera" -> device.camera()
            "openBrowser" -> device.browser(args["url"].orEmpty())
            "openMaps" -> device.maps(args["query"].orEmpty())
            "call" -> device.call(args["number"].orEmpty())
            "replyToLatestNotification" -> { val latest = AstraNotificationListenerService.latest() ?: return@withContext ToolResult(false, "There are no available notifications."); AstraNotificationListenerService.current()?.reply(latest.key, args["message"].orEmpty()) ?: ToolResult(false, "Notification access is not enabled.") }
            else -> ToolResult(false, "Tool is not configured.")
        }
    }
}

class LocalCommandEngine(private val context: Context) {
    private val router = ToolRouter(context)
    suspend fun handle(text: String): ToolResult? {
        val t = text.trim()
        val lower = t.lowercase()
        val urlCandidate = when {
            lower.startsWith("http://") || lower.startsWith("https://") -> t
            lower.startsWith("open url ") -> t.substring(9).trim()
            lower.startsWith("open website ") -> t.substring(13).trim()
            lower.startsWith("go to ") && t.substring(6).contains(".") -> t.substring(6).trim()
            else -> ""
        }
        if (urlCandidate.isNotBlank()) return router.execute("openBrowser", mapOf("url" to urlCandidate))
        return when {
            lower.startsWith("take a photo") || lower.startsWith("take photo") || lower.startsWith("take a pic") || lower.startsWith("take pic") || lower.startsWith("open camera") || lower == "camera" -> router.execute("openCamera", emptyMap())
            lower.startsWith("open browser") || lower.startsWith("browse to ") -> router.execute("openBrowser", mapOf("url" to t.substringAfter(" ", "").removePrefix("browser ").removePrefix("to ").trim()))
            lower.startsWith("open maps") || lower.startsWith("find on maps ") || lower.startsWith("navigate to ") -> router.execute("openMaps", mapOf("query" to t.substringAfter(" ").removePrefix("maps ").removePrefix("on maps ").removePrefix("to ").trim()))
            lower.startsWith("play ") -> router.execute("openApp", mapOf("name" to t.substring(5).removeSuffix(" game").trim()))
            lower.startsWith("launch ") -> router.execute("openApp", mapOf("name" to t.substring(7).trim()))
            lower.startsWith("open ") -> router.execute("openApp", mapOf("name" to t.substring(5)))
            lower.contains("go home") || lower == "home" -> router.execute("goHome", emptyMap())
            lower.contains("open settings") -> router.execute("openSettings", emptyMap())
            lower.startsWith("call ") || lower.startsWith("dial ") -> router.execute("call", mapOf("number" to t.substringAfter(' ')), confirmed = true)
            lower.startsWith("reply ") -> router.execute("replyToLatestNotification", mapOf("message" to t.substringAfter(' ')), confirmed = false)
            else -> null
        }
    }
}
