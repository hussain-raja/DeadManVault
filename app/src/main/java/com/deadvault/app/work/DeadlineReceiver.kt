package com.deadvault.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires for the deadline/warning alarms, after boot and after an app update. */
class DeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                runCatching { DeadlineEnforcer.check(context) }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
