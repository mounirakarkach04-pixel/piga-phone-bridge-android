package io.piga.phonebridge

import android.app.Application
import android.content.Context

class PigaBridgeApp : Application() {
    companion object {
        private const val POCKET_ENTERPRISE_BASE_URL = "https://ee08874a-6e9f-4d86-9942-9371a86f6c3e-00-3myurbngr26bi.janeway.replit.dev"
        private const val LEGACY_EXECUTOR_HOST = "d62aa607-3fcc-4f10-b437-8dd3326c4f3f-00-1iesyu3mfpkl2.janeway.replit.dev"
    }

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("piga_bridge", Context.MODE_PRIVATE)

        // One-time control-plane migration. Preserve the stable device ID and the
        // Android Keystore key, but never carry a pairing credential across origins.
        val storedBaseUrl = prefs.getString("base_url", null)?.trim()?.removeSuffix("/")
        val legacyBinding = storedBaseUrl?.contains(LEGACY_EXECUTOR_HOST, ignoreCase = true) == true
        if (legacyBinding) {
            prefs.edit()
                .putString("base_url", POCKET_ENTERPRISE_BASE_URL)
                .putBoolean("paired", false)
                .remove("pairing_id")
                .putString("runtime_status", "REPAIR_REQUIRED_CONTROL_PLANE_MIGRATION")
                .putString("autonomy_status", "DISARMED_REPAIR_REQUIRED")
                .putBoolean("master_autonomy", false)
                .apply()
        } else if (storedBaseUrl.isNullOrBlank()) {
            prefs.edit().putString("base_url", POCKET_ENTERPRISE_BASE_URL).apply()
        }

        val paired = prefs.getBoolean("paired", false)
        val emergencyStop = prefs.getBoolean("emergency_stop", false)

        // User-authorized autonomy mode: once a device is paired, keep the local
        // autonomy switch enabled across process restarts unless Emergency Stop is active.
        if (paired && !emergencyStop) {
            prefs.edit()
                .putBoolean("master_autonomy", true)
                .putString("autonomy_status", "ARMED_PAIRED_RECOVERY")
                .apply()
        }

        BridgeRecoveryScheduler.ensureScheduled(this)
        if (paired && !emergencyStop) {
            BridgeRecoveryScheduler.requestRecovery(this)
        }
    }
}
