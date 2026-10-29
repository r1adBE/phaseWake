package com.phasewake.app.ring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.phasewake.app.R

/** Mitteilungen des Handys: nur der Weckvorgang (Mitklingeln mit der Uhr und Zweitwecker). */
object RingNotifications {
    const val CHANNEL_ALARM = "alarm"
    const val ID_RING = 1

    /**
     * Zweite Kennung der Weck-Mitteilung, abwechselnd mit [ID_RING] ([com.phasewake.app.core.RingAlert.nextId]):
     * Android startet das Vollbild nur bei einer neuen Mitteilung, nie bei einer Aktualisierung (z. B. nach dem Schlummern).
     */
    const val ID_RING_ALT = 7

    /** Request-Codes der Absichten für den Weckbildschirm (je Weg eine, damit die Diagnose den Weg kennt). */
    private const val REQUEST_TAP = 0
    private const val REQUEST_FULL_SCREEN = 1
    private const val REQUEST_ALARM = 2

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, context.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_alarm_description)
                setSound(null, null) // Ton und Vibration steuert der Dienst (sanft ansteigend).
                enableVibration(false)
                setBypassDnd(true)
            },
        )
    }

    fun ringing(context: Context): Notification {
        val open = ringScreen(context, PhoneRingActivity.VIA_TAP, REQUEST_TAP)
        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(context.getString(R.string.ring_greeting))
            .setContentText(context.getString(R.string.ring_notification_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            // Kein setSilent: Es unterdrückt den Vollbildstart. Der Kanal ist ohnehin stumm.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(ringScreen(context, PhoneRingActivity.VIA_FULL_SCREEN, REQUEST_FULL_SCREEN), true)
            .setContentIntent(open)
            // «Öffnen» statt «Stopp»: Beendet wird nur durch Halten auf dem Weckbildschirm.
            .addAction(0, context.getString(R.string.ring_open), open)
            .build()
    }

    /** Weckbildschirm über einen Wecker-Alarm des Systems ([PhoneRingAlert]); gleiche Identität zum Abbrechen. */
    fun ringScreenByAlarm(context: Context): PendingIntent = ringScreen(context, PhoneRingActivity.VIA_ALARM, REQUEST_ALARM)

    private fun ringScreen(context: Context, via: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, PhoneRingActivity::class.java).putExtra(PhoneRingActivity.EXTRA_VIA, via).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
