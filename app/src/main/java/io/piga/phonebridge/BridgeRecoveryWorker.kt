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
        val now = System.currentTimeMillis()
        val snapshot = BridgeRecoveryPolicy.Snapshot(
            paired = prefs.getBoolean("paired", false),
            masterAutonomy = prefs.getBoolean("master_autonomy", false),
            emergencyStop = prefs.getBoolean("emergency_stop", false),
            lastPollMs = prefs.getLong("last_poll_ms", 0L),
            nowMs = now
        )
        val action = BridgeRecoveryPolicy.decide(snapshot)

        if (action != BridgeRecoveryPolicy.Action.RESTART_STALE_RUNTIME) {
            prefs.edit()
                .putString("recovery_status", action.name)
                .putLong("last_recovery_ms", now)
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
                        .putLong("control_plane_reentry_ms", now)
                        .commit()
                ) { "Unable to persist canonical control-plane re-entry" }
            }

            val intent = Intent(applicationContext, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            prefs.edit()
                .putString("recovery_status", "BRIDGE_RESTART_REQUESTED_STALE_HEARTBEAT")
                .putLong("last_recovery_ms", now)
                .putLong("recovery_restart_count", prefs.getLong("recovery_restart_count", 0L) + 1L)
                .apply()
            Result.success()
        } catch (t: Throwable) {
            prefs.edit()
                .putString("recovery_status", "DEFERRED_${t.javaClass.simpleName}")
                .putLong("last_recovery_ms", now)
                .putLong("recovery_failure_count", prefs.getLong("recovery_failure_count", 0L) + 1L)
                .apply()
            Result.retry()
        }
    }
}
