package io.piga.phonebridge

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LocalDiagnosticsActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 24)
            text = "PIGA Local Capability Diagnostics\nRead-only capability probes — no governed action is executed"
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
            addView(status, fullWidth())
            addView(testButton("1. Probe clipboard service") { probeClipboard() }, fullWidth())
            addView(testButton("2. Probe HTTPS URL handler") { probeUrlHandler() }, fullWidth())
            addView(testButton("3. Probe TTS availability") { probeTts() }, fullWidth())
            addView(testButton("4. Probe PIGA Bridge launchability") { probeAppLaunch() }, fullWidth())
            addView(testButton("5. Probe share handler") { probeShare() }, fullWidth())
            addView(testButton("Run read-only probe suite") { runReadOnlySuite() }, fullWidth())
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

    private fun probeClipboard(): Boolean {
        return try {
            val available = getSystemService(Context.CLIPBOARD_SERVICE) is ClipboardManager
            setStatus("clipboard_service: ${if (available) "AVAILABLE" else "UNAVAILABLE"}\nNo clipboard content was read or written.")
            available
        } catch (e: Exception) {
            fail("clipboard_service", e)
            false
        }
    }

    private fun probeUrlHandler(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            val available = intent.resolveActivity(packageManager) != null
            setStatus("https_url_handler: ${if (available) "AVAILABLE" else "UNAVAILABLE"}\nNo URL was opened.")
            available
        } catch (e: Exception) {
            fail("https_url_handler", e)
            false
        }
    }

    private fun probeTts(): Boolean {
        return try {
            val check = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
            val available = check.resolveActivity(packageManager) != null
            setStatus("text_to_speech_handler: ${if (available) "AVAILABLE" else "UNAVAILABLE"}\nNo speech was produced.")
            available
        } catch (e: Exception) {
            fail("text_to_speech_handler", e)
            false
        }
    }

    private fun probeAppLaunch(): Boolean {
        return try {
            val available = packageManager.getLaunchIntentForPackage(packageName) != null
            setStatus("piga_bridge_launchability: ${if (available) "AVAILABLE" else "UNAVAILABLE"}\nNo activity was launched.")
            available
        } catch (e: Exception) {
            fail("piga_bridge_launchability", e)
            false
        }
    }

    private fun probeShare(): Boolean {
        return try {
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
            val available = send.resolveActivity(packageManager) != null
            setStatus("share_handler: ${if (available) "AVAILABLE" else "UNAVAILABLE"}\nNo chooser was opened and nothing was shared.")
            available
        } catch (e: Exception) {
            fail("share_handler", e)
            false
        }
    }

    private fun runReadOnlySuite() {
        val results = listOf(
            "clipboard_service=${probeClipboard().toPassFail()}",
            "https_url_handler=${probeUrlHandler().toPassFail()}",
            "tts_handler=${probeTts().toPassFail()}",
            "piga_bridge_launchability=${probeAppLaunch().toPassFail()}",
            "share_handler=${probeShare().toPassFail()}"
        )
        setStatus(
            "READ_ONLY_PROBE_SUITE\n${results.joinToString("\n")}\n\n" +
                "No capability was executed or promoted. Gate 2 remains required for every governed effect."
        )
    }

    private fun Boolean.toPassFail() = if (this) "AVAILABLE" else "UNAVAILABLE"

    private fun fail(capability: String, e: Exception) {
        setStatus("$capability: PROBE_FAILED — ${e.message ?: e.javaClass.simpleName}\nNo action was executed.")
    }

    private fun setStatus(message: String) {
        runOnUiThread { status.text = "PIGA Local Capability Diagnostics\n\n$message" }
    }
}
