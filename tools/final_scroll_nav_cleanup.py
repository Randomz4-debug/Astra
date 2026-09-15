from pathlib import Path
# Conservative performance pass for the existing Android UI.
# Do not perform network/model work from navigation rendering paths.
for name in ('SiriEdgeGlow.kt','SiriOrbView.kt'):
    p=Path('app/src/main/java/com/astra/ai')/name
    if p.exists():
        s=p.read_text(encoding='utf-8').replace('delay(16)', 'delay(32)').replace('delay(8)', 'delay(24)')
        p.write_text(s, encoding='utf-8')
print('Final scroll/navigation cleanup applied')
