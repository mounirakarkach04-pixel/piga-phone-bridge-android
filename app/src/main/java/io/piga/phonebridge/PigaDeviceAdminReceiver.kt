package io.piga.phonebridge

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Minimal Device Admin component required for explicit Android Device Owner provisioning.
 *
 * This receiver grants no authority by itself. Managed-install capability remains gated by
 * DevicePolicyManager.isDeviceOwnerApp(packageName) at execution time.
 */
class PigaDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled; Device Owner status must still be independently verified.")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin disabled; managed silent install must remain unavailable.")
    }

    companion object {
        private const val TAG = "PigaDeviceAdmin"
    }
}
