package io.piga.phonebridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.util.Locale
import java.util.UUID

class LocalDiagnosticsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("piga_bridge", MODE_PRIVATE) }
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 24)
            text = "PIGA Local Capability Diagnostics\nFail-closed safe adapter checks"
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
            addView(status, fullWidth())
            addView(testButton("1. Clipboard write") { testClipboard() }, fullWidth())
            addView(testButton("2. Open safe URL") { testUrl() }, fullWidth())
            addView(testButton("3. Text-to-Speech") { testTts() }, fullWidth())
            addView(testButton("4. Launch PIGA Bridge") { testAppLaunch() }, fullWidth())
            addView(testButton("5. Open Share chooser") { testShare() }, fullWidth())
            addView(testButton("Run safe local suite") { runSafeSuite() }, fullWidth())
        }

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun testButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun gate(): Boolean {
        if (!prefs.getBoolean("master_autonomy", false)) {
            setStatus("BLOCKED: Master Autonomy is OFF")
            return false
        }
        if (prefs.getBoolean("emergency_stop", false)) {
            setStatus("BLOCKED: Emergency Stop is ON")
            return false
        }
        return true
    }

    private fun testClipboard(): Boolean {
        if (!gate()) return false
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PIGA diagnostic", "PIGA_SAFE_DIAGNOSTIC_${System.currentTimeMillis()}"))
            record("clipboard_write", "succeeded")
            setStatus("clipboard_write: SUCCEEDED")
            true
        } catch (e: Exception) {
            fail("clipboard_write", e)
            false
        }
    }

    private fun testUrl(): Boolean {
        if (!gate()) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            require(intent.resolveActivity(packageManager) != null) { "No safe URL handler available" }
            startActivity(intent)
            record("url_intent", "succeeded")
            setStatus("url_intent: SUCCEEDED (example.com opened)")
            true
        } catch (e: Exception) {
            fail("url_intent", e)
            false
        }
    }

    private fun testTts(): Boolean {
        if (!gate()) return false
        setStatus("text_to_speech: STARTING")
        var engine: TextToSpeech? = null
        engine = TextToSpeech(applicationContext) { initStatus ->
            if (initStatus != TextToSpeech.SUCCESS) {
                record("text_to_speech", "failed")
                setStatus("text_to_speech: FAILED (engine unavailable)")
                engine?.shutdown()
                return@TextToSpeech
            }
            engine?.language = Locale.getDefault()
            val utteranceId = "piga-diag-${UUID.randomUUID()}"
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    record("text_to_speech", "succeeded")
                    runOnUiThread { setStatus("text_to_speech: SUCCEEDED") }
                    engine?.shutdown()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    record("text_to_speech", "failed")
                    runOnUiThread { setStatus("text_to_speech: FAILED") }
                    engine?.shutdown()
                }
            })
            val result = engine?.speak("PIGA safe capability diagnostic successful.", TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                record("text_to_speech", "failed")
                setStatus("text_to_speech: FAILED")
                engine?.shutdown()
            }
        }
        return true
    }

    private fun testAppLaunch(): Boolean {
        if (!gate()) return false
        return try {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
                ?: throw IllegalStateException("PIGA Bridge launch intent unavailable")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            record("supported_app_launch", "succeeded")
            setStatus("supported_app_launch: SUCCEEDED")
            true
        } catch (e: Exception) {
            fail("supported_app_launch", e)
            false
        }
    }

    private fun testShare(): Boolean {
        if (!gate()) return false
        return try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "PIGA safe diagnostic share test — no automatic send.")
            }
            val chooser = Intent.createChooser(send, "PIGA safe share diagnostic")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            require(send.resolveActivity(packageManager) != null) { "No share target available" }
            startActivity(chooser)
            record("share_text", "succeeded")
            setStatus("share_text: SUCCEEDED (chooser opened; nothing sent)")
            true
        } catch (e: Exception) {
            fail("share_text", e)
            false
        }
    }

    private fun runSafeSuite() {
        if (!gate()) return
        Thread {
            val results = mutableListOf<String>()
            results += if (testClipboard()) "clipboard_write=PASS" else "clipboard_write=FAIL"
            Thread.sleep(500)
            runOnUiThread { results += if (testUrl()) "url_intent=PASS" else "url_intent=FAIL" }
            Thread.sleep(700)
            runOnUiThread { testTts() }
            Thread.sleep(1500)
            runOnUiThread { results += if (testAppLaunch()) "supported_app_launch=PASS" else "supported_app_launch=FAIL" }
            Thread.sleep(700)
            runOnUiThread { results += if (testShare()) "share_text=PASS" else "share_text=FAIL" }
            prefs.edit().putString("local_diag_suite_last", results.joinToString(",") + ",tts=ASYNC").apply()
        }.start()
    }

    private fun record(capability: String, outcome: String) {
        prefs.edit()
            .putString("local_diag_${capability}_status", outcome)
            .putString("local_diag_${capability}_time", Instant.now().toString())
            .apply()
    }

    private fun fail(capability: String, e: Exception) {
        record(capability, "failed")
        setStatus("$capability: FAILED — ${e.message ?: e.javaClass.simpleName}")
    }

    private fun setStatus(message: String) {
        runOnUiThread { status.text = "PIGA Local Capability Diagnostics\n\n$message" }
    }
}
