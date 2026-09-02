package io.piga.phonebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Captures the Android PackageInstaller terminal/pending result locally.
 * The bridge runtime must subsequently turn this into a signed RESULT receipt.
 * A pending-user-action response is never treated as success.
 */
class ManagedInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val admissionId = intent.getStringExtra(EXTRA_ADMISSION_ID).orEmpty()
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val nonce = intent.getStringExtra(EXTRA_NONCE).orEmpty()
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        if (admissionId.isBlank() || packageName.isBlank() || nonce.length < 16 || sessionId < 0) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(1000)
        val normalized = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "succeeded"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "pending_user_action"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "aborted"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflict"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible"
            PackageInstaller.STATUS_FAILURE_INVALID -> "invalid"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "storage_failure"
            else -> "failed"
        }

        context.getSharedPreferences("piga_bridge", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "managed_install_result_$admissionId",
                listOf(normalized, packageName, sessionId.toString(), nonce, statusMessage).joinToString("|")
            )
            .putLong("managed_install_result_${admissionId}_at", System.currentTimeMillis())
            .apply()
    }

    companion object {
        const val ACTION = "io.piga.phonebridge.MANAGED_INSTALL_RESULT"
        const val EXTRA_ADMISSION_ID = "admission_id"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_NONCE = "nonce"
        const val EXTRA_SESSION_ID = "session_id"
    }
}