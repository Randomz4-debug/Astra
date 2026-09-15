from pathlib import Path

p = Path('app/src/main/java/com/astra/ai')
voice = p / 'MultilingualVoiceController.kt'
s = voice.read_text(encoding='utf-8')
s = s.replace('val rms = kotlin.math.sqrt(sum / count) / 32768.0\n                    VoiceTelemetry.setRms((rms * 100f).coerceIn(0f, 100f))', 'val rms = (kotlin.math.sqrt(sum / count) / 32768.0).toFloat()\n                    VoiceTelemetry.setRms((rms * 100f).coerceIn(0f, 100f))')
voice.write_text(s, encoding='utf-8')

main = p / 'AstraMainActivity.kt'
s = main.read_text(encoding='utf-8')
s = s.replace('drawCircle(Color(0xFFFF3158).copy(alpha = .10f + .25f * strength), Offset(size.width / 2f, center), 22f + 32f * strength)', 'drawCircle(color = Color(0xFFFF3158).copy(alpha = .10f + .25f * strength), radius = 22f + 32f * strength, center = Offset(size.width / 2f, center))')
s = s.replace('drawCircle(Color(0xFFFF3158).copy(alpha = .75f), Offset(size.width / 2f, center), 3f + 4f * strength)', 'drawCircle(color = Color(0xFFFF3158).copy(alpha = .75f), radius = 3f + 4f * strength, center = Offset(size.width / 2f, center))')
main.write_text(s, encoding='utf-8')
print('Audio compile fixes applied.')
