package de.kalnbach.operations

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {
    private lateinit var webView: WebView

    inner class NativeDownloads {
        @JavascriptInterface
        fun saveText(filename: String, mimeType: String, content: String) {
            runOnUiThread {
                try {
                    val safe = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, safe)
                            put(MediaStore.Downloads.MIME_TYPE, mimeType)
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Kalnbach")
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: error("Download konnte nicht angelegt werden")
                        contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                            ?: error("Download konnte nicht geschrieben werden")
                    } else {
                        val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Kalnbach").apply { mkdirs() }
                        FileOutputStream(File(dir, safe)).use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    }
                    Toast.makeText(this@MainActivity, "Gespeichert: $safe", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Download fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun savePdf(filename: String, title: String, linesText: String) {
            runOnUiThread {
                try {
                    val safe = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val pdf = PdfDocument()
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f }
                    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17f; isFakeBoldText = true }
                    val lines = linesText.split("\n")
                    var pageNo = 1
                    var index = 0
                    while (index < lines.size || pageNo == 1) {
                        val info = PdfDocument.PageInfo.Builder(595, 842, pageNo).create()
                        val page = pdf.startPage(info)
                        val canvas = page.canvas
                        canvas.drawText(title.take(70), 36f, 42f, titlePaint)
                        var y = 68f
                        while (index < lines.size && y < 810f) {
                            val line = lines[index]
                            val chunks = line.chunked(92).ifEmpty { listOf("") }
                            for (chunk in chunks) {
                                if (y >= 810f) break
                                canvas.drawText(chunk, 36f, y, paint)
                                y += 14f
                            }
                            index++
                        }
                        pdf.finishPage(page)
                        pageNo++
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, safe)
                            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Kalnbach")
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: error("PDF konnte nicht angelegt werden")
                        contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) }
                            ?: error("PDF konnte nicht geschrieben werden")
                    } else {
                        val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Kalnbach").apply { mkdirs() }
                        FileOutputStream(File(dir, safe)).use { pdf.writeTo(it) }
                    }
                    pdf.close()
                    Toast.makeText(this@MainActivity, "PDF gespeichert: $safe", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "PDF fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = true
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString KalnbachOperationsAndroid/2.0"
        }
        webView.addJavascriptInterface(NativeDownloads(), "KalnbachNative")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
