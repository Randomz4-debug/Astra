package com.astra.ai

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class AstraAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally passive until the user directs an action through Astra.
    }

    override fun onInterrupt() {}
}
