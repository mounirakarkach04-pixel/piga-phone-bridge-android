package com.pigapocket.bootstrap

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    companion object {
        private const val TAG = "PigaPocketShell"
        private const val APP_HOST = "app.pigapocket.com"
        private const val APP_ORIGIN = "https://app.pigapocket.com"
        private const val CANONICAL_CLERK_PUBLISHABLE_KEY = "pk_live_Y2xlcmsuYXBwLnBpZ2Fwb2NrZXQuY29tJA"
        private const val EMPTY_CLERK_MARKER = "const E='',A=void 0"
        private const val PATCHED_CLERK_MARKER = "const E='$CANONICAL_CLERK_PUBLISHABLE_KEY',A=void 0"
        private val EXPO_WEB_BUNDLE = Regex("^/_expo/static/js/web/index-[A-Za-z0-9_-]+\\.js$")
    }

    private lateinit var webView: WebView
    private var pendingWebPermission: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        // The preceding field-test shell may have cached the broken bundle. Clear only
        // HTTP/WebView cache; Clerk/local storage remains governed by the web runtime.
        webView.clearCache(true)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return if (uri.scheme == "https" && uri.host == APP_HOST) {
                    false
                } else {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    true
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                val isCanonicalExpoBundle =
                    request.method == "GET" &&
                        uri.scheme == "https" &&
                        uri.host == APP_HOST &&
                        EXPO_WEB_BUNDLE.matches(uri.path ?: "")
                if (!isCanonicalExpoBundle) return super.shouldInterceptRequest(view, request)
                return interceptCanonicalRuntimeBundle(uri)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val originAllowed = request.origin?.scheme == "https" && request.origin?.host == APP_HOST
                val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!originAllowed || !wantsAudio) {
                    request.deny()
                    return
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    pendingWebPermission = request
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
                }
            }
        }
        handleIntent(intent)
    }

    private fun interceptCanonicalRuntimeBundle(uri: Uri): WebResourceResponse {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 25_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/javascript,text/javascript,*/*;q=0.8")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.e(TAG, "Canonical runtime bundle fetch blocked with HTTP $status")
                blockedRuntimeResponse("upstream_http_$status")
            } else {
                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.isEmpty() || bytes.size > 8_000_000) {
                    Log.e(TAG, "Canonical runtime bundle size outside recovery bounds: ${bytes.size}")
                    blockedRuntimeResponse("bundle_size_out_of_bounds")
                } else {
                    val source = bytes.toString(Charsets.UTF_8)
                    when {
                        source.contains(CANONICAL_CLERK_PUBLISHABLE_KEY) -> {
                            // Production has healed. Pass the canonical bundle through unchanged.
                            javascriptResponse(source)
                        }
                        source.indexOf(EMPTY_CLERK_MARKER) < 0 -> {
                            Log.e(TAG, "Clerk recovery marker is absent; refusing broad runtime mutation")
                            blockedRuntimeResponse("exact_clerk_marker_absent")
                        }
                        source.indexOf(EMPTY_CLERK_MARKER) != source.lastIndexOf(EMPTY_CLERK_MARKER) -> {
                            Log.e(TAG, "Clerk recovery marker is ambiguous; refusing runtime mutation")
                            blockedRuntimeResponse("exact_clerk_marker_ambiguous")
                        }
                        else -> {
                            val patched = source.replace(EMPTY_CLERK_MARKER, PATCHED_CLERK_MARKER)
                            if (!patched.contains(CANONICAL_CLERK_PUBLISHABLE_KEY) || patched.contains(EMPTY_CLERK_MARKER)) {
                                Log.e(TAG, "Post-patch Clerk invariant failed")
                                blockedRuntimeResponse("post_patch_invariant_failed")
                            } else {
                                Log.i(TAG, "Applied exact public Clerk publishable-key recovery to canonical web runtime")
                                javascriptResponse(patched)
                            }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Canonical runtime recovery failed", error)
            blockedRuntimeResponse("runtime_recovery_network_failure")
        } finally {
            connection?.disconnect()
        }
    }

    private fun javascriptResponse(source: String): WebResourceResponse =
        WebResourceResponse(
            "application/javascript",
            "UTF-8",
            ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)),
        )

    private fun blockedRuntimeResponse(reason: String): WebResourceResponse =
        javascriptResponse(
            "throw new Error('PIGA Clerk recovery blocked: $reason. No session or action authority was granted.');",
        )

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val request = pendingWebPermission
            pendingWebPermission = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                request?.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                request?.deny()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val deepLink = intent?.data
        val target = if (deepLink?.scheme == "pigapocket") {
            Uri.Builder()
                .scheme("https")
                .authority(APP_HOST)
                .path(deepLink.path ?: "/")
                .encodedQuery(deepLink.encodedQuery)
                .build()
                .toString()
        } else {
            APP_ORIGIN
        }
        webView.loadUrl(target)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
