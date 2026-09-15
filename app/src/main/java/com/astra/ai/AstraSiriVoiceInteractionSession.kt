package com.astra.ai

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Clean Siri-inspired assistant surface. Uses original Astra visuals, not Apple's assets. */
class AstraSiriVoiceInteractionSession(private val ctx: Context) : VoiceInteractionSession(ctx) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var destroyed = false
    private var pendingSpeech: String? = null
    private lateinit var root: FrameLayout
    private lateinit var glow: SiriEdgeGlow
    private lateinit var orb: SiriOrb
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var answer: TextView
    private lateinit var input: EditText

    override fun onCreateContentView(): View {
        root = FrameLayout(ctx).apply { setBackgroundColor(Color.argb(248, 3, 4, 8)) }
        glow = SiriEdgeGlow(ctx)
        root.addView(glow, FrameLayout.LayoutParams(-1, dp(330), Gravity.BOTTOM))
        val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(12), dp(20), dp(18)) }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(ctx).apply { text = "Astra"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, 1) }
        val close = TextView(ctx).apply { text = "×"; textSize = 28f; setTextColor(Color.argb(190,255,255,255)); gravity = Gravity.CENTER; setOnClickListener { requestHide() } }
        header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f)); header.addView(close, LinearLayout.LayoutParams(dp(42), dp(42)))
        status = TextView(ctx).apply { text = "Listening"; textSize = 13f; setTextColor(Color.argb(170,245,247,255)); gravity = Gravity.CENTER }
        orb = SiriOrb(ctx)
        transcript = TextView(ctx).apply { textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
        answer = TextView(ctx).apply { textSize = 16f; setTextColor(Color.argb(235,245,247,255)); gravity = Gravity.CENTER; maxLines = 5; ellipsize = android.text.TextUtils.TruncateAt.END; visibility = View.GONE }
        panel.addView(header, LinearLayout.LayoutParams(-1, dp(42)))
        panel.addView(status, LinearLayout.LayoutParams(-1, dp(30)))
        panel.addView(orb, LinearLayout.LayoutParams(dp(160), dp(160)).apply { topMargin = dp(8); bottomMargin = dp(8) })
        panel.addView(transcript, LinearLayout.LayoutParams(-1, dp(78)))
        panel.addView(answer, LinearLayout.LayoutParams(-1, dp(92)))
        val pill = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(4), dp(6), dp(4)); background = rounded(Color.argb(48,255,255,255), dp(28).toFloat()) }
        input = EditText(ctx).apply { hint = "Ask Astra"; setHintTextColor(Color.argb(115,255,255,255)); setTextColor(Color.WHITE); textSize = 16f; setSingleLine(true); background = null; setPadding(dp(10),0,dp(6),0) }
        val send = TextView(ctx).apply { text = "↑"; textSize = 23f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = rounded(Color.argb(75,255,255,255),100f); setOnClickListener { submit(input.text.toString()) } }
        pill.addView(input, LinearLayout.LayoutParams(0, dp(50), 1f)); pill.addView(send, LinearLayout.LayoutParams(dp(44),dp(44)))
        panel.addView(pill, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(8) })
        root.addView(panel, FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply { bottomMargin = dp(8) })
        tts = TextToSpeech(ctx) { result ->
            if (destroyed) return@TextToSpeech
            ttsReady = result == TextToSpeech.SUCCESS
            if (!ttsReady) return@TextToSpeech
            val r = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.ERROR
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { glow.speaking=true; orb.speaking=true; status.text="Astra is speaking"; VoiceTelemetry.setSpeaking(true) }
                override fun onDone(id: String?) { glow.speaking=false; orb.speaking=false; VoiceTelemetry.setSpeaking(false); if (!destroyed) root.postDelayed({ startListening() },450) }
                override fun onError(id: String?) { glow.speaking=false; orb.speaking=false; VoiceTelemetry.setSpeaking(false) }
                override fun onAudioAvailable(id: String?, audio: ByteArray?) { if(audio!=null&&audio.isNotEmpty()){ val v=pcmRms(audio); glow.rms=v; orb.rms=v; VoiceTelemetry.setRms(v) } }
            })
            pendingSpeech?.let { pendingSpeech=null; speak(it) }
        }
        root.post { startListening() }
        return root
    }

    private fun requestHide() { stopListening(); VoiceTelemetry.setSpeaking(false); onHide() }
    private fun startListening() {
        if (destroyed || !SpeechRecognizer.isRecognitionAvailable(ctx)) return
        recognizer?.destroy(); VoiceTelemetry.setListening(true); glow.listening=true; status.text="Listening"; answer.visibility=View.GONE
        recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).also { r ->
            r.setRecognitionListener(object: RecognitionListener {
                override fun onReadyForSpeech(p:Bundle?){status.text="Listening"}
                override fun onBeginningOfSpeech(){status.text="Listening"}
                override fun onRmsChanged(v:Float){glow.rms=v; orb.rms=v; VoiceTelemetry.setRms(v)}
                override fun onBufferReceived(b:ByteArray?){ }
                override fun onEndOfSpeech(){VoiceTelemetry.setListening(false);glow.listening=false;status.text="Thinking"}
                override fun onError(e:Int){VoiceTelemetry.setListening(false);glow.listening=false;recognizer=null;status.text=if(e==SpeechRecognizer.ERROR_NO_MATCH)"I didn't catch that" else "Ready"}
                override fun onResults(b:Bundle?){VoiceTelemetry.setListening(false);glow.listening=false;recognizer=null;val text=b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty();if(text.isNotBlank()){transcript.text=text;submit(text)}}
                override fun onPartialResults(b:Bundle?){val text=b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty();if(text.isNotBlank())transcript.text=text}
                override fun onEvent(t:Int,b:Bundle?){ }
            })
            val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3);putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault().toLanguageTag())}
            runCatching{r.startListening(i)}.onFailure{status.text="Ready";glow.listening=false;VoiceTelemetry.setListening(false)}
        }
    }
    private fun submit(text:String){val clean=text.trim();if(clean.isBlank()||destroyed)return;transcript.text=clean;status.text="Thinking";scope.launch(Dispatchers.IO){val result=runCatching{AstraAgentRuntime(ctx).handle(clean,false)}.getOrElse{"Astra error: ${it.message?:"unknown error"}"};launch(Dispatchers.Main){if(destroyed)return@launch;answer.text=result;answer.visibility=View.VISIBLE;input.setText("");speak(result)}}}
    private fun speak(text:String){if(text.isBlank()||destroyed)return;if(!ttsReady){pendingSpeech=text;return};glow.speaking=true;orb.speaking=true;VoiceTelemetry.setSpeaking(true);runCatching{tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"astra-${System.currentTimeMillis()}")}}
    private fun stopListening(){recognizer?.cancel();recognizer?.destroy();recognizer=null;glow.listening=false;VoiceTelemetry.setListening(false)}
    override fun onShow(a:Bundle?,f:Int){super.onShow(a,f);root.post{startListening()}}
    override fun onHide(){stopListening();super.onHide()}
    override fun onDestroy(){destroyed=true;stopListening();tts?.stop();tts?.shutdown();tts=null;scope.cancel();VoiceTelemetry.setSpeaking(false);VoiceTelemetry.setRms(0f);super.onDestroy()}
    private fun dp(v:Int)=(v*ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private fun rounded(c:Int,r:Float)=android.graphics.drawable.GradientDrawable().apply{setColor(c);cornerRadius=r}
    private fun pcmRms(b:ByteArray):Float{var s=0.0;var n=0;var i=0;while(i+1<b.size){val x=((b[i+1].toInt() shl 8) or (b[i].toInt() and 255)).toShort().toInt();s+=x.toDouble()*x;n++;i+=2};return if(n==0)0f else(sqrt(s/n)/32768.0*100).toFloat()}
}

