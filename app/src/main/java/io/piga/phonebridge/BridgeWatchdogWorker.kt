package io.piga.phonebridge

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters

class BridgeWatchdogWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("piga_bridge", Context.MODE_PRIVATE)
        val paired = prefs.getBoolean("paired", false)
        val masterAutonomy = prefs.getBoolean("master_autonomy", false)
        val emergencyStop = prefs.getBoolean("emergency_stop", false)

        if (!paired || !masterAutonomy || emergencyStop) {
            return Result.success()
        }

        return try {
            val intent = Intent(applicationContext, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
