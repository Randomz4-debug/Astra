package com.astra.ai

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Optional notification access. Android only binds this service after the user enables
 * notification access in system settings. Content is kept in memory only.
 */
class AstraNotificationListenerService : NotificationListenerService() {
    companion object {
        private val _notifications = MutableStateFlow<List<NotificationSummary>>(emptyList())
        val notifications: StateFlow<List<NotificationSummary>> = _notifications
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
}

data class NotificationSummary(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long
)
