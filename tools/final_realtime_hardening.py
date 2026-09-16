from pathlib import Path

# Realtime functionality is maintained in the Kotlin sources. This CI stage is
# intentionally verification-only so repeated builds cannot inject duplicate
# timer parsers, out-of-scope variables, or malformed Kotlin into AstraTooling.kt.
root = Path("app/src/main/java/com/astra/ai")
required = [
    root / "AstraRealtimeCore.kt",
    root / "AstraRealtimeVisionAnalyzer.kt",
    root / "AstraAccessibilityService.kt",
    root / "AstraTooling.kt",
]
missing = [str(p) for p in required if not p.exists()]
if missing:
    raise SystemExit("Missing realtime source files: " + ", ".join(missing))

print("Astra realtime hardening sources verified; no source rewriting performed.")
