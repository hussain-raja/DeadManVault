package com.deadvault.app

import android.app.Application
import com.deadvault.app.work.DeadlineEnforcer
import com.deadvault.app.work.Notifications

class DeadVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        // Any time our process starts (app launch, alarm, boot, WorkManager) enforce the deadline.
        Thread { runCatching { DeadlineEnforcer.check(this) } }.start()
    }
}
