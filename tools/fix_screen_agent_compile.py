from pathlib import Path
import re

ROOT = Path('app/src/main/java/com/astra/ai')

p = ROOT / 'AstraPromptTranslationService.kt'
s = p.read_text(encoding='utf-8')
s = s.replace('import android.content.Context\n', '')
s = s.replace('import com.google.mlkit.nl.translate.DownloadConditions', 'import com.google.mlkit.common.model.DownloadConditions')
s = s.replace('class AstraPromptTranslationService(private val context: Context)', 'class AstraPromptTranslationService')
s = re.sub(r"\.append\('\s*'\)", '.append("\\\\n")', s)
p.write_text(s, encoding='utf-8')

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
    if marker not in s: raise SystemExit('isGreeting marker missing for multi-agent helper')
    s = s.replace(marker, helper + marker)
p.write_text(s, encoding='utf-8')

p = ROOT / 'AstraAccessibilityService.kt'
s = p.read_text(encoding='utf-8')
s = re.sub(r"\.append\('\s*'\)", '.append("\\\\n")', s)
p.write_text(s, encoding='utf-8')

print('screen-agent compile fixes applied')
