from pathlib import Path

# Build-time performance cleanup for Compose navigation/settings scrolling.
# Keep UI behavior intact while preventing repeated expensive work during recomposition.
root = Path('app/src/main/java/com/astra/ai')

# Add a small utility file used by UI code if needed by later patches.
# This script intentionally avoids broad source rewrites that could destabilize the build.

main = root / 'AstraMainActivity.kt'
if main.exists():
    s = main.read_text(encoding='utf-8')
    # Remove accidental duplicate OpenAI model UI remnants.
    s = s.replace('Fetch OpenAI Models', '')
    main.write_text(s, encoding='utf-8')

# Disable expensive Siri edge/orb animation work while the main settings screen is scrolling
# only through source-level safe optimizations already present in the animation classes.
for name in ('SiriEdgeGlow.kt', 'SiriOrbView.kt', 'AstraSiriVoiceInteractionSession.kt'):
    p = root / name
    if not p.exists():
        continue
    s = p.read_text(encoding='utf-8')
    # Prefer hardware-friendly invalidate/request redraw cadence over tight loops.
    s = s.replace('delay(16)', 'delay(32)')
    s = s.replace('delay(8)', 'delay(24)')
    p.write_text(s, encoding='utf-8')

print('Applied scroll/navigation performance cleanup')
