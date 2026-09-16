package com.astra.ai

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONArray
import java.net.URL

/** Astra's AI-controlled browser surface. It inventories links/media and can target embedded videos. */
class AstraWebBrowserActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        status = TextView(this).apply { setTextColor(Color.WHITE); setPadding(18, 10, 18, 10); text = "Astra Browser • analysing page…" }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
                override fun onPageFinished(view: WebView, url: String) { super.onPageFinished(view, url); inventory() }
            }
        }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val requested = intent?.dataString ?: intent?.getStringExtra("url")
        if (!requested.isNullOrBlank()) load(requested)
    }

    private fun load(raw: String) {
        val value = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
        web.loadUrl(value)
    }

    private fun inventory() {
        val js = """
            (function(){
              const abs=u=>{try{return new URL(u,location.href).href}catch(e){return u||''}};
              const links=[...document.querySelectorAll('a[href]')].map((e,i)=>({i,text:(e.innerText||e.getAttribute('aria-label')||e.title||'').trim().slice(0,200),url:abs(e.href)}));
              const imgs=[...document.images].map((e,i)=>({i,alt:(e.alt||'').trim().slice(0,200),src:abs(e.currentSrc||e.src)}));
              const videos=[...document.querySelectorAll('video')].map((e,i)=>({i,src:abs(e.currentSrc||e.src),poster:abs(e.poster),duration:Number.isFinite(e.duration)?e.duration:null}));
              const sources=[...document.querySelectorAll('video source, audio source')].map(e=>abs(e.src));
              const iframes=[...document.querySelectorAll('iframe[src]')].map((e,i)=>({i,src:abs(e.src),title:e.title||''}));
              const data={url:location.href,title:document.title,links,images:imgs,videos,sources,iframes,linksCount:links.length,imagesCount:imgs.length,videosCount:videos.length,iframesCount:iframes.length};
              return JSON.stringify(data);
            })()
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val clean = raw.removeSurrounding("\"").replace("\\\"", "\"")
            val summary = runCatching {
                val o = org.json.JSONObject(clean)
                val title = o.optString("title").ifBlank { "Untitled page" }
                "Astra Browser • ${o.optInt("linksCount")} links • ${o.optInt("imagesCount")} images • ${o.optInt("videosCount")} videos • ${o.optInt("iframesCount")} embedded frames\n$title"
            }.getOrDefault("Astra Browser • page loaded")
            runOnUiThread { status.text = summary }
        }
    }

    /** Called by Astra's action layer to play the nth embedded video on the current page. */
    fun playVideo(index: Int = 0) {
        web.evaluateJavascript("(function(){const v=document.querySelectorAll('video')[$index]; if(!v)return 'VIDEO_NOT_FOUND'; v.scrollIntoView({block:'center'}); v.play(); return 'PLAYING';})()", null)
    }

    /** Follows a page-local link by visible text or partial URL. */
    fun openInnerLink(query: String) {
        val q = query.replace("\\", "\\\\").replace("'", "\\'")
        web.evaluateJavascript("(function(){const q='$q'.toLowerCase(); const a=[...document.querySelectorAll('a[href]')].find(e=>((e.innerText||e.getAttribute('aria-label')||e.href||'').toLowerCase().includes(q))); if(!a)return 'LINK_NOT_FOUND'; a.click(); return a.href;})()", null)
    }

    override fun onDestroy() { if (::web.isInitialized) web.destroy(); super.onDestroy() }
}
