package io.piga.phonebridge

import android.app.Application
import android.content.Context

class PigaBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("piga_bridge", Context.MODE_PRIVATE)
        val canonical = ControlPlaneResolver.CANONICAL_CONTROL_PLANE
        val storedBaseUrl = prefs.getString("base_url", null)?.trim()?.removeSuffix("/")

        when {
            storedBaseUrl.isNullOrBlank() -> {
                prefs.edit().putString("base_url", canonical).apply()
            }
            storedBaseUrl != canonical -> {
                // A control-plane origin change is material. Pairing authority and
                // autonomous execution must not cross origins without fresh admission.
                prefs.edit()
                    .putString("base_url", canonical)
                    .putBoolean("paired", false)
                    .remove("pairing_id")
                    .putBoolean("master_autonomy", false)
                    .putString("runtime_status", "REPAIR_REQUIRED_CONTROL_PLANE_REENTRY")
                    .putString("autonomy_status", "DISARMED_REPAIR_REQUIRED")
                    .putLong("control_plane_reentry_ms", System.currentTimeMillis())
                    .apply()
            }
        }

        val paired = prefs.getBoolean("paired", false)
        val masterAutonomy = prefs.getBoolean("master_autonomy", false)
        val emergencyStop = prefs.getBoolean("emergency_stop", false)

        // Master Autonomy is never enabled implicitly. A prior explicit choice may
        // persist across process restarts, but Emergency Stop always dominates.
        if (emergencyStop && masterAutonomy) {
            prefs.edit()
                .putBoolean("master_autonomy", false)
                .putString("autonomy_status", "DISARMED_EMERGENCY_STOP")
                .apply()
        }

        BridgeRecoveryScheduler.ensureScheduled(this)
        if (paired && masterAutonomy && !emergencyStop) {
            BridgeRecoveryScheduler.requestRecovery(this)
        }
    }
}
