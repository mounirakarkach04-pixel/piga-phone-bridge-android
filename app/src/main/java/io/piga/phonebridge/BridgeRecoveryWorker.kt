package io.piga.phonebridge

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters

class BridgeRecoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("piga_bridge", Context.MODE_PRIVATE)
        val paired = prefs.getBoolean("paired", false)
        val master = prefs.getBoolean("master_autonomy", false)
        val emergencyStop = prefs.getBoolean("emergency_stop", false)

        if (!paired || !master || emergencyStop) {
            prefs.edit()
                .putString("recovery_status", "SKIPPED paired=$paired master=$master emergencyStop=$emergencyStop")
                .putLong("last_recovery_ms", System.currentTimeMillis())
                .apply()
            return Result.success()
        }

        return try {
            val intent = Intent(applicationContext, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            prefs.edit()
                .putString("recovery_status", "BRIDGE_RESTART_REQUESTED")
                .putLong("last_recovery_ms", System.currentTimeMillis())
                .apply()
            Result.success()
        } catch (t: Throwable) {
            // Android 15+ may reject background FGS starts. Fail closed and let WorkManager retry.
            prefs.edit()
                .putString("recovery_status", "DEFERRED ${t.javaClass.simpleName}")
                .putLong("last_recovery_ms", System.currentTimeMillis())
                .apply()
            Result.retry()
        }
    }
}
