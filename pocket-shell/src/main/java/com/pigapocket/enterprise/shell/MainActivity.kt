package com.pigapocket.enterprise.shell

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.setSupportMultipleWindows(false)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme == "https" && uri.host == APP_HOST) return false
                if (uri.scheme == "pigapocket") {
                    loadGovernedDestination(uri)
                    return true
                }
                if (uri.scheme == "https") {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    return true
                }
                return true
            }
        }

        loadGovernedDestination(intent?.data)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadGovernedDestination(intent?.data)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun loadGovernedDestination(uri: Uri?) {
        val target = if (uri?.scheme == "pigapocket" && uri.host == "device-pairing") {
            PAIRING_URL
        } else {
            APP_URL
        }
        webView.loadUrl(target)
    }

    companion object {
        private const val APP_HOST = "app.pigapocket.com"
        private const val APP_URL = "https://app.pigapocket.com/"
        private const val PAIRING_URL = "https://app.pigapocket.com/device-pairing"
    }
}
