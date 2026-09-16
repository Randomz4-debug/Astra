from pathlib import Path

p = Path("app/src/main/java/com/astra/ai/AstraRealtimeCore.kt")
if not p.exists():
    raise SystemExit("AstraRealtimeCore.kt was not generated")
s = p.read_text(encoding="utf-8")
old = '''    @Volatile var profile: AstraPerformanceProfile = AstraPerformanceProfile.PERFORMANCE
    @Volatile var targetRenderFps: Int = 60
    @Volatile var detectorIntervalMs: Long = 66L

    fun setProfile(value: AstraPerformanceProfile) {
        profile = value
'''
new = '''    @Volatile private var currentProfile: AstraPerformanceProfile = AstraPerformanceProfile.PERFORMANCE
    @Volatile var targetRenderFps: Int = 60
    @Volatile var detectorIntervalMs: Long = 66L

    val profile: AstraPerformanceProfile
        get() = currentProfile

    fun setProfile(value: AstraPerformanceProfile) {
        currentProfile = value
'''
if old in s:
    s = s.replace(old, new, 1)
if 'private var currentProfile' not in s:
    raise SystemExit("Could not find the conflicting performance profile property")
p.write_text(s, encoding="utf-8")
print("Realtime performance-controller JVM setter clash fixed.")
