from pathlib import Path
p = Path('app/src/main/java/com/astra/ai/AstraVoiceInteractionSession.kt')
s = p.read_text(encoding='utf-8')
# VoiceInteractionSession is presented by Android's assistant window; do not call Activity-only window APIs.
s = s.replace('import android.view.WindowManager\n', '')
block = '''    override fun onCreate() {
        super.onCreate()
        runCatching { window?.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) }
    }

'''
s = s.replace(block, '')
p.write_text(s, encoding='utf-8')
assert 'WindowManager' not in s
assert 'class SiriOrbView' in s
assert 'onAudioAvailable' in s
print('Siri session compile hardening applied')
