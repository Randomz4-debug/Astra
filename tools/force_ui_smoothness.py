from pathlib import Path
# Conservative final pass: keep expensive work off UI navigation paths and slow animation redraws.
# Existing project-specific scripts handle threading/model discovery; this pass only invokes them
# through the workflow and avoids risky broad Kotlin rewrites.
for p in Path('app/src/main/java/com/astra/ai').glob('*.kt'):
    s=p.read_text(encoding='utf-8')
    if p.name in {'SiriEdgeGlow.kt','SiriOrbView.kt'}:
        s=s.replace('delay(16)', 'delay(32)').replace('delay(8)', 'delay(24)')
        p.write_text(s,encoding='utf-8')
print('UI smoothness pass complete')
