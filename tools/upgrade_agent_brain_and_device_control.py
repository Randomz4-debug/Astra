from pathlib import Path

ROOT = Path('app/src/main/java/com/astra/ai')

# 1) Put a closed-loop model/tool controller in front of the old first-match command router.
p = ROOT / 'AstraAgentRuntime.kt'
s = p.read_text(encoding='utf-8')
needle = '    private val connectionTools = AstraConnectionTools(appContext)\n'
if 'private val agenticExecutor' not in s:
    if needle not in s:
        raise SystemExit('runtime field insertion point not found')
    s = s.replace(needle, needle + '    private val agenticExecutor = AstraAgenticExecutor(appContext)\n')

needle2 = '        val chatId = currentChatId()\n        chats.append(chatId, "user", clean)\n\n        // Execute compound requests before the simple command router.'
replacement2 = '''        val chatId = currentChatId()
        chats.append(chatId, "user", clean)

        // Real agent loop for device/app tasks. It plans, executes, observes the screen,
        // and continues until the requested task is actually complete. Simple questions do
        // not pay this latency cost.
        if (shouldUseAgenticExecutor(clean)) {
            val agent = runCatching {
                agenticExecutor.run(clean) { plannerPrompt ->
                    cloudOrLocal(AstraPersona.systemPrompt(appContext) + "\\n\\n" + plannerPrompt)
                }
            }.getOrNull()
            if (agent?.handled == true && agent.answer.isNotBlank()) {
                chats.append(chatId, "assistant", agent.answer)
                return agent.answer
            }
        }

        // Execute compound requests before the simple command router.'''
if 'if (shouldUseAgenticExecutor(clean))' not in s:
    if needle2 not in s:
        raise SystemExit('runtime agent insertion point not found')
    s = s.replace(needle2, replacement2)

needle3 = '    private fun isGreeting(text: String): Boolean {'
helper = '''    private fun shouldUseAgenticExecutor(text: String): Boolean {
        val n = text.lowercase()
        val action = listOf("open ", "launch ", "start ", "click ", "tap ", "type ", "read ", "send ", "reply ", "call ", "search ", "find ", "scroll ", "check ", "inspect ", "look at", "use whatsapp", "use telegram", "use instagram")
        val compound = listOf(" and ", " then ", " after ", " next ", " once ", " finally ", "contact", "message", "chat", "notification")
        return action.any { n.contains(it) } && (compound.any { n.contains(it) } || n.contains("whatsapp") || n.contains("telegram") || n.contains("instagram") || n.contains("screen"))
    }

'''
if 'private fun shouldUseAgenticExecutor(text: String)' not in s:
    if needle3 not in s: raise SystemExit('runtime helper insertion point not found')
    s = s.replace(needle3, helper + needle3)
p.write_text(s, encoding='utf-8')

# 2) Make screen matching robust to phone-number formatting and accessibility descriptions.
p = ROOT / 'AstraAccessibilityService.kt'
s = p.read_text(encoding='utf-8')
needle = '''    fun clickText(text: String): Boolean {
        val node = findText(rootInActiveWindow, text) ?: return false
        return clickNode(node)
    }
'''
addition = '''    fun clickText(text: String): Boolean {
        val node = findText(rootInActiveWindow, text) ?: return false
        return clickNode(node)
    }

    /** Matches visible phone numbers even when WhatsApp formats spaces, brackets or dashes. */
    fun clickTextNormalized(text: String): Boolean {
        val wanted = text.filter(Char::isDigit)
        if (wanted.length < 7) return clickText(text)
        val node = findNormalized(rootInActiveWindow, wanted) ?: return false
        return clickNode(node)
    }
'''
if 'clickTextNormalized' not in s:
    if needle not in s: raise SystemExit('accessibility insertion point not found')
    s = s.replace(needle, addition)
