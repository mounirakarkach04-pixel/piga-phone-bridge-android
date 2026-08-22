package io.piga.phonebridge

import android.app.Application
import android.content.Context

class PigaBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("piga_bridge", Context.MODE_PRIVATE)
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
