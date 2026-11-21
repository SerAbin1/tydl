package io.github.serabin1.tydl.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import io.github.serabin1.tydl.domain.GuardState
import io.github.serabin1.tydl.domain.Session
import io.github.serabin1.tydl.ui.MainActivity

object GuardNotifications {

    const val CHANNEL_GUARD = "guard"
    const val CHANNEL_ALERTS = "alerts"
    const val ID_GUARD = 1
    const val ID_ALERT = 2

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GUARD,
                "Guard",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Ongoing state of the Tydl guard" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Warnings when Tydl needs your attention" },
        )
    }

    fun guard(context: Context, state: GuardState): Notification {
        val now = System.currentTimeMillis()
        val session = state.session
        val builder = NotificationCompat.Builder(context, CHANNEL_GUARD)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openMain(context))

        when {
            state.isPaused(now) -> builder
                .setContentTitle("Tydl is paused")
                .setContentText("Back at ${clock(context, state.pausedUntil)}")

            session == null -> builder
                .setContentTitle("Tydl is guarding")
                .setContentText("State your reason when you next unlock.")

            session.isOverrun(now) -> builder
                .setContentTitle("Time's up")
                .setContentText(
                    "\"${session.reason}\" — over by ${overBy(session, now)} min",
                )

            else -> builder
                .setContentTitle(session.reason)
                .setContentText("${session.remainingMinutes(now)} min left")
                .setWhen(session.endsAt)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }
        return builder.build()
    }

    fun alert(
        context: Context,
        title: String,
        text: String,
        target: Class<*>,
        extra: String? = null,
    ): Notification {
        val intent = Intent(context, target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (extra != null) intent.putExtra(extra, true)
        val pending = PendingIntent.getActivity(
            context,
            (target.hashCode() * 31) + (extra?.hashCode() ?: 0),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
    }

    /** A non-blocking warning, e.g. when "I'm done" could not lock the screen. */
    fun warn(context: Context, title: String, text: String) {
        context.getSystemService(NotificationManager::class.java)
            .notify(ID_ALERT, alert(context, title, text, MainActivity::class.java))
    }

    private fun overBy(session: Session, now: Long): Int =
        (((now - session.endsAt).coerceAtLeast(0L)) / Session.MINUTE_MS).toInt()

    private fun clock(context: Context, at: Long): String =
        DateFormat.getTimeFormat(context).format(at)

    private fun openMain(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