needle2 = '    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {'
helper2 = '''    private fun findNormalized(node: AccessibilityNodeInfo?, wantedDigits: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val visible = buildString {
            append(node.text?.toString().orEmpty())
            append(' ')
            append(node.contentDescription?.toString().orEmpty())
        }
        val digits = visible.filter(Char::isDigit)
        if (digits.contains(wantedDigits) || (wantedDigits.contains(digits) && digits.length >= 7)) return node
        for (i in 0 until node.childCount) findNormalized(node.getChild(i), wantedDigits)?.let { return it }
        return null
    }

'''
if 'private fun findNormalized' not in s:
    if needle2 not in s: raise SystemExit('accessibility helper insertion point not found')
    s = s.replace(needle2, helper2 + needle2)
p.write_text(s, encoding='utf-8')

# 3) Harden the deterministic WhatsApp fallback. Do not launch an external browser/wa.me after
# opening WhatsApp; use WhatsApp's own visible UI first.
p = ROOT / 'AstraCommandPlanner.kt'
s = p.read_text(encoding='utf-8')
s = s.replace('''                var selected = service.clickText(sender)
                if (!selected) {
                    val digits = sender.filter(Char::isDigit)
                    if (digits.length >= 8) selected = service.clickText(digits)
                }
                if (selected) delay(1400)''', '''                var selected = service.clickText(sender)
                if (!selected) selected = service.clickTextNormalized(sender)
                if (!selected) {
                    val digits = sender.filter(Char::isDigit)
                    if (digits.length >= 8) selected = service.clickText(digits)
                }
                if (selected) delay(1600)''')

marker = '        if (readChat != null) return@withContext planReadChat(clean, readChat.groupValues[1].trim(), readChat.groupValues[2].trim())\n'
extra = '''        if (readChat != null) return@withContext planReadChat(clean, readChat.groupValues[1].trim(), readChat.groupValues[2].trim())

        val chainedWhatsApp = Regex("(?is)^open\\\\s+whatsapp.*?(?:open|find|search)\\\\s+(?:contact\\\\s+)?(.+?)\\\\s+.*?(?:read|check)\\\\s+(?:the\\\\s+)?(?:chat|messages?|conversation).*\\\\s*$")
            .find(clean)
        if (chainedWhatsApp != null) return@withContext planReadChat(clean, "WhatsApp", chainedWhatsApp.groupValues[1].trim())
'''
if 'val chainedWhatsApp = Regex' not in s and marker in s:
    s = s.replace(marker, extra)

start = s.find('    private fun prepareOpenedApp(')
if start >= 0:
    end = s.find('    private fun buildReasoningRequest', start)
    if end > start:
        s = s[:start] + '    private fun prepareOpenedApp(step: String, wholeRequest: String) { /* Keep navigation inside the target app. */ }\n\n' + s[end:]
p.write_text(s, encoding='utf-8')

# 4) Keep the background/lock-screen assistant in the same conversation instead of falling back to
# a one-turn greeting. After TTS finishes, automatically listen for the next turn.
p = ROOT / 'AstraVoiceInteractionSession.kt'
s = p.read_text(encoding='utf-8')
s = s.replace('''override fun onDone(utteranceId: String?) { orb.setSpeaking(false); wave.setSpeaking(false); VoiceTelemetry.setSpeaking(false); VoiceTelemetry.setRms(0f) }''', '''override fun onDone(utteranceId: String?) {
                        orb.setSpeaking(false); wave.setSpeaking(false); VoiceTelemetry.setSpeaking(false); VoiceTelemetry.setRms(0f)
                        if (!destroyed) root.postDelayed({ if (!destroyed && recognizer == null) startListening() }, 450)
                    }''')
s = s.replace('''override fun onError(utteranceId: String?) { orb.setSpeaking(false); wave.setSpeaking(false); VoiceTelemetry.setSpeaking(false) }''', '''override fun onError(utteranceId: String?) {
                        orb.setSpeaking(false); wave.setSpeaking(false); VoiceTelemetry.setSpeaking(false)
                        if (!destroyed) root.postDelayed({ if (!destroyed && recognizer == null) startListening() }, 450)
                    }''')
p.write_text(s, encoding='utf-8')

print('agent brain/device-control/background-session upgrades applied')
