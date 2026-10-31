package com.deadvault.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.deadvault.app.data.VaultPrefs
import com.deadvault.app.data.VaultWiper
import java.util.concurrent.TimeUnit

/**
 * The dead-man's-switch.
 *
 * deadline = (time of last successful passcode entry) + interval
 *
 * It is enforced from four independent directions so a missed deadline is acted on as soon as the
 * phone gives us a chance to run:
 *  - every process start (Application.onCreate) and every unlock attempt;
 *  - an AlarmManager alarm set for the deadline (and one for the warning);
 *  - a periodic WorkManager job (backup for devices that drop alarms);
 *  - a boot / app-update receiver (a phone that was off at the deadline is wiped when it starts).
 */
object DeadlineEnforcer {

    const val ACTION_CHECK = "com.deadvault.app.DEADLINE_CHECK"
    private const val UNIQUE_WORK = "deadvault_deadline_check"
    private const val REQ_DEADLINE = 1001
    private const val REQ_WARNING = 1002
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /** One lock for every read-modify-write of the deadline state (check, unlock, setup, interval change). */
    private val lock = Any()

    fun <T> atomically(block: () -> T): T = synchronized(lock) { block() }

    /**
     * "Now" as the vault sees it, immune to the phone's clock being changed:
     *  - Within a boot it is anchor + monotonic time elapsed since the anchor (the wall clock is ignored,
     *    so winding the clock back, or a bogus forward jump from a time sync, changes nothing).
     *  - On the first call of a new boot it is max(wall clock, latest persisted effective time), which
     *    accounts for time the phone spent switched off, and becomes the new anchor.
     */
    fun now(context: Context, prefs: VaultPrefs): Long {
        val boot = bootCount(context)
        val elapsed = SystemClock.elapsedRealtime()
        val now = if (boot != -1 && boot == prefs.anchorBoot && elapsed >= prefs.anchorElapsed) {
            prefs.anchorNow + (elapsed - prefs.anchorElapsed)
        } else {
            maxOf(System.currentTimeMillis(), prefs.maxWallSeen).also { prefs.setAnchor(boot, elapsed, it) }
        }
        if (now > prefs.maxWallSeen + 60_000) prefs.maxWallSeen = now // throttled write
        return now
    }

    fun deadlineAt(prefs: VaultPrefs): Long = prefs.lastUnlockWall + prefs.intervalMs

    private fun warnLeadMs(prefs: VaultPrefs): Long = minOf(DAY_MS, prefs.intervalMs / 4)

    /** Milliseconds until the wipe, or null when no vault exists. */
    fun remainingMs(context: Context): Long? {
        val prefs = VaultPrefs(context.applicationContext)
        if (!prefs.isSetUp) return null
        return deadlineAt(prefs) - now(context, prefs)
    }

    /** Call after every successful passcode entry (and on setup). Restarts the countdown. */
    fun markUnlocked(context: Context) {
        atomically {
            val app = context.applicationContext
            val prefs = VaultPrefs(app)
            val now = now(app, prefs)
            prefs.markUnlocked(now, SystemClock.elapsedRealtime(), bootCount(app))
            Notifications.cancelWarning(app)
            schedule(app) // no-op until setup has finished
        }
    }

    /** Returns true if the vault was (or had been half-) wiped by this call. */
    fun check(context: Context): Boolean = synchronized(lock) { checkLocked(context) }

    private fun checkLocked(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = VaultPrefs(app)

        if (prefs.pendingWipe) {
            VaultWiper.wipe(app, prefs.pendingReason ?: REASON_MISSED)
            return true
        }
        if (!prefs.isSetUp) {
            cancelAll(app)
            return false
        }

        val now = now(app, prefs)
        val deadline = deadlineAt(prefs)
        if (now >= deadline) {
            VaultWiper.wipe(app, REASON_MISSED)
            return true
        }

        if (!prefs.warned && now >= deadline - warnLeadMs(prefs)) {
            prefs.warned = true
            Notifications.postWarning(app, deadline)
        }
        schedule(app)
        return false
    }

    fun schedule(context: Context) {
        val app = context.applicationContext
        val prefs = VaultPrefs(app)
        if (!prefs.isSetUp) return

        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = now(app, prefs)
        val wallNow = System.currentTimeMillis()
        val deadline = deadlineAt(prefs)

        // Inexact-but-doze-friendly alarms need no special permission. They may fire a few
        // minutes late; the process-start and unlock checks close any remaining gap.
        alarms.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, wallNow + (deadline - now).coerceAtLeast(1_000), pending(app, REQ_DEADLINE),
        )

        val warnAt = deadline - warnLeadMs(prefs)
        if (!prefs.warned && warnAt > now) {
            alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, wallNow + (warnAt - now), pending(app, REQ_WARNING),
            )
        } else {
            alarms.cancel(pending(app, REQ_WARNING))
        }

        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DeadlineWorker>(3, TimeUnit.HOURS).build(),
        )
    }

    fun cancelAll(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.cancel(pending(app, REQ_DEADLINE))
        alarms.cancel(pending(app, REQ_WARNING))
        WorkManager.getInstance(app).cancelUniqueWork(UNIQUE_WORK)
        Notifications.cancelWarning(app)
    }

    private fun pending(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, DeadlineReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    const val REASON_MISSED = "The passcode was not entered before the deadline."
    const val REASON_ATTEMPTS = "Too many incorrect passcode attempts."
    const val REASON_MANUAL = "The vault was wiped manually."
}
