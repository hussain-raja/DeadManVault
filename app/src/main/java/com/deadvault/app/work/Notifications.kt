package com.deadvault.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.deadvault.app.MainActivity
import com.deadvault.app.R
import java.text.DateFormat
import java.util.Date

/** Notifications are deliberately vague and hidden on the lock screen. */
object Notifications {

    private const val CHANNEL = "deadline"
    private const val ID_WARNING = 11
    private const val ID_WIPED = 12

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Check-in reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Reminds you to open DeadVault before its check-in deadline"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                },
            )
        }
    }

    fun postWarning(context: Context, deadlineAt: Long) {
        val whenText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(deadlineAt))
        notify(
            context, ID_WARNING, "DeadVault check-in due",
            "Open DeadVault and enter your passcode before $whenText or everything inside will be erased.",
        )
    }

    fun postWiped(context: Context) {
        notify(context, ID_WIPED, "DeadVault was reset", "The vault was erased. Open the app for details.")
    }

    fun cancelWarning(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_WARNING)
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted; the deadline is still enforced.
        }
    }
}
