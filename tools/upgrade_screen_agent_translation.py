from pathlib import Path

ROOT = Path('app/src/main/java/com/astra/ai')

# -----------------------------------------------------------------------------
# 1. Independent pre-Astra input translator.
# Uses ML Kit language identification + on-device translation to English. It
# does not call Astra/OpenAI/Ollama and does not modify the user's visible text.
# -----------------------------------------------------------------------------
translator = ROOT / 'AstraPromptTranslationService.kt'
translator.write_text(r'''package com.astra.ai

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Isolated input-normalization layer that runs before Astra's reasoning/command layers.
 * English input is returned unchanged. Non-English input is translated to English locally
 * when an ML Kit language/translation model is available. The original user text is never
 * overwritten in chat history.
 */
class AstraPromptTranslationService(private val context: Context) {
    suspend fun translateForAstra(input: String): String = withContext(Dispatchers.IO) {
        val clean = input.trim()
        if (clean.isBlank()) return@withContext clean
        val language = runCatching {
            Tasks.await(LanguageIdentification.getClient().identifyLanguage(clean), 2500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault("und")
        if (language == "und" || language.equals("en", true) || language.startsWith("en-", true)) return@withContext clean

        val source = runCatching { TranslateLanguage.fromLanguageTag(language) }.getOrNull()
            ?: return@withContext clean
        if (source == TranslateLanguage.ENGLISH) return@withContext clean

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
        )
        return@withContext try {
            val conditions = DownloadConditions.Builder().build()
            Tasks.await(translator.downloadModelIfNeeded(conditions), 15000, java.util.concurrent.TimeUnit.MILLISECONDS)
            Tasks.await(translator.translate(clean), 12000, java.util.concurrent.TimeUnit.MILLISECONDS).trim().ifBlank { clean }
        } catch (_: Throwable) {
            clean
        } finally {
            translator.close()
        }
    }
}
''', encoding='utf-8')

# -----------------------------------------------------------------------------
# 2. Make the accessibility layer much faster and more useful to the agent.
# -----------------------------------------------------------------------------
p = ROOT / 'AstraAccessibilityService.kt'
s = p.read_text(encoding='utf-8')

s = s.replace(
'''    fun clickText(text: String): Boolean {
        val node = findText(rootInActiveWindow, text) ?: return false
        return clickNode(node)
    }
''',
'''    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findBestTextNode(root, text) ?: return false
        return clickNode(node)
    }
''')

if 'fun readInteractiveScreen(): String' not in s:
    marker = '    fun readScreen(): String = _screenText.value\n'
    addition = '''    fun readScreen(): String = _screenText.value

    /** Compact live map of actionable/visible controls including bounds for coordinate taps. */
    fun readInteractiveScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val out = StringBuilder()
        var count = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || count >= 180) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val interesting = text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable
            if (interesting) {
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                if (!r.isEmpty) {
                    out.append(count++).append(" | text=").append(text.take(120))
                        .append(" | desc=").append(desc.take(120))
                        .append(" | class=").append(node.className?.toString().orEmpty().take(80))
                        .append(" | clickable=").append(node.isClickable)
                        .append(" | editable=").append(node.isEditable)
                        .append(" | scrollable=").append(node.isScrollable)
                        .append(" | bounds=").append(r.left).append(',').append(r.top).append(',').append(r.right).append(',').append(r.bottom)
                        .append('\n')
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return out.toString().take(24000)
    }

    fun activePackageName(): String = rootInActiveWindow?.packageName?.toString().orEmpty()
'''
    if marker not in s:
        raise SystemExit('readScreen marker missing')
    s = s.replace(marker, addition)

s = s.replace(
'''    fun scrollForward(): Boolean = rootInActiveWindow?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
    fun scrollBackward(): Boolean = rootInActiveWindow?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
''',
'''    fun scrollForward(): Boolean = scrollSemantic(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, forward = true)
    fun scrollBackward(): Boolean = scrollSemantic(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, forward = false)

    private fun scrollSemantic(action: Int, forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable && node.performAction(action)) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        if (find(root) != null) return true
        val h = resources.displayMetrics.heightPixels.toFloat()
        val w = resources.displayMetrics.widthPixels.toFloat()
        return if (forward) swipe(w * 0.5f, h * 0.78f, w * 0.5f, h * 0.24f, 120L)
        else swipe(w * 0.5f, h * 0.24f, w * 0.5f, h * 0.78f, 120L)
    }
''')

s = s.replace(
'''        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build(), null, null)
''',
'''        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 25)).build(), null, null)
''')
s = s.replace('duration.coerceIn(100, 2000)', 'duration.coerceIn(60, 1200)')

