package com.phasewake.app.wear.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.phasewake.app.core.Snooze
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.MainActivity
import com.phasewake.app.wear.ui.RingActivity
import com.phasewake.app.wear.ui.SunriseActivity

/** Mitteilungen der Uhr: ruhige Daueranzeige während des Fensters, Weck-Mitteilung mit Vollbild beim Wecken (Vibration steuert der Dienst). */
object Notifications {
    const val CHANNEL_WINDOW = "window"
    const val CHANNEL_ALARM = "alarm"
    const val ID_SERVICE = 1

    /** Sonnenaufgang ab Weckfenster ([WatchSunrise]), neben der Daueranzeige der Messung. */
    const val ID_SUNRISE = 2

    /**
     * Weck-Mitteilung, abwechselnd unter zwei Kennungen ([com.phasewake.app.core.RingAlert.nextId]): Android
     * startet das Vollbild nur bei einer neuen Mitteilung, nie bei der Aktualisierung von [ID_SERVICE].
     */
    const val ID_RING = 3
    const val ID_RING_ALT = 4

    /** Request-Codes der Absichten für den Weckbildschirm (je Weg eine, damit die Diagnose den Weg kennt). */
    private const val REQUEST_RING_TAP = 1
    private const val REQUEST_RING_FULL_SCREEN = 5
    private const val REQUEST_RING_ALARM = 6

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

    /** Daueranzeige beim Schlummern: wann es wieder klingelt. */
    fun snoozed(context: Context, untilText: String): Notification {
        // Öffnet den Weckbildschirm: Dort lässt sich die Nacht auch beim Schlummern durch Halten beenden.
        val open = ringScreen(context)
        return NotificationCompat.Builder(context, CHANNEL_WINDOW)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(context.getString(R.string.notification_ring_title))
            .setContentText(context.getString(R.string.ring_snoozed_until, untilText))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
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

    /**
     * Weck-Mitteilung mit Vollbild-Absicht: öffnet den Weckbildschirm auch bei gesperrter Uhr.
     *
     * Ohne `setSilent`: Das legt eine stille Gruppe an, und das System unterdrückt dann den
     * Vollbildstart. Still ist sie trotzdem, der Kanal hat weder Ton noch Vibration.
     * Stoppen nur auf dem Weckbildschirm durch Halten; die Mitteilung bietet Öffnen und Schlummern.
     */
    fun ringing(context: Context, text: String, snoozesLeft: Int): Notification {
        val ring = ringScreen(context)
        val fullScreen = ringScreen(context, RingActivity.VIA_FULL_SCREEN, REQUEST_RING_FULL_SCREEN)
        val builder = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(context.getString(R.string.notification_ring_title))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(ring)
            .addAction(R.drawable.ic_moon, context.getString(R.string.notification_open), ring)
        if (snoozesLeft > 0) {
            val snooze = PendingIntent.getService(
                context, 3, Intent(context, WakeService::class.java).setAction(WakeService.ACTION_SNOOZE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val label = context.resources.getQuantityString(R.plurals.ring_snooze, snoozesLeft, Snooze.MINUTES, snoozesLeft)
            builder.addAction(R.drawable.ic_moon, label, snooze)
        }
        return builder.build()
    }

    /**
     * Sonnenaufgang ab Weckfenster: Vollbild-Absicht öffnet [SunriseActivity] auch bei dunklem Display.
     * Kanal «Wecker» (ohne Ton und Vibration), sonst ignoriert das System die Vollbild-Absicht; ohne
     * `setSilent` aus demselben Grund wie bei [ringing]. Texte wie die Daueranzeige der Messung.
     */
    fun sunrise(context: Context, targetText: String): Notification {
        val open = PendingIntent.getActivity(
            context, 4, Intent(context, SunriseActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(context.getString(R.string.notification_window_title))
            .setContentText(context.getString(R.string.notification_window_text, targetText))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .build()
    }

    /** Weckbildschirm über einen Wecker-Alarm des Systems (siehe `RingScreenAlert.kt`); gleiche Identität zum Abbrechen. */
    fun ringScreenByAlarm(context: Context): PendingIntent = ringScreen(context, RingActivity.VIA_ALARM, REQUEST_RING_ALARM)

    /** Entfernt alle Mitteilungen des Weckdienstes (Daueranzeige und beide Weck-Kennungen). */
    fun cancelService(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        listOf(ID_SERVICE, ID_RING, ID_RING_ALT).forEach(manager::cancel)
    }

    private fun ringScreen(context: Context, via: String = RingActivity.VIA_TAP, requestCode: Int = REQUEST_RING_TAP): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, Intent(context, RingActivity::class.java).putExtra(RingActivity.EXTRA_VIA, via),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
