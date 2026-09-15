from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/astra/ai'

def read(name):
    return (JAVA / name).read_text(encoding='utf-8')

def write(name, text):
    (JAVA / name).write_text(text, encoding='utf-8')

# 1) Main brain: put the multi-agent coordinator ahead of the legacy planner.
p = JAVA / 'AstraAgentRuntime.kt'
s = p.read_text(encoding='utf-8')
if 'private val multiAgentRuntime' not in s:
    s = s.replace('private val connectionTools = AstraConnectionTools(appContext)', 'private val connectionTools = AstraConnectionTools(appContext)\n    private val multiAgentRuntime = AstraMultiAgentRuntime(appContext)')
needle = '        // Execute compound requests before the simple command router. This is the key fix for'
if 'multiAgentRuntime.run(clean' not in s:
    block = '''        // Multi-agent layer: parallel perception/context first, then closed-loop execution.\n        if (shouldUseMultiAgent(clean)) {\n            val multi = runCatching {\n                multiAgentRuntime.run(clean) { plannerPrompt -> cloudOrLocal(AstraPersona.systemPrompt(appContext) + "\\n\\n" + plannerPrompt) }\n            }.getOrNull()\n            if (multi?.handled == true && multi.answer.isNotBlank()) {\n                chats.append(chatId, "assistant", multi.answer)\n                return multi.answer\n            }\n        }\n\n'''
    # chatId must exist before this block; move it after chat append by inserting after chats.append instead.
    block = '''        // Multi-agent layer: parallel perception/context first, then closed-loop execution.\n        if (shouldUseMultiAgent(clean)) {\n            val multi = runCatching {\n                multiAgentRuntime.run(clean) { plannerPrompt -> cloudOrLocal(AstraPersona.systemPrompt(appContext) + "\\n\\n" + plannerPrompt) }\n            }.getOrNull()\n            if (multi?.handled == true && multi.answer.isNotBlank()) {\n                chats.append(chatId, "assistant", multi.answer)\n                return multi.answer\n            }\n        }\n\n'''
    s = s.replace('        chats.append(chatId, "user", clean)\n\n', '        chats.append(chatId, "user", clean)\n\n' + block)
if 'private fun shouldUseMultiAgent' not in s:
    marker = '    private fun isGreeting(text: String): Boolean {'
    fn = '''    private fun shouldUseMultiAgent(text: String): Boolean {\n        val n = text.lowercase()\n        val action = Regex("\\\\b(open|tap|click|press|type|swipe|scroll|send|call|dial|launch|search|find|read|reply|message|whatsapp|telegram|screen|screenshot|camera|file|document|pdf)\\\\b").containsMatchIn(n)\n        val compound = n.contains(" and ") || n.contains(" then ") || n.contains(" after ") || n.contains(" before ") || n.contains(" while ")\n        return action || compound\n    }\n\n'''
    # Regex string above intentionally escaped for Kotlin source.
    s = s.replace(marker, fn + marker)
p.write_text(s, encoding='utf-8')

# 2) Fix the source-level model discovery bug: changing tabs must not fetch models.
p = JAVA / 'AstraMainActivity.kt'
s = p.read_text(encoding='utf-8')
s = re.sub(r'\n\s*LaunchedEffect\(page\) \{ if \(page == 1\) discoverAllModels\(\) \}', '', s)
s = re.sub(r'\n\s*if \(endpoint\.contains\(":12434"\) && localResult\.isNotEmpty\(\)\) \{ endpoint = endpoint\.replace\(":12434", ":11434"\); local\.configure\(endpoint, model\) \}', '', s)
p.write_text(s, encoding='utf-8')

# 3) Prevent Keystore decryption on the main thread and serialize chat requests.
p = JAVA / 'AstraViewModel.kt'
s = p.read_text(encoding='utf-8')
s = s.replace('cloudConfigured = secure.openAiApiKey() != null', 'cloudConfigured = secure.hasOpenAiApiKey()')
s = s.replace('    fun setOpenAiApiKey(value: String) { runCatching { secure.setOpenAiApiKey(value); _ui.value = _ui.value.copy(cloudConfigured = value.isNotBlank()) } }', '''    fun setOpenAiApiKey(value: String) {\n        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {\n            runCatching { secure.setOpenAiApiKey(value) }\n            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { _ui.value = _ui.value.copy(cloudConfigured = value.isNotBlank()) }\n        }\n    }''')
# Avoid a second expensive AI request while one is already running.
if 'private var requestRunning' not in s:
    s = s.replace('    private val _ui = MutableStateFlow(', '    @Volatile private var requestRunning = false\n    private val _ui = MutableStateFlow(')
    s = s.replace('    fun ask(text: String) { if (text.isBlank()) return; viewModelScope.launch {', '    fun ask(text: String) { if (text.isBlank() || requestRunning) return; requestRunning = true; viewModelScope.launch {')
    s = s.replace(' _ui.value=_ui.value.copy(state=if(answer=="Stopped.")AssistantState.INTERRUPTED else AssistantState.SPEAKING,response=answer)} }', ' _ui.value=_ui.value.copy(state=if(answer=="Stopped.")AssistantState.INTERRUPTED else AssistantState.SPEAKING,response=answer); requestRunning = false } }')
# Ensure early stop also releases the guard.
s = s.replace('_ui.value=_ui.value.copy(state=AssistantState.INTERRUPTED,response="Stopped.");return@launch', '_ui.value=_ui.value.copy(state=AssistantState.INTERRUPTED,response="Stopped."); requestRunning = false; return@launch')
p.write_text(s, encoding='utf-8')

# 4) Make the lock/background voice UI share the same multi-agent brain and avoid hot-loop rendering.
p = JAVA / 'AstraSiriVoiceInteractionSession.kt'
s = p.read_text(encoding='utf-8')
# It already calls AstraAgentRuntime, which now routes through the multi-agent layer.
# Keep the voice session lifecycle, but don't continuously redraw a fully idle canvas.
s = s.replace('phase+=.045f;', 'if (speaking || rms > 0.5f) phase+=.045f;')
s = s.replace('postInvalidateOnAnimation()}}\n\nprivate class SiriEdgeGlow', 'if (speaking || rms > 0.5f) postInvalidateOnAnimation()}}\n\nprivate class SiriEdgeGlow')
s = s.replace('phase+=.035f;', 'if (listening || speaking || rms > 0.5f) phase+=.035f;')
s = s.replace('p.style=Paint.Style.FILL;postInvalidateOnAnimation()}}', 'p.style=Paint.Style.FILL;if (listening || speaking || rms > 0.5f) postInvalidateOnAnimation()}}')
p.write_text(s, encoding='utf-8')

# 5) Fix the coordinator's nested Result constructor after the source is generated.
p = JAVA / 'AstraMultiAgentRuntime.kt'
s = p.read_text(encoding='utf-8').replace('AstraAgenticExecutor.Result(false, "Agent execution failed: ${it.message ?: "unknown error"}")', 'AstraAgenticExecutor.Result(false, "Agent execution failed: ${it.message ?: "unknown error"}", 0)')
p.write_text(s, encoding='utf-8')

print('Multi-agent architecture, model-discovery persistence, chat threading and UI smoothness upgrade applied')
