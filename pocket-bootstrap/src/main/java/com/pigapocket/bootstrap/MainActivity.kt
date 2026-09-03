package com.pigapocket.bootstrap

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.URL
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    companion object {
        private const val APP_HOST = "qjvopzschqukitvudgfz.supabase.co"
        private const val APP_PATH = "/functions/v1/aeiou-kids-app"
        private const val APP_ORIGIN = "https://qjvopzschqukitvudgfz.supabase.co/functions/v1/aeiou-kids-app/"
        private const val MIC_REQUEST = 2001
    }

    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var recognitionPending = false

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.clearCache(true)
        webView.addJavascriptInterface(AeiouNativeBridge(), "AeiouNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val allowed = uri.scheme == "https" && uri.host == APP_HOST && (uri.path ?: "").startsWith(APP_PATH)
                return if (allowed) {
                    false
                } else {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.startsWith(APP_ORIGIN.removeSuffix("/"))) injectNativeVoicePolyfill()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
        }

        handleIntent(intent)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.GERMANY
    }

    inner class AeiouNativeBridge {
        @JavascriptInterface
        fun speak(text: String?, rate: Double, pitch: Double) {
            val bounded = text?.trim()?.take(2500).orEmpty()
            if (bounded.isBlank()) return
            runOnUiThread {
                tts?.setSpeechRate(rate.toFloat().coerceIn(0.55f, 1.60f))
                tts?.setPitch(pitch.toFloat().coerceIn(0.70f, 1.40f))
                tts?.speak(bounded, TextToSpeech.QUEUE_FLUSH, null, "aeiou-${System.currentTimeMillis()}")
            }
        }

        @JavascriptInterface
        fun cancelSpeech() { runOnUiThread { tts?.stop() } }

        @JavascriptInterface
        fun startRecognition() {
            runOnUiThread {
                if (!SpeechRecognizer.isRecognitionAvailable(this@MainActivity)) {
                    emitRecognitionError("not_available")
                    return@runOnUiThread
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    recognitionPending = true
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
                    return@runOnUiThread
                }
                startBoundedRecognition()
            }
        }

        @JavascriptInterface
        fun stopRecognition() { runOnUiThread { recognizer?.stopListening() } }
    }

    private fun startBoundedRecognition() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) = emitRecognitionError("recognition_$error")
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isBlank()) emitRecognitionError("empty_result") else emitRecognitionResult(text)
                }
            })
            val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            }
            speech.startListening(recognitionIntent)
        }
    }

    private fun emitRecognitionResult(text: String) {
        val quoted = JSONObject.quote(text.take(500))
        webView.post { webView.evaluateJavascript("window.__aeiouRecognitionResult && window.__aeiouRecognitionResult($quoted);", null) }
    }

    private fun emitRecognitionError(code: String) {
        val quoted = JSONObject.quote(code)
        webView.post { webView.evaluateJavascript("window.__aeiouRecognitionError && window.__aeiouRecognitionError($quoted);", null) }
    }

    private fun injectNativeVoicePolyfill() {
        val js = """
            (function(){
              if (!window.AeiouNative) return;
              if (!window.speechSynthesis || !window.SpeechSynthesisUtterance) {
                window.SpeechSynthesisUtterance = function(text){ this.text=String(text||''); this.rate=1; this.pitch=1; this.lang='de-DE'; this.onend=null; };
                window.speechSynthesis = {
                  speak:function(u){ window.AeiouNative.speak(String((u&&u.text)||''), Number((u&&u.rate)||1), Number((u&&u.pitch)||1)); if(u&&typeof u.onend==='function') setTimeout(function(){u.onend();}, 50); },
                  cancel:function(){ window.AeiouNative.cancelSpeech(); },
                  pause:function(){}, resume:function(){}, getVoices:function(){ return []; }
                };
              }
              if (!window.SpeechRecognition && !window.webkitSpeechRecognition) {
                function AeiouRecognition(){ this.lang='de-DE'; this.interimResults=false; this.maxAlternatives=1; this.onresult=null; this.onerror=null; this.onend=null; }
                AeiouRecognition.prototype.start=function(){ window.__aeiouRecognitionInstance=this; window.AeiouNative.startRecognition(); };
                AeiouRecognition.prototype.stop=function(){ window.AeiouNative.stopRecognition(); };
                window.__aeiouRecognitionResult=function(text){ var r=window.__aeiouRecognitionInstance; if(!r)return; if(typeof r.onresult==='function') r.onresult({results:[[{transcript:String(text||'')}]]}); if(typeof r.onend==='function') r.onend(); };
                window.__aeiouRecognitionError=function(code){ var r=window.__aeiouRecognitionInstance; if(!r)return; if(typeof r.onerror==='function') r.onerror({error:String(code||'unknown')}); if(typeof r.onend==='function') r.onend(); };
                window.SpeechRecognition=AeiouRecognition;
                window.webkitSpeechRecognition=AeiouRecognition;
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_REQUEST) {
            val retry = recognitionPending
            recognitionPending = false
            if (retry && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startBoundedRecognition()
            else if (retry) emitRecognitionError("permission_denied")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val deepLink = intent?.data
        val target = if (deepLink?.scheme == "aeiou") {
            val suffix = deepLink.path?.trimStart('/').orEmpty()
            if (suffix.isBlank()) APP_ORIGIN else APP_ORIGIN + suffix
        } else APP_ORIGIN
        loadGovernedHtml(target)
    }

    private fun loadGovernedHtml(target: String) {
        val parsed = Uri.parse(target)
        val allowed = parsed.scheme == "https" && parsed.host == APP_HOST && (parsed.path ?: "").startsWith(APP_PATH)
        if (!allowed) return
        Thread {
            runCatching {
                val separator = if (target.contains("?")) "&" else "?"
                val connection = URL(target + separator + "native_render=1&t=" + System.currentTimeMillis()).openConnection().apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
                    setRequestProperty("User-Agent", "AEIOU-Android-RC2/1.0")
                }
                val html = connection.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
                require(html.contains("<html", ignoreCase = true) && html.contains("AEIOU")) { "Invalid AEIOU HTML payload" }
                runOnUiThread { webView.loadDataWithBaseURL(APP_ORIGIN, html, "text/html", "UTF-8", target) }
            }.onFailure { err ->
                val safe = android.text.TextUtils.htmlEncode(err.message ?: "Unbekannter Ladefehler")
                runOnUiThread {
                    webView.loadDataWithBaseURL(
                        APP_ORIGIN,
                        "<html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><body style=\"font-family:sans-serif;padding:24px\"><h2>AEIOU konnte nicht geladen werden</h2><p>" + safe + "</p></body></html>",
                        "text/html", "UTF-8", null
                    )
                }
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
