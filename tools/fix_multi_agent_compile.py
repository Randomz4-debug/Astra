from pathlib import Path
p = Path('app/src/main/java/com/astra/ai/AstraMultiAgentRuntime.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('class AstraFileAgent(private val context: Context) {\n    fun observe(): AstraAgentObservation {', 'class AstraFileAgent(private val context: Context) {\n    suspend fun observe(): AstraAgentObservation {')
s = s.replace('AstraAgenticExecutor.Result(false, "Agent execution failed: ${it.message ?: "unknown error"}")', 'AstraAgenticExecutor.Result(false, "Agent execution failed: ${it.message ?: "unknown error"}", 0)')
p.write_text(s, encoding='utf-8')
print('Multi-agent compile hardening applied')
