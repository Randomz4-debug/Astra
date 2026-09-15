from pathlib import Path

ROOT = Path('app/src/main/java/com/astra/ai')

# ML Kit's DownloadConditions lives in the common model package.
p = ROOT / 'AstraPromptTranslationService.kt'
s = p.read_text(encoding='utf-8')
s = s.replace('import android.content.Context\n', '')
s = s.replace('import com.google.mlkit.nl.translate.DownloadConditions', 'import com.google.mlkit.common.model.DownloadConditions')
s = s.replace('class AstraPromptTranslationService(private val context: Context)', 'class AstraPromptTranslationService')
# The earlier generated Kotlin used an actual newline inside a character literal.
s = s.replace(".append('\\n')", '.append("\\\\n")')
s = s.replace(".append('\n')", '.append("\\\\n")')
p.write_text(s, encoding='utf-8')

# The multi-agent smoothness pass may be followed by the agentic helper replacement.
# Ensure the multi-agent predicate still exists in the final generated runtime.
p = ROOT / 'AstraAgentRuntime.kt'
s = p.read_text(encoding='utf-8')
if 'private fun shouldUseMultiAgent' not in s:
    marker = '    private fun isGreeting(text: String): Boolean {'
    helper = '''    private fun shouldUseMultiAgent(text: String): Boolean {
        val n = text.lowercase()
        val action = Regex("\\\\b(open|tap|click|press|type|swipe|scroll|send|call|dial|launch|search|find|read|reply|message|whatsapp|telegram|screen|screenshot|camera|file|document|pdf|timer|alarm)\\\\b").containsMatchIn(n)
        return action || n.contains(" and ") || n.contains(" then ") || n.contains(" after ") || n.contains(" before ") || n.contains(" while ")
    }

'''
    if marker not in s:
        raise SystemExit('isGreeting marker missing for multi-agent helper')
    s = s.replace(marker, helper + marker)
p.write_text(s, encoding='utf-8')

# Verify the fast screen methods survived all build-time transformations.
p = ROOT / 'AstraAccessibilityService.kt'
s = p.read_text(encoding='utf-8')
if '.append("\\\\n")' not in s:
    s = s.replace(".append('\\n')", '.append("\\\\n")')
p.write_text(s, encoding='utf-8')

print('screen-agent compile fixes applied')
