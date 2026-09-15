from pathlib import Path

ROOT = Path('app/src/main/java/com/astra/ai')

def read(name): return (ROOT / name).read_text(encoding='utf-8')
def write(name, text): (ROOT / name).write_text(text, encoding='utf-8')

# 1) Fix Ollama on arbitrary ports (including 12434) and make provider discovery robust.
p = ROOT / 'AiProviderRegistry.kt'
s = p.read_text(encoding='utf-8')
s = s.replace('val urls = if (base.contains("11434") || base.endsWith("/ollama")) listOf("$base/api/tags") else listOf("$base/v1/models", "$base/models", "$base/api/tags")', 'val urls = listOf("$base/api/tags", "$base/v1/models", "$base/models")')
s = s.replace('val endpoint = if (base.contains("11434")) "$base/api/chat" else "$base/v1/chat/completions"', 'val endpoint = if (models.length() > 0 && baseOllama(base)) "$base/api/chat" else "$base/v1/chat/completions"')
s = s.replace('fun statusJson(): String = JSONObject().put("running", true).put("providers", providers().size).toString()', 'fun statusJson(): String = JSONObject().put("running", true).put("providers", providers().size).toString()\n    private fun baseOllama(base: String): Boolean = base.contains("/ollama", true) || base.contains(":11434") || base.contains(":12434") || runCatching { request("${base.trimEnd(\'/\')}/api/version", null, null, "GET")?.isNotBlank() == true }.getOrDefault(false)')
s = s.replace('val payload = JSONObject().put("model", model).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt))).put("stream", false).toString(); val endpoint', 'val payload = JSONObject().put("model", model).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt))).put("stream", false).toString(); val endpoint')
# Add an explicit connectivity test and a provider kind helper.
needle = '    fun statusJson(): String = JSONObject().put("running", true).put("providers", providers().size).toString()'
if 'suspend fun testProvider' not in s:
    s = s.replace(needle, needle + '\n    suspend fun testProvider(baseUrl: String, apiKey: String = ""): String = withContext(Dispatchers.IO) {\n        val base = baseUrl.trim().trimEnd(\'/\'); require(base.startsWith("http://") || base.startsWith("https://")) { "URL must start with http:// or https://" }\n        val ollama = request("$base/api/tags", apiKey.takeIf { it.isNotBlank() }, null, "GET")\n        if (!ollama.isNullOrBlank()) return@withContext "Connected: Ollama-compatible API detected"\n        val models = request("$base/v1/models", apiKey.takeIf { it.isNotBlank() }, null, "GET")\n        if (!models.isNullOrBlank()) "Connected: OpenAI-compatible API detected" else "Connection failed: no /api/tags or /v1/models response"\n    }')
p.write_text(s, encoding='utf-8')

# 2) Stronger reasoning prompt: use the model as a general expert while never pretending unsupported actions happened.
p = ROOT / 'AstraAgentRuntime.kt'
s = p.read_text(encoding='utf-8')
old = 'Use practical human common sense: infer ordinary intent from context, resolve pronouns and references from recent messages and visible screen text, and ask a concise clarification only when a missing detail is genuinely required.'
new = '''Operate as an expert general-purpose reasoning system: maximize factual accuracy, depth, planning quality, context tracking, mathematical/scientific reasoning, coding ability, language understanding, and task decomposition. Treat every user message as meaningful input, including short hints, slang, typos, screenshots/OCR text, chats, notifications, UI labels, and indirect requests. First infer the user's actual goal and relevant context, then select the best available model/provider and tools. For difficult questions, reason step-by-step internally, verify assumptions against available screen/workspace data, and give a concise but complete answer. For tasks, make an internal plan, execute supported steps in order, observe results when possible, and recover from failures rather than repeating a canned response. Never use a canned greeting as the answer to a substantive request. Use practical human common sense: infer ordinary intent from context, resolve pronouns and references from recent messages and visible screen text, and ask a concise clarification only when a missing detail is genuinely required.'''
s = s.replace(old, new)
# Make provider selection prefer configured provider but retain fallback.
s = s.replace('val cloud = cloudEngine.respond(prompt)', 'val cloud = cloudEngine.respond(prompt)')
p.write_text(s, encoding='utf-8')

