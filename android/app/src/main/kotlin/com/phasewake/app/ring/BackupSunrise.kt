package com.phasewake.app.ring

import android.app.PendingIntent
import android.content.Intent
import androidx.datastore.preferences.core.edit
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.Sunrise
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.BackupAlarm.Companion.ACTION_SUNRISE
import com.phasewake.app.ring.BackupAlarm.Companion.SUNRISE_SHOWN_UNTIL
import java.time.Instant
import kotlinx.coroutines.flow.first

// Sonnenaufgang ab Weckfenster auf dem Handy: planen und starten.

/** Sonnenaufgang ab Weckfenster: beginnt mit dem Fenster des nächsten Weckers, der ihn auf dem Handy will. */
internal suspend fun BackupAlarm.rescheduleSunrise() {
    alarmManager.cancel(sunrisePending())
    val wanted = alarms.current().alarms.filter { it.phoneSunrise == Sunrise.WINDOW }
    // Ein bereits gezeigtes Licht nicht noch einmal starten.
    val shown = Instant.ofEpochMilli(store.data.first()[SUNRISE_SHOWN_UNTIL] ?: 0L)
    val holidays = context.phoneApp.holidays.store.current()
    val plan = AlarmSchedule.earliestPlan(wanted, maxOf(now(), shown), zone, holidays) ?: return
    val at = maxOf(plan.windowStart, now())
    // Kein Wecker-Alarm: sonst zeigten Sperrbildschirm und andere Apps den Fensterbeginn als Weckzeit.
    setAlarm(at, show = null, operation = sunrisePending(plan.alarmId, plan.windowStart, plan.target))
}

private fun BackupAlarm.sunrisePending(alarmId: String? = null, start: Instant? = null, end: Instant? = null): PendingIntent {
    val intent = Intent(context, BackupAlarm.Receiver::class.java).setAction(ACTION_SUNRISE)
    if (alarmId != null) intent.putExtra(PhoneRingService.EXTRA_ALARM_ID, alarmId)
    if (start != null) intent.putExtra(SunriseLight.EXTRA_START, start.toEpochMilli())
    if (end != null) intent.putExtra(SunriseLight.EXTRA_END, end.toEpochMilli())
    return PendingIntent.getBroadcast(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** Licht starten und merken, damit es nach dem Neuplanen nicht erneut beginnt. */
internal suspend fun BackupAlarm.startSunrise(intent: Intent) {
    val end = Instant.ofEpochMilli(intent.getLongExtra(SunriseLight.EXTRA_END, 0L))
    store.edit { it[SUNRISE_SHOWN_UNTIL] = end.toEpochMilli() }
    if (end.isAfter(now())) {
        SunriseLight.start(
            context,
            SunriseLight.Active(
                alarmId = intent.getStringExtra(PhoneRingService.EXTRA_ALARM_ID),
                start = Instant.ofEpochMilli(intent.getLongExtra(SunriseLight.EXTRA_START, 0L)),
                end = end,
            ),
        )
    }
}
