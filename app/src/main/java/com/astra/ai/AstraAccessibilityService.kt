package com.astra.ai

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AstraAccessibilityService : AccessibilityService() {
    companion object {
        private var instance: AstraAccessibilityService? = null
        private val _screenText = MutableStateFlow("")
        val screenText: StateFlow<String> = _screenText
        fun current(): AstraAccessibilityService? = instance
    }

    override fun onServiceConnected() { instance = this }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only observe the accessibility tree. No data is uploaded or persisted here.
        val root = rootInActiveWindow ?: return
        _screenText.value = collectText(root).take(12000)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun readScreen(): String = _screenText.value

    fun clickText(text: String): Boolean {
        val node = findText(rootInActiveWindow, text) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedEditable(root) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollForward(): Boolean = rootInActiveWindow?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true

    fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun globalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun globalRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    private fun findText(node: AccessibilityNodeInfo?, wanted: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val value = node.text?.toString().orEmpty()
        if (value.equals(wanted, true) || value.contains(wanted, true)) return node
        for (i in 0 until node.childCount) findText(node.getChild(i), wanted)?.let { return it }
        return null
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) findFocusedEditable(node.getChild(i))?.let { return it }
        return null
    }

    private fun collectText(node: AccessibilityNodeInfo): String {
        val out = StringBuilder()
        fun walk(n: AccessibilityNodeInfo) {
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.append(it).append('\n') }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.append(it).append('\n') }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(node)
        return out.toString()
    }
}