private class SiriOrb(context:Context):View(context){var rms=0f;var speaking=false;private var phase=0f;private val p=Paint(1);override fun onDraw(c:Canvas){phase+=.045f;val x=width/2f;val y=height/2f;val b=minOf(width,height)*.27f;val e=(abs(rms)/12f).coerceIn(.05f,1.8f);val r=b*(1+e*.12f);p.shader=RadialGradient(x-r*.25f,y-r*.3f,r*1.5f,intArrayOf(Color.WHITE,Color.rgb(65,205,255),Color.rgb(95,70,255),Color.rgb(245,70,190),Color.TRANSPARENT),floatArrayOf(0f,.22f,.55f,.82f,1f),Shader.TileMode.CLAMP);c.drawCircle(x,y,r*1.6f,p);p.shader=RadialGradient(x-r*.2f,y-r*.25f,r,intArrayOf(Color.WHITE,Color.rgb(70,190,255),Color.rgb(90,80,255),Color.rgb(245,70,190)),null,Shader.TileMode.CLAMP);c.drawCircle(x,y,r,p);p.shader=null;postInvalidateOnAnimation()}}

private class SiriEdgeGlow(context:Context):View(context){var rms=0f;var listening=false;var speaking=false;private var phase=0f;private val p=Paint(1);override fun onDraw(c:Canvas){phase+=.035f;val w=width.toFloat();val h=height.toFloat();val e=(abs(rms)/12f).coerceIn(.03f,1.7f);p.style=Paint.Style.STROKE;p.strokeWidth=8f+e*7f;p.shader=android.graphics.LinearGradient(0f,0f,w,0f,intArrayOf(Color.rgb(245,70,190),Color.rgb(80,80,255),Color.rgb(70,205,255),Color.rgb(245,70,190)),null,Shader.TileMode.MIRROR);val active=if(listening||speaking)0.12f else 0.025f;val amp=h*active*(1f+e);val path=android.graphics.Path();var x=0f;var first=true;while(x<=w){val env=(1.0-abs(x.toDouble()-w/2.0)/(w/2.0)).coerceAtLeast(0.0).toFloat();val wave=sin(x.toDouble()*.018+phase.toDouble()).toFloat();val y=h*.72f+wave*amp*(.25f+env);if(first){path.moveTo(x,y);first=false}else path.lineTo(x,y);x+=5f};c.drawPath(path,p);p.shader=null;p.style=Paint.Style.FILL;postInvalidateOnAnimation()}}
