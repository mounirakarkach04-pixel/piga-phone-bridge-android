package io.piga.phonebridge

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class PigaApplication : Application() {
    companion object {
        @Volatile private var appContext: Context? = null
        fun contextOrNull(): Context? = appContext
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val watchdog = PeriodicWorkRequestBuilder<BridgeWatchdogWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "piga_bridge_watchdog",
            ExistingPeriodicWorkPolicy.UPDATE,
            watchdog
        )
    }
}
