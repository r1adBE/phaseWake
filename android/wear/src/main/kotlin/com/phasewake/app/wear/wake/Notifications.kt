package com.phasewake.app.wear.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.MainActivity
import com.phasewake.app.wear.ui.RingActivity

/** Mitteilungen der Uhr: ruhige Daueranzeige während des Fensters, laute beim Wecken. */
object Notifications {
    const val CHANNEL_WINDOW = "window"
    const val CHANNEL_ALARM = "alarm"
    const val ID_SERVICE = 1

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_WINDOW, context.getString(R.string.channel_window), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_window_description) },
            NotificationChannel(CHANNEL_ALARM, context.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = context.getString(R.string.channel_alarm_description)
                    // Die Vibration steuert der Dienst selbst (Steigerung in drei Stufen).
                    enableVibration(false)
                    setSound(null, null)
                    setBypassDnd(true)
                },
        ))
    }

    /** Daueranzeige während der Messung, mit laufender Aktivität auf dem Zifferblatt. */
    fun window(context: Context, targetText: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.getString(R.string.notification_window_text, targetText)
        val builder = NotificationCompat.Builder(context, CHANNEL_WINDOW)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(context.getString(R.string.notification_window_title))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
        OngoingActivity.Builder(context, ID_SERVICE, builder)
            .setStaticIcon(R.drawable.ic_moon)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(text).build())
            .build()
            .apply(context)
        return builder.build()
    }

    /** Daueranzeige nach «Gute Nacht», solange auf das Einschlafen gewartet wird. */
    fun goodNight(context: Context): Notification {
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.getString(R.string.notification_good_night_text)
        val builder = NotificationCompat.Builder(context, CHANNEL_WINDOW)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(context.getString(R.string.notification_good_night_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
        OngoingActivity.Builder(context, ID_SERVICE, builder)
            .setStaticIcon(R.drawable.ic_moon)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(text).build())
            .build()
            .apply(context)
        return builder.build()
    }

    /** Weck-Mitteilung mit Vollbild-Absicht: öffnet den Weckbildschirm auch bei gesperrter Uhr. */
    fun ringing(context: Context, text: String): Notification {
        val ring = PendingIntent.getActivity(
            context, 1, Intent(context, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(context.getString(R.string.notification_ring_title))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setSilent(true)
            .setFullScreenIntent(ring, true)
            .setContentIntent(ring)
            .build()
    }
}
