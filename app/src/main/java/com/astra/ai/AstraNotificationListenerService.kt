package com.astra.ai

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Optional user-enabled notification access. No notification content is uploaded or persisted. */
class AstraNotificationListenerService : NotificationListenerService() {
    companion object {
        private var instance: AstraNotificationListenerService? = null
        private val _notifications = MutableStateFlow<List<NotificationSummary>>(emptyList())
        val notifications: StateFlow<List<NotificationSummary>> = _notifications
        fun current(): AstraNotificationListenerService? = instance
        fun latest(): NotificationSummary? = _notifications.value.firstOrNull()
    }

    override fun onListenerConnected() {
        instance = this
        _notifications.value = activeNotifications.orEmpty().map { sbn ->
            NotificationSummary(
                sbn.key,
                sbn.packageName,
                sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                System.currentTimeMillis()
            )
        }.take(50)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val item = NotificationSummary(sbn.key, sbn.packageName, title, text, System.currentTimeMillis())
        _notifications.value = (listOf(item) + _notifications.value.filterNot { it.key == item.key }).take(50)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        _notifications.value = _notifications.value.filterNot { it.key == sbn.key }
    }

    fun reply(key: String, message: String): ToolResult {
        val sbn = activeNotifications.firstOrNull { it.key == key } ?: return ToolResult(false, "Notification is no longer available.")
        val action = sbn.notification.actions?.firstOrNull { it.remoteInputs?.any { input -> input.resultKey.isNotBlank() } == true }
            ?: return ToolResult(false, "This notification does not expose a reply action.")
        val input = action.remoteInputs!!.first { it.resultKey.isNotBlank() }
        val intent = Intent()
        val results = android.os.Bundle().apply { putCharSequence(input.resultKey, message) }
        RemoteInput.addResultsToIntent(arrayOf(input), intent, results)
        return try {
            action.actionIntent.send(this, 0, intent)
            ToolResult(true, "Reply sent through the notification action.")
        } catch (_: Exception) { ToolResult(false, "Android rejected the notification reply action.") }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}

data class NotificationSummary(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long
)