# 3) Replace the background VoiceInteractionSession with a Siri-like minimal surface.
session = '''package com.astra.ai

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.service.voice.VoiceInteractionSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/** System assistant surface: compact Siri-style overlay, never an Activity. */
class AstraVoiceInteractionSession(private val sessionContext: Context) : VoiceInteractionSession(sessionContext) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var destroyed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var root: FrameLayout
    private lateinit var wave: SiriWaveView
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var answer: TextView
    private lateinit var textInput: EditText

    override fun onCreateContentView(): View {
        root = FrameLayout(sessionContext).apply { setBackgroundColor(Color.TRANSPARENT); setPadding(10, 0, 10, 12) }
        val card = LinearLayout(sessionContext).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(18, 12, 18, 12); background = roundedBackground(Color.argb(242, 7, 9, 15), 32f) }
        status = TextView(sessionContext).apply { text = "Listening…"; textSize = 11f; setTextColor(Color.rgb(170,180,200)); gravity = Gravity.CENTER }
        wave = SiriWaveView(sessionContext)
        transcript = TextView(sessionContext).apply { text = ""; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 2 }
        answer = TextView(sessionContext).apply { text = ""; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 4; visibility = View.GONE }
        textInput = EditText(sessionContext).apply { hint = "Type to speak to Astra…"; setHintTextColor(Color.rgb(125,130,145)); setTextColor(Color.WHITE); textSize = 14f; setSingleLine(true); background = roundedBackground(Color.rgb(20,23,31), 22f); setPadding(16,8,16,8) }
        val controls = LinearLayout(sessionContext).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val mic = ImageButton(sessionContext).apply { setImageResource(android.R.drawable.ic_btn_speak_now); setColorFilter(Color.WHITE); background = roundedBackground(Color.rgb(28,31,40), 100f); contentDescription = "Talk to Astra"; setOnClickListener { if (recognizer != null) stopListening() else startListening() } }
        val send = ImageButton(sessionContext).apply { setImageResource(android.R.drawable.ic_media_play); setColorFilter(Color.WHITE); background = roundedBackground(Color.rgb(28,62,100), 100f); contentDescription = "Send"; setOnClickListener { submit(textInput.text.toString()) } }
        controls.addView(mic, LinearLayout.LayoutParams(52,52).apply { marginEnd = 8 }); controls.addView(send, LinearLayout.LayoutParams(52,52))
        card.addView(status, LinearLayout.LayoutParams(-1,22)); card.addView(wave, LinearLayout.LayoutParams(-1,66)); card.addView(transcript, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)); card.addView(answer, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 3 }); card.addView(textInput, LinearLayout.LayoutParams(-1,48).apply { topMargin = 6 }); card.addView(controls, LinearLayout.LayoutParams(-1,54).apply { topMargin = 5 })
        root.addView(card, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        tts = TextToSpeech(sessionContext) { result ->
            if (destroyed) return@TextToSpeech
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) { val r = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.ERROR; if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.language = Locale.US; pendingSpeech?.let { pendingSpeech=null; speak(it) } }
        }
        root.post { startListening() }
        return root
    }
    private fun startListening() {
        if (destroyed || !SpeechRecognizer.isRecognitionAvailable(sessionContext)) { status.text = "Speech unavailable"; return }
        recognizer?.destroy(); VoiceTelemetry.setListening(true); wave.setListening(true); status.text = "Listening…"; transcript.text = ""; answer.visibility = View.GONE
        recognizer = SpeechRecognizer.createSpeechRecognizer(sessionContext).also { r ->
            r.setRecognitionListener(object: RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { status.text="Listening…" }
                override fun onBeginningOfSpeech() { status.text="Listening…" }
                override fun onRmsChanged(v: Float) { VoiceTelemetry.setRms(v); wave.setRms(v) }
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { VoiceTelemetry.setListening(false); wave.setListening(false); status.text="Thinking…" }
                override fun onError(e:Int) { VoiceTelemetry.setListening(false); wave.setListening(false); recognizer=null; status.text="Ready" }
                override fun onResults(b:Bundle?) { VoiceTelemetry.setListening(false); wave.setListening(false); val text=b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty(); if(text.isBlank()){status.text="I didn't catch that";return}; transcript.text=text; submit(text) }
                override fun onPartialResults(b:Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf{it.isNotBlank()}?.let{transcript.text=it} }
                override fun onEvent(t:Int,p:Bundle?) {}
            })
            val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true); putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3); putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault().toLanguageTag()); if(android.os.Build.VERSION.SDK_INT>=34){putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION,true);putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,true)} }
            runCatching{r.startListening(intent)}.onFailure{VoiceTelemetry.setListening(false);wave.setListening(false);status.text="Ready"}
        }
    }
    private fun stopListening(){runCatching{recognizer?.cancel()};recognizer?.destroy();recognizer=null;VoiceTelemetry.setListening(false);wave.setListening(false);status.text="Ready"}
    private fun submit(text:String){val clean=text.trim();if(clean.isBlank()||destroyed)return;transcript.text=clean;status.text="Thinking…";answer.visibility=View.GONE;scope.launch(Dispatchers.IO){val result=runCatching{AstraAgentRuntime(sessionContext).handle(clean,false)}.getOrElse{"Astra error: ${it.message?:"unknown error"}"};launch(Dispatchers.Main){if(destroyed)return@launch;answer.text=result;answer.visibility=View.VISIBLE;textInput.setText("");status.text="Astra";speak(result)}}}
    private fun speak(text:String){if(text.isBlank()||destroyed)return;if(!ttsReady){pendingSpeech=text;return};wave.setSpeaking(true);VoiceTelemetry.setSpeaking(true);runCatching{tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"astra-${System.currentTimeMillis()}")}.onFailure{wave.setSpeaking(false);VoiceTelemetry.setSpeaking(false)}}
    override fun onShow(args:Bundle?,showFlags:Int){super.onShow(args,showFlags);root.post{startListening()}}
    override fun onHide(){stopListening();super.onHide()}
    override fun onDestroy(){destroyed=true;stopListening();tts?.stop();tts?.shutdown();tts=null;ttsReady=false;pendingSpeech=null;VoiceTelemetry.setSpeaking(false);VoiceTelemetry.setRms(0f);scope.cancel();super.onDestroy()}
    private fun roundedBackground(color:Int,radius:Float)=android.graphics.drawable.GradientDrawable().apply{setColor(color);cornerRadius=radius}
}
private class SiriWaveView(context:Context):View(context){private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private var rms=0f;private var listening=false;private var speaking=false;private var phase=0f;private val colors=intArrayOf(Color.rgb(35,95,255),Color.rgb(75,205,255),Color.WHITE,Color.rgb(90,175,255),Color.rgb(35,95,255));init{postInvalidateOnAnimation()};fun setRms(v:Float){rms=v;invalidate()};fun setListening(v:Boolean){listening=v;invalidate()};fun setSpeaking(v:Boolean){speaking=v;invalidate()};override fun onDraw(c:Canvas){phase+=.055f;val w=width.toFloat();val h=height.toFloat();val cy=h/2f;val energy=if(listening||speaking)(abs(rms)/12f).coerceIn(.12f,1.4f) else .08f;val bars=51;for(i in 0 until bars){val x=(i+.5f)*w/bars;val d=abs(i-bars/2f)/(bars/2f);val env=(1-d*.84f).coerceAtLeast(.06f);val pulse=(.3+.7*abs(sin(phase*2+i*.5))).toFloat();val amp=h*.34f*env*(.3f+energy)*pulse;paint.color=colors[i*colors.size/bars];c.drawRoundRect(x-1.7f,cy-amp,x+1.7f,cy+amp,4f,4f,paint)};postInvalidateOnAnimation()}}
'''
write('AstraVoiceInteractionSession.kt', session)

