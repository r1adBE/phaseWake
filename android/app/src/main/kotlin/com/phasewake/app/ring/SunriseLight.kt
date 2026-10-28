package com.phasewake.app.ring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.phasewake.app.R
import com.phasewake.app.core.Sunrise
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sonnenaufgang ab Weckfenster: Das Licht beginnt mit dem Fenster und ist zur Zielzeit voll hell.
 * Geplant wird es von [BackupAlarm], gezeigt von [SunriseActivity]. Weckt die Uhr früher, übernimmt
 * der Weckbildschirm und holt das Licht in [Sunrise.CATCH_UP_SECONDS] Sekunden nach.
 */
object SunriseLight {
    const val CHANNEL = "sunrise"
    private const val NOTIFICATION_ID = 2
    const val EXTRA_START = "sunriseStart"
    const val EXTRA_END = "sunriseEnd"

    /** Ein laufendes Licht für einen Wecker. */
    data class Active(val alarmId: String?, val start: Instant, val end: Instant) {
        fun progress(now: Instant = Instant.now()): Float = Sunrise.progress(start, end, now)
    }

    private val state = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = state.asStateFlow()

    /** Fortschritt des laufenden Lichts für [alarmId], sonst 0. */
    fun progressFor(alarmId: String?): Float = state.value?.takeIf { it.alarmId == alarmId }?.progress() ?: 0f

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_sunrise), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_sunrise_description)
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
            },
        )
    }

    /** Startet das Licht: Mitteilung mit Vollbild, damit es auch bei gesperrtem Handy erscheint. */
    fun start(context: Context, active: Active) {
        state.value = active
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, active))
    }

    /** Nach einem Neustart des Prozesses: laufendes Licht aus der Mitteilung wieder aufnehmen. */
    fun resume(active: Active) {
        if (state.value == null) state.value = active
    }

    /** Beendet das Licht (ausgeschaltet oder vom Weckbildschirm übernommen und beendet). */
    fun stop(context: Context) {
        state.value = null
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun notification(context: Context, active: Active): Notification {
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, SunriseActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(PhoneRingService.EXTRA_ALARM_ID, active.alarmId)
                .putExtra(EXTRA_START, active.start.toEpochMilli())
                .putExtra(EXTRA_END, active.end.toEpochMilli()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(context.getString(R.string.sunrise_title))
            .setContentText(context.getString(R.string.sunrise_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setSilent(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .build()
    }
}
