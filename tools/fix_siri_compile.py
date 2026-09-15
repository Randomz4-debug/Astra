from pathlib import Path

p = Path('app/src/main/java/com/astra/ai/AstraVoiceInteractionSession.kt')
s = p.read_text(encoding='utf-8')
# Keep the implementation on the VoiceInteractionSession window; avoid Activity-only lifecycle APIs.
s = s.replace('import android.view.WindowManager\n', '')
block = '''    override fun onCreate() {
        super.onCreate()
        runCatching { window?.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) }
    }

'''
s = s.replace(block, '')
needle = '    override fun onCreateContentView(): View {'
replacement = '''    override fun onCreateContentView(): View {
        // VoiceInteractionSession has its own system window. These flags let the compact
        // assistant surface remain visible over the keyguard when the system permits it.
        runCatching {
            getWindow()?.getWindow()?.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
'''
s = s.replace(needle, replacement, 1)
p.write_text(s, encoding='utf-8')
assert 'getWindow()?.getWindow()' in s
assert 'class SiriOrbView' in s
assert 'onAudioAvailable' in s
print('Siri session compile + keyguard window hardening applied')
