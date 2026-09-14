package com.astra.ai

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Astra's bundled web UI. It works without internet and uses the same native agent brain. */
class AstraWebActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val runtime by lazy { AstraAgentRuntime(applicationContext) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "Astra")
            loadUrl("file:///android_asset/astra/index.html")
        }
        setContentView(web)
    }

    inner class Bridge {
        @JavascriptInterface
        fun ask(text: String) {
            lifecycleScope.launch {
                val localOnly = getSharedPreferences("astra_runtime", MODE_PRIVATE).getBoolean("local_only", true)
                val response = runtime.handle(text, localOnly)
                runOnUiThread { web.evaluateJavascript("onAstraResponse(${JSONObjectEscaper.quote(response)})", null) }
            }
        }

        @JavascriptInterface
        fun listen() { runOnUiThread { Toast.makeText(this@AstraWebActivity, "Use the native Astra voice button for microphone access.", Toast.LENGTH_SHORT).show() } }
        @JavascriptInterface
        fun stop() { }
        @JavascriptInterface
        fun readScreen() { runOnUiThread { Toast.makeText(this@AstraWebActivity, "Use Screen Access from the native Astra UI first.", Toast.LENGTH_SHORT).show() } }
    }

    override fun onDestroy() { web.destroy(); super.onDestroy() }
}

private object JSONObjectEscaper {
    fun quote(value: String): String = org.json.JSONObject.quote(value)
}
