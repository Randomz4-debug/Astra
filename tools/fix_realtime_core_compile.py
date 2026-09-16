from pathlib import Path

p = Path("app/src/main/java/com/astra/ai/AstraRealtimeCore.kt")
if not p.exists():
    raise SystemExit("AstraRealtimeCore.kt was not generated")
s = p.read_text(encoding="utf-8")

# Normalize either historical generator form into one JVM-safe controller.
old_property = '''    @Volatile var profile: AstraPerformanceProfile = AstraPerformanceProfile.PERFORMANCE
    @Volatile var targetRenderFps: Int = 60
    @Volatile var detectorIntervalMs: Long = 66L

    fun setProfile(value: AstraPerformanceProfile) {
        profile = value
'''
new_property = '''    @Volatile private var currentProfile: AstraPerformanceProfile = AstraPerformanceProfile.PERFORMANCE
    @Volatile var targetRenderFps: Int = 60
    @Volatile var detectorIntervalMs: Long = 66L

    val profile: AstraPerformanceProfile
        get() = currentProfile

    fun setProfile(value: AstraPerformanceProfile) {
        currentProfile = value
'''
if old_property in s:
    s = s.replace(old_property, new_property, 1)

# If the generator already used applyProfile(), keep it; no generated JVM clash remains.
if 'private var currentProfile' not in s and 'fun applyProfile(value: AstraPerformanceProfile)' not in s:
    raise SystemExit("Could not normalize the performance profile controller")

p.write_text(s, encoding="utf-8")
print("Realtime performance-controller JVM setter clash fixed/verified.")
