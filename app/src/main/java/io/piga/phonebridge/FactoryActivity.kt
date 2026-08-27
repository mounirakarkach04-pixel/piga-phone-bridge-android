package io.piga.phonebridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import org.json.JSONObject

class FactoryActivity : Activity() {
    private lateinit var webView: WebView
    private val prefs by lazy { getSharedPreferences("piga_bridge", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            addJavascriptInterface(FactoryBridge(), "PigaNative")
            loadUrl("file:///android_asset/piga_factory.html")
        }
        setContentView(webView)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.evaluateJavascript("window.pigaRefreshNativeStatus && window.pigaRefreshNativeStatus()", null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("PigaNative")
            webView.destroy()
        }
        super.onDestroy()
    }

    inner class FactoryBridge {
        @JavascriptInterface
        fun status(): String = JSONObject().apply {
            put("product", "PIGA Factory")
            put("packageId", BuildConfig.APPLICATION_ID)
            put("version", BuildConfig.VERSION_NAME)
            put("paired", prefs.getBoolean("paired", false))
            put("masterAutonomy", prefs.getBoolean("master_autonomy", false))
            put("emergencyStop", prefs.getBoolean("emergency_stop", false))
            put("runtime", prefs.getString("runtime_status", "NOT_STARTED") ?: "NOT_STARTED")
            put("recovery", prefs.getString("recovery_status", "NOT_RUN") ?: "NOT_RUN")
            put("deviceId", prefs.getString("device_id", "LOCAL_DEVICE") ?: "LOCAL_DEVICE")
        }.toString()

        @JavascriptInterface
        fun openPairing() {
            runOnUiThread { startActivity(Intent(this@FactoryActivity, PairingActivity::class.java)) }
        }

        @JavascriptInterface
        fun openDiagnostics() {
            runOnUiThread { startActivity(Intent(this@FactoryActivity, LocalDiagnosticsActivity::class.java)) }
        }

        @JavascriptInterface
        fun openNativeControl() {
            runOnUiThread { startActivity(Intent(this@FactoryActivity, MainActivity::class.java)) }
        }

        @JavascriptInterface
        fun emergencyStop(): String {
            prefs.edit()
                .putBoolean("emergency_stop", true)
                .putBoolean("master_autonomy", false)
                .putString("autonomy_status", "DISARMED_EMERGENCY_STOP")
                .putString("runtime_status", "STOPPED_EMERGENCY_STOP")
                .apply()
            runOnUiThread { stopService(Intent(this@FactoryActivity, BridgeService::class.java)) }
            return status()
        }
    }
}