# 4) Fix chat UI so user text always reaches the real runtime; add current assistant name and a better local status.
p = ROOT / 'AstraChatActivity.kt'
s = p.read_text(encoding='utf-8')
s = s.replace('var sending by remember { mutableStateOf(false) }', 'var sending by remember { mutableStateOf(false) }\n        var assistantName by remember { mutableStateOf("Astra") }')
s = s.replace('LaunchedEffect(Unit) {\n            val id = runtime.currentChatId()', 'LaunchedEffect(Unit) {\n            assistantName = getSharedPreferences("astra_runtime", MODE_PRIVATE).getString("assistant_name", "Astra") ?: "Astra"\n            val id = runtime.currentChatId()')
s = s.replace('Text("Astra")', 'Text(assistantName)')
s = s.replace('Text("Message Astra…")', 'Text("Message $assistantName…")')
p.write_text(s, encoding='utf-8')

# 5) Add a dedicated provider manager activity so users can add any OpenAI-compatible internet AI (OpenAI, Grok, Gemini-compatible endpoints, self-hosted, LAN, etc.).
provider_activity = '''package com.astra.ai

import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AstraProviderActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;padding(22)}
        val title=TextView(this).apply{text="AI Providers";textSize=24f;setTextColor(android.graphics.Color.WHITE)}
        val name=EditText(this).apply{hint="Provider name (e.g. Grok, Gemini, OpenAI)"}
        val url=EditText(this).apply{hint="Base URL (e.g. http://192.168.29.16:12434)"}
        val key=EditText(this).apply{hint="API key (optional)"}
        val status=TextView(this).apply{setTextColor(android.graphics.Color.LTGRAY)}
        val add=Button(this).apply{text="Test & Add Provider"}
        val list=Button(this).apply{text="Refresh Providers"}
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.setBackgroundColor(android.graphics.Color.rgb(5,6,10)); listOf(title,name,url,key,add,list,status,box).forEach{root.addView(it,LinearLayout.LayoutParams(-1,LinearLayout.LayoutParams.WRAP_CONTENT).apply{setMargins(0,10,0,10)})}; setContentView(root)
        val registry=AiProviderRegistry(this)
        fun refresh(){box.removeAllViews();registry.providersJson().let{json->org.json.JSONArray(json).also{a->for(i in 0 until a.length()){val o=a.getJSONObject(i);val row=LinearLayout(this);row.orientation=LinearLayout.HORIZONTAL;val t=TextView(this);t.text="${o.optString("name")} • ${o.optString("baseUrl")}";t.setTextColor(android.graphics.Color.WHITE);t.layoutParams=LinearLayout.LayoutParams(0,-2,1f);val del=Button(this);del.text="Delete";del.setOnClickListener{registry.removeProvider(o.optString("id"));refresh()};row.addView(t);row.addView(del);box.addView(row)}}}}
        add.setOnClickListener{val n=name.text.toString().trim();val u=url.text.toString().trim();val k=key.text.toString();if(n.isBlank()||u.isBlank()){status.text="Enter a provider name and URL";return@setOnClickListener};lifecycleScope.launch(Dispatchers.IO){val test=runCatching{registry.testProvider(u,k)}.getOrElse{"Connection error: ${it.message}"};withContext(Dispatchers.Main){status.text=test;if(test.startsWith("Connected")){runCatching{registry.addProvider(n,u,k)};refresh()}}}}
        list.setOnClickListener{refresh()};refresh()
    }
}
'''
write('AstraProviderActivity.kt', provider_activity)

