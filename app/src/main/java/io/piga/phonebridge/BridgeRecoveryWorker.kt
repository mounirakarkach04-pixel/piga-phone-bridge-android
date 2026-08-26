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
        val previousHeartbeat = prefs.getLong("runtime_heartbeat_ms", 0L)
        val snapshot = BridgeRecoveryPolicy.Snapshot(
            paired = prefs.getBoolean("paired", false),
            masterAutonomy = prefs.getBoolean("master_autonomy", false),
            emergencyStop = prefs.getBoolean("emergency_stop", false),
            runtimeHeartbeatMs = previousHeartbeat,
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

            // A7SEM Reverse: a restart request is not recovery evidence. Give the runtime
            // a small bounded window to publish a fresh local heartbeat and only then mark
            // the repair as verified. Otherwise defer and let WorkManager backoff/re-enter.
            var verifiedHeartbeat = 0L
            var attempts = 0
            while (attempts < 10 && verifiedHeartbeat == 0L) {
                val observed = prefs.getLong("runtime_heartbeat_ms", 0L)
                if (observed > previousHeartbeat && System.currentTimeMillis() - observed <= 5_000L) {
                    verifiedHeartbeat = observed
                } else {
                    attempts += 1
                    if (attempts < 10) Thread.sleep(500L)
                }
            }

            val restartCount = prefs.getLong("recovery_restart_count", 0L) + 1L
            if (verifiedHeartbeat > 0L) {
                prefs.edit()
                    .putString("recovery_status", "BRIDGE_RESTART_VERIFIED")
                    .putLong("last_recovery_ms", System.currentTimeMillis())
                    .putLong("recovery_restart_count", restartCount)
                    .apply()
                Result.success()
            } else {
                prefs.edit()
                    .putString("recovery_status", "BRIDGE_RESTART_UNVERIFIED")
                    .putLong("last_recovery_ms", System.currentTimeMillis())
                    .putLong("recovery_restart_count", restartCount)
                    .putLong("recovery_failure_count", prefs.getLong("recovery_failure_count", 0L) + 1L)
                    .apply()
                Result.retry()
            }
        } catch (t: Throwable) {
            prefs.edit()
                .putString("recovery_status", "DEFERRED_${t.javaClass.simpleName}")
                .putLong("last_recovery_ms", System.currentTimeMillis())
                .putLong("recovery_failure_count", prefs.getLong("recovery_failure_count", 0L) + 1L)
                .apply()
            Result.retry()
        }
    }
}
