package io.piga.phonebridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import org.json.JSONObject

/** Bounded local capability router. Admission remains server-side + local safety gated. */
object PhoneExecutionRouter {
    fun placeCall(context: Context, payload: JSONObject): String {
        require(context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            "CALL_PHONE permission missing."
        }
        val number = payload.optString("number").trim()
        require(number.matches(Regex("^[+0-9 ()-]{3,40}$"))) { "Phone number invalid." }
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: throw IllegalStateException("Telecom service unavailable.")
        if (Build.VERSION.SDK_INT >= 29) {
            require(!telecom.isEmergencyNumber(number)) { "Emergency numbers are never admitted for autonomous calling." }
        } else {
            val digits = number.filter(Char::isDigit)
            require(digits !in setOf("110", "112", "911", "999")) { "Emergency numbers are never admitted for autonomous calling." }
        }
        telecom.placeCall(Uri.fromParts("tel", number, null), android.os.Bundle())
        return "Telefonanruf an das zugelassene Ziel gestartet."
    }

    @Suppress("DEPRECATION")
    fun answerCall(context: Context): String {
        require(Build.VERSION.SDK_INT >= 26) { "Call answering requires Android 8+." }
        require(context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            "ANSWER_PHONE_CALLS permission missing."
        }
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: throw IllegalStateException("Telecom service unavailable.")
        telecom.acceptRingingCall()
        return "Eingehenden Telefonanruf angenommen."
    }

    @Suppress("DEPRECATION")
    fun hangup(context: Context): String {
        require(Build.VERSION.SDK_INT >= 28) { "Call termination requires Android 9+." }
        require(context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            "ANSWER_PHONE_CALLS permission missing."
        }
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: throw IllegalStateException("Telecom service unavailable.")
        require(telecom.endCall()) { "No eligible active call to terminate." }
        return "Aktiven Telefonanruf beendet."
    }

    fun composeMessage(context: Context, payload: JSONObject): String {
        val packageName = payload.optString("packageName").trim()
        require(packageName in setOf("com.whatsapp", "com.linkedin.android")) { "Messaging package not admitted." }
        val text = payload.optString("text").trim()
        require(text.isNotBlank() && text.length <= 8000) { "Message text invalid." }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        require(share.resolveActivity(context.packageManager) != null) { "Messaging target is not installed or share-capable." }
        context.startActivity(share)
        return "Nachricht im zugelassenen Messenger vorbereitet; noch nicht gesendet."
    }

    fun uiNavigation(payload: JSONObject): String = PigaAccessibilityService.performNavigation(payload)
    fun fillForm(payload: JSONObject): String = PigaAccessibilityService.fillForm(payload)
    fun sendMessage(payload: JSONObject): String = PigaAccessibilityService.confirmMessageSend(payload)
    fun submitForm(payload: JSONObject): String = PigaAccessibilityService.confirmFormSubmit(payload)
}
