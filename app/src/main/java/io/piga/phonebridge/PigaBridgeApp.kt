package io.piga.phonebridge

import android.app.Application

class PigaBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BridgeRecoveryScheduler.ensureScheduled(this)
    }
}
