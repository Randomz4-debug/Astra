package com.astra.ai

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Closed-loop Android/game interaction controller.
 * It observes the current UI, waits for state transitions instead of fixed sleeps,
 * ranks interactive candidates, acts, then verifies that the screen changed.
 *
 * This is intentionally model-agnostic: a future on-device vision model can plug into
 * the candidate/ranking layer without changing the wait/action/recovery protocol.
 */
class GameInteractionAgent(private val service: AstraAccessibilityService) {
    data class Observation(val text: String, val fingerprint: String, val loading: Boolean)
    data class Candidate(val node: AccessibilityNodeInfo, val bounds: Rect, val score: Int, val label: String)

    suspend fun waitUntilReady(timeoutMs: Long = 45_000L): Observation {
        val started = System.currentTimeMillis()
        var previous = ""
        var stableCount = 0
        var last = observe()
        while (System.currentTimeMillis() - started < timeoutMs) {
            val current = observe()
            if (current.fingerprint == previous) stableCount++ else stableCount = 0
            previous = current.fingerprint
            last = current

            // A non-loading screen with accessibility content is actionable immediately.
            if (!current.loading && (current.text.isNotBlank() || hasInteractiveNodes())) return current
            // If the screen is still changing, poll faster; if stable, back off slightly.
            delay(if (stableCount < 3) 180L else 350L)
        }
        return last
    }

    suspend fun findAndPressStart(maxAttempts: Int = 8): Boolean {
        repeat(maxAttempts) { attempt ->
            val observation = waitUntilReady(if (attempt == 0) 1_500L else 2_500L)
            val candidates = collectCandidates(observation.text)
            val best = candidates.maxByOrNull { it.score }
            if (best != null && best.score >= 45) {
                val before = observation.fingerprint
                if (service.clickNode(best.node)) {
                    if (waitForTransition(before, 2_500L)) return true
                }
            }
            // Give animated/late UI controls time to appear without blocking for a fixed long sleep.
            delay(120L + attempt * 80L)
        }
        return false
    }

    suspend fun swipeUp() = service.swipe(540f, 1500f, 540f, 500f, 260L)
    suspend fun swipeDown() = service.swipe(540f, 500f, 540f, 1500f, 260L)
    suspend fun swipeLeft() = service.swipe(900f, 900f, 180f, 900f, 240L)
    suspend fun swipeRight() = service.swipe(180f, 900f, 900f, 900f, 240L)

    private suspend fun waitForTransition(before: String, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            delay(120L)
            val now = observe().fingerprint
            if (now != before) return true
        }
        return false
    }

    private fun observe(): Observation {
        val text = service.readScreen().trim().take(24_000)
        val fingerprint = text.lowercase().replace(Regex("\\s+"), " ").hashCode().toString()
        return Observation(text, fingerprint, looksLikeLoading(text))
    }

    private fun hasInteractiveNodes(): Boolean = collectCandidates("").isNotEmpty()

    private fun collectCandidates(screenText: String): List<Candidate> {
        val root = service.rootNode() ?: return emptyList()
        val out = mutableListOf<Candidate>()
        walk(root, screenText.lowercase(), out)
        return out.sortedByDescending { it.score }
    }

    private fun walk(node: AccessibilityNodeInfo, screenText: String, out: MutableList<Candidate>) {
        val label = listOf(node.text?.toString(), node.contentDescription?.toString()).filterNotNull().joinToString(" ").trim()
        if (node.isVisibleToUser && (node.isClickable || node.isFocusable)) {
            val rect = Rect(); node.getBoundsInScreen(rect)
            if (rect.width() > 10 && rect.height() > 10) {
                out += Candidate(node, rect, score(node, label, screenText), label)
            }
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, screenText, out) }
    }

    private fun score(node: AccessibilityNodeInfo, label: String, screenText: String): Int {
        val s = label.lowercase()
        var score = 0
        if (node.isClickable) score += 20
        if (node.isFocusable) score += 5
        if (s.isNotBlank()) score += 5
        val startWords = listOf("start", "play", "begin", "continue", "go", "enter", "launch", "resume", "next")
        if (startWords.any { s == it }) score += 60
        else if (startWords.any { s.contains(it) }) score += 40
        if (s.contains("setting") || s.contains("permission") || s.contains("cancel") || s.contains("exit")) score -= 45
        if (screenText.contains("loading") && s.isBlank()) score -= 5
        // Large centered controls are common game-menu affordances; this is only a weak signal.
        val r = Rect(); node.getBoundsInScreen(r)
        val area = r.width().toLong() * r.height().toLong()
        if (area > 40_000) score += 8
        return score
    }

    private fun looksLikeLoading(text: String): Boolean {
        if (text.isBlank()) return true
        val s = text.lowercase()
        return listOf("loading", "please wait", "connecting", "initializing", "starting", "downloading", "checking for updates", "syncing", "logging in").any { s.contains(it) }
    }
}
