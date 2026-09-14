package com.astra.ai

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity

/** Hosts the same HTTP Web UI locally so its fetch() calls hit real Astra APIs. */
class AstraWebActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var server: AstraLanServer

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = AstraLanServer(applicationContext)
        if (!server.start(8765)) {
            Toast.makeText(this, "Astra Web UI server could not start. Port 8765 may be in use.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            loadUrl("http://127.0.0.1:8765/")
        }
        setContentView(web)
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
