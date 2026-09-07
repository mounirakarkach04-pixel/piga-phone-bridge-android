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
            val previousRoot = prefs.getString("base_url", null)
            val canonicalRoot = ControlPlaneResolver.resolve(previousRoot)
            if (canonicalRoot != previousRoot) {
                require(
                    prefs.edit()
                        .putString("base_url", canonicalRoot)
                        .putString("recovery_status", "CONTROL_PLANE_REENTRY")
                        .putLong("control_plane_reentry_ms", System.currentTimeMillis())
                        .commit()
                ) { "Unable to persist canonical control-plane re-entry" }
            }

            val recoveryRequestedAt = System.currentTimeMillis()
            require(
                prefs.edit()
                    .putString("recovery_status", "BRIDGE_RESTART_REQUESTED")
                    .putLong("last_recovery_ms", recoveryRequestedAt)
                    .commit()
            ) { "Unable to persist bridge restart request" }

            val intent = Intent(applicationContext, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }

            val verificationDeadline = recoveryRequestedAt + 45_000L
            while (System.currentTimeMillis() < verificationDeadline) {
                if (isStopped) {
                    prefs.edit()
                        .putString("recovery_status", "DEFERRED_WORKER_STOPPED")
                        .putLong("last_recovery_ms", System.currentTimeMillis())
                        .apply()
                    return Result.retry()
                }

                val lastPoll = prefs.getLong("last_poll_ms", 0L)
                val runtimeStatus = prefs.getString("runtime_status", "UNKNOWN").orEmpty()
                if (lastPoll >= recoveryRequestedAt && runtimeStatus.startsWith("ONLINE")) {
                    prefs.edit()
                        .putString("recovery_status", "RECOVERED")
                        .putLong("recovery_verified_ms", System.currentTimeMillis())
                        .putLong("last_recovery_ms", System.currentTimeMillis())
                        .apply()
                    return Result.success()
                }
                Thread.sleep(1_000L)
            }

            val runtimeStatus = prefs.getString("runtime_status", "UNKNOWN").orEmpty().take(120)
            prefs.edit()
                .putString("recovery_status", "DEFERRED_RUNTIME $runtimeStatus")
                .putLong("last_recovery_ms", System.currentTimeMillis())
                .apply()

            // Pairing identity and Android Keystore material are intentionally untouched.
            // WorkManager retries the runtime recovery instead of forcing a re-pair.
            Result.retry()
        } catch (t: Throwable) {
            // Fail closed without destroying the existing pairing. Discovery/runtime
            // recovery is retried by WorkManager and a later verified poll clears it.
            prefs.edit()
                .putString("recovery_status", "DEFERRED ${t.javaClass.simpleName}")
                .putLong("last_recovery_ms", System.currentTimeMillis())
                .apply()
            Result.retry()
        }
    }
}