# Prefer exact visible matches and clickable nodes before falling back to a coordinate tap.
if 'private fun findBestTextNode' not in s:
    marker = '    private fun findText(node: AccessibilityNodeInfo?, wanted: String): AccessibilityNodeInfo? {'
    helper = '''    private fun findBestTextNode(root: AccessibilityNodeInfo, wanted: String): AccessibilityNodeInfo? {
        val query = wanted.trim()
        if (query.isBlank()) return null
        val exact = ArrayList<AccessibilityNodeInfo>()
        val partial = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val exactMatch = text.equals(query, true) || desc.equals(query, true)
            val partialMatch = text.contains(query, true) || desc.contains(query, true)
            if (exactMatch) exact += node else if (partialMatch) partial += node
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return (exact + partial).firstOrNull { it.isVisibleToUser && (it.isClickable || it.isEnabled) }
            ?: (exact + partial).firstOrNull { it.isVisibleToUser }
    }

'''
    if marker not in s:
        raise SystemExit('findText marker missing')
    s = s.replace(marker, helper + marker)

# Keep normalized phone-number matching if the earlier upgrade script has not added it yet.
if 'fun clickTextNormalized' not in s:
    needle = '''    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findBestTextNode(root, text) ?: return false
        return clickNode(node)
    }
'''
    repl = needle + '''
    fun clickTextNormalized(text: String): Boolean {
        val wanted = text.filter(Char::isDigit)
        if (wanted.length < 7) return clickText(text)
        val node = findNormalized(rootInActiveWindow, wanted) ?: return false
        return clickNode(node)
    }
'''
    if needle in s:
        s = s.replace(needle, repl)
if 'private fun findNormalized' not in s:
    marker = '    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {'
    helper = '''    private fun findNormalized(node: AccessibilityNodeInfo?, wantedDigits: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val visible = buildString {
            append(node.text?.toString().orEmpty()).append(' ')
            append(node.contentDescription?.toString().orEmpty())
        }
        val digits = visible.filter(Char::isDigit)
        if (digits.contains(wantedDigits) || (wantedDigits.contains(digits) && digits.length >= 7)) return node
        for (i in 0 until node.childCount) findNormalized(node.getChild(i), wantedDigits)?.let { return it }
        return null
    }

'''
    if marker in s: s = s.replace(marker, helper + marker)

p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 3. Give the agent a live interactive map and OCR fallback, and reduce blind waits.
# -----------------------------------------------------------------------------
p = ROOT / 'AstraAgenticExecutor.kt'
s = p.read_text(encoding='utf-8')

old = '''                    delay(actionDelay(name))
                    lastObservation = screen()
                    trace.append("OBSERVATION: ").append(lastObservation.take(6000)).append("\\n")
'''
new = '''                    delay(actionDelay(name))
                    if (name == "openApp" || name == "clickScreen" || name == "tapScreen" || name == "scrollScreen" || name == "swipeScreen") {
                        lastObservation = waitForUiChange(lastObservation)
                    } else {
                        lastObservation = screen()
                    }
                    trace.append("OBSERVATION: ").append(lastObservation.take(6000)).append("\\n")
'''
if old in s: s = s.replace(old, new)

old_prompt = '''CURRENT VISIBLE SCREEN:
${screen.ifBlank { "(unavailable)" }}

Return ONLY one JSON object, no markdown:
'''
new_prompt = '''CURRENT VISIBLE SCREEN TEXT:
${screen.ifBlank { "(unavailable)" }}

LIVE INTERACTIVE SCREEN MAP (use bounds for coordinate taps when text labels are missing):
${AstraAccessibilityService.current()?.readInteractiveScreen().orEmpty().ifBlank { "(interactive map unavailable)" }}

ACTIVE APP PACKAGE:
${AstraAccessibilityService.current()?.activePackageName().orEmpty().ifBlank { "(unknown)" }}

Return ONLY one JSON object, no markdown:
'''
if old_prompt in s: s = s.replace(old_prompt, new_prompt)

s = s.replace(
'''3. Do not stop merely because an app opened. Continue until the user's requested information/action is completed.
4. Use the visible screen as evidence. If a target is not visible, search/navigate using available tools rather than inventing it.
''',
'''3. Do not stop merely because an app opened. Continue until the user's requested information/action is completed.
4. Use the live screen map as evidence. Prefer clickScreen for matching visible labels; when a control has no useful text, use tapScreen at the center of its reported bounds. Use scrollScreen for semantic scroll containers and swipeScreen only when necessary.
5. After every navigation/action, inspect the new screen before deciding the next tap. Never reuse stale coordinates after the UI changes.
''')
s = s.replace('''5. For reading a message: verify the conversation and sender from visible UI, then read the actual message. Never guess.
6. For WhatsApp contact requests, open WhatsApp, use its visible Search control, search the requested number/name, open the matching result, then inspect the conversation.
7. If a tool fails, recover with another available action when possible.
8. Never claim a login, connection, message read, message sent, or other action succeeded unless the tool result and/or visible screen verifies it.
9. `say` is the final answer to the user, not a status update. Keep it empty until done.
10. Never greet or say "Hi, I am Astra" for an ongoing task.
11. Do not expose unrelated private screen content.
''', '''6. For reading a message: verify the conversation and sender from visible UI, then read the actual message. Never guess.
7. For WhatsApp contact requests, open WhatsApp, use its visible Search control, search the requested number/name, open the matching result, then inspect the conversation.
8. For timers/alarms and similar tasks, open the actual installed Clock app, inspect its current UI, locate Timer/Alarm using visible controls, and continue until the requested value is actually entered/started. Do not merely say that Clock was opened.
9. If a tool fails, recover with another available action when possible.
10. Never claim a login, connection, message read, message sent, timer started, or other action succeeded unless the tool result and/or visible screen verifies it.
11. `say` is the final answer to the user, not a status update. Keep it empty until done.
12. Never greet or say "Hi, I am Astra" for an ongoing task.
13. Do not expose unrelated private screen content.
''')