# 6) Add provider manager + assistant name editor to Settings page, without changing the existing layout architecture.
p = ROOT / 'AstraMainActivity.kt'
s = p.read_text(encoding='utf-8')
insert = '''                item {\n                    Text("ASSISTANT IDENTITY", color = Color.White, style = MaterialTheme.typography.titleMedium)\n                    var assistantName by remember { mutableStateOf(a.getSharedPreferences("astra_runtime", 0).getString("assistant_name", "Astra") ?: "Astra") }\n                    OutlinedTextField(assistantName, { assistantName = it }, Modifier.fillMaxWidth(), label = { Text("Assistant name") }, singleLine = true)\n                    Button({ val clean = assistantName.trim().ifBlank { "Astra" }; a.getSharedPreferences("astra_runtime", 0).edit().putString("assistant_name", clean).apply(); info = "Assistant name saved: $clean" }, Modifier.fillMaxWidth()) { Text("Save Assistant Name") }\n                    Text("This name is used by the reasoning prompt, chat UI and voice assistant.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)\n                }\n                item {\n                    Text("AI PROVIDERS", color = Color.White, style = MaterialTheme.typography.titleMedium)\n                    Text("Add OpenAI-compatible AI services such as OpenAI, Grok, Gemini-compatible endpoints, local Ollama, LAN servers, or your own gateway. Astra will discover models and use the same Android tools.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)\n                    Button({ a.startActivity(Intent(a, AstraProviderActivity::class.java)) }, Modifier.fillMaxWidth()) { Text("Manage / Add AI Providers") }\n                }\n'''
marker = '                item { Text("DEFAULT ASSISTANT", color = Color.White, style = MaterialTheme.typography.titleMedium);'
if 'ASSISTANT IDENTITY' not in s:
    s = s.replace(marker, insert + marker)
p.write_text(s, encoding='utf-8')

# 7) Ensure the new activity is declared in the manifest.
manifest = Path('app/src/main/AndroidManifest.xml')
m = manifest.read_text(encoding='utf-8')
if 'AstraProviderActivity' not in m:
    m = m.replace('<application', '<application')
    pos = m.rfind('</application>')
    m = m[:pos] + '        <activity android:name=".AstraProviderActivity" android:exported="false" />\n' + m[pos:]
manifest.write_text(m, encoding='utf-8')

print('Astra intelligence/UI/provider upgrade applied')
''