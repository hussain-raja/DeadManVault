package com.deadvault.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Periodic backup for the exact alarm. */
class DeadlineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        DeadlineEnforcer.check(applicationContext)
        return Result.success()
    }
}