marker = '    private fun screen(): String = AstraAccessibilityService.current()?.readScreen().orEmpty().trim().take(18000)\n'
replacement = '''    private fun screen(): String {
        val service = AstraAccessibilityService.current() ?: return ""
        val text = service.readScreen().trim().take(18000)
        if (text.isNotBlank()) return text
        return runCatching {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { service.readScreenWithOcr() }
        }.getOrDefault("").trim().take(18000)
    }

    private suspend fun waitForUiChange(previous: String): String {
        repeat(18) {
            val current = screen()
            if (current.isNotBlank() && current != previous) return current
            delay(100)
        }
        return screen()
    }
'''
if marker in s: s = s.replace(marker, replacement)
s = s.replace('''        "openApp", "openBrowser", "openMaps", "openCamera" -> 1400L
        "clickScreen", "typeScreen", "tapScreen" -> 650L
        "scrollScreen", "swipeScreen" -> 800L
        else -> 300L
''', '''        "openApp", "openBrowser", "openMaps", "openCamera" -> 450L
        "clickScreen", "typeScreen", "tapScreen" -> 120L
        "scrollScreen", "swipeScreen" -> 140L
        else -> 120L
''')
p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 4. Feed every command/task request through the translator before the execution brain.
# -----------------------------------------------------------------------------
p = ROOT / 'AstraAgentRuntime.kt'
s = p.read_text(encoding='utf-8')
field = '    private val connectionTools = AstraConnectionTools(appContext)\n'
if 'private val promptTranslator' not in s:
    if field not in s: raise SystemExit('runtime field marker missing')
    s = s.replace(field, field + '    private val promptTranslator = AstraPromptTranslationService(appContext)\n')

marker = '        val chatId = currentChatId()\n        chats.append(chatId, "user", clean)\n'
if 'val executionInput = promptTranslator.translateForAstra(clean)' not in s:
    if marker not in s: raise SystemExit('runtime chat marker missing')
    s = s.replace(marker, marker + '        val executionInput = runCatching { promptTranslator.translateForAstra(clean) }.getOrDefault(clean)\n')

# Agent/planner get the normalized prompt. The original text remains in chat history.
s = s.replace('shouldUseAgenticExecutor(clean)', 'shouldUseAgenticExecutor(executionInput)')
s = s.replace('agenticExecutor.run(clean) { plannerPrompt ->', 'agenticExecutor.run(executionInput) { plannerPrompt ->')
s = s.replace('val planned = runCatching { planner.plan(clean) }.getOrNull()', 'val planned = runCatching { planner.plan(executionInput) }.getOrNull()')

# Model answers receive both forms so the model understands the normalized command without losing
# the user's original wording/language for response context.
s = s.replace('generateModelAnswer(chatId, clean, localOnly)', 'generateModelAnswer(chatId, executionInput, localOnly)')

# Make the action classifier recognize single-step tasks such as "set a 5 second timer".
start = s.find('    private fun shouldUseAgenticExecutor(text: String): Boolean {')
if start >= 0:
    end = s.find('    private fun isGreeting(text: String): Boolean {', start)
    if end > start:
        helper = '''    private fun shouldUseAgenticExecutor(text: String): Boolean {
        val n = text.lowercase()
        val action = listOf("open ", "launch ", "start ", "click ", "tap ", "type ", "read ", "send ", "reply ", "call ", "search ", "find ", "scroll ", "check ", "inspect ", "look at", "use whatsapp", "use telegram", "use instagram", "set a timer", "set timer", "set an alarm", "set alarm", "turn on", "turn off", "enable ", "disable ")
        val compound = listOf(" and ", " then ", " after ", " next ", " once ", " finally ", "contact", "message", "chat", "notification", "timer", "alarm")
        return action.any { n.contains(it) } && (compound.any { n.contains(it) } || n.contains("whatsapp") || n.contains("telegram") || n.contains("instagram") || n.contains("screen") || n.contains("timer") || n.contains("alarm"))
    }

'''
        s = s[:start] + helper + s[end:]
p.write_text(s, encoding='utf-8')

print('screen-agent, fast gestures, interactive map, and isolated translation applied')
