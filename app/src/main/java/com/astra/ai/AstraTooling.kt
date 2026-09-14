package com.astra.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A deliberately small allow-listed tool surface. No shell, root, ADB, or hidden APIs. */
data class ToolSpec(
    val name: String,
    val description: String,
    val requiresConfirmation: Boolean = false
)

data class ToolResult(val ok: Boolean, val message: String)

class AppManager(private val context: Context) {
    fun launchableApps(): Map<String, String> = context.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .associate { it.loadLabel(context.packageManager).toString() to it.activityInfo.packageName }

    fun open(name: String): ToolResult {
        val apps = launchableApps()
        val exact = apps.entries.firstOrNull { it.key.equals(name.trim(), true) }
        val partial = exact ?: apps.entries.firstOrNull {
            it.key.contains(name.trim(), true) || name.trim().contains(it.key, true)
        }
        val packageName = partial?.value ?: return ToolResult(false, "I couldn't find an installed app named $name.")
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolResult(false, "That app cannot be launched.")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return ToolResult(true, "Opening ${partial.key}.")
    }
}

class DeviceTools(private val context: Context) {
    fun home(): ToolResult {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        return ToolResult(true, "Going home.")
    }

    fun back(): ToolResult {
        return if (android.os.Build.VERSION.SDK_INT >= 28) {
            ToolResult(false, "Back is available through AstraAccessibilityService after you enable Accessibility access.")
        } else ToolResult(false, "Back requires Accessibility access.")
    }

    fun settings(): ToolResult {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult(true, "Opening Settings.")
    }

    fun dial(number: String): ToolResult {
        val cleaned = PhoneNumberUtils.normalizeNumber(number)
        if (cleaned.isBlank()) return ToolResult(false, "I need a phone number.")
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleaned")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult(true, "Opening the dialer for $cleaned. I did not place the call automatically.")
    }
}

class MemoryManager(context: Context) {
    private val prefs = context.getSharedPreferences("astra_memory", Context.MODE_PRIVATE)
    fun remember(key: String, value: String) { prefs.edit().putString(key.trim(), value).apply() }
    fun recall(key: String): String? = prefs.getString(key.trim(), null)
    fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    fun forget(key: String) { prefs.edit().remove(key.trim()).apply() }
    fun clear() { prefs.edit().clear().apply() }
}

class ToolRouter(private val context: Context) {
    private val apps = AppManager(context)
    private val device = DeviceTools(context)
    val tools = listOf(
        ToolSpec("openApp", "Open an installed application."),
        ToolSpec("goHome", "Return to the launcher."),
        ToolSpec("openSettings", "Open Android Settings."),
        ToolSpec("dial", "Open the dialer with a number.", true),
        ToolSpec("remember", "Store a user-requested local memory."),
        ToolSpec("forgetMemory", "Delete a user-requested local memory.", true),
        ToolSpec("clearMemory", "Delete all local Astra memory.", true)
    )

    suspend fun execute(name: String, args: Map<String, String>, confirmed: Boolean = false): ToolResult =
        withContext(Dispatchers.Main) {
            val spec = tools.firstOrNull { it.name == name } ?: return@withContext ToolResult(false, "Unknown tool.")
            if (spec.requiresConfirmation && !confirmed) return@withContext ToolResult(false, "CONFIRMATION_REQUIRED")
            when (name) {
                "openApp" -> apps.open(args["name"].orEmpty())
                "goHome" -> device.home()
                "openSettings" -> device.settings()
                "dial" -> device.dial(args["number"].orEmpty())
                else -> ToolResult(false, "Tool is not configured.")
            }
        }
}

class LocalCommandEngine(private val context: Context) {
    private val router = ToolRouter(context)
    suspend fun handle(text: String): ToolResult? {
        val t = text.trim()
        val lower = t.lowercase()
        return when {
            lower.startsWith("open ") -> router.execute("openApp", mapOf("name" to t.substring(5)))
            lower.contains("go home") || lower == "home" -> router.execute("goHome", emptyMap())
            lower.contains("open settings") -> router.execute("openSettings", emptyMap())
            lower.startsWith("call ") -> router.execute("dial", mapOf("number" to t.substring(5)))
            else -> null
        }
    }
}
