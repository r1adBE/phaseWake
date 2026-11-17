package com.phasewake.app.ring

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import com.phasewake.app.ring.BackupAlarm.Companion.ACTION_WATCH_RING
import com.phasewake.app.ring.BackupAlarm.Companion.FORWARD_DELAY
import com.phasewake.app.ring.BackupAlarm.Companion.TAG
import com.phasewake.app.ring.BackupAlarm.Companion.canScheduleExact
import com.phasewake.app.shared.RingMessage

// Weiterleiten der Weckmeldung der Uhr an den Weckdienst des Handys (über einen Wecker-Alarm).

/**
 * Die Meldung kommt im Hintergrund an; von dort darf ab Android 12 kein Vordergrund-Dienst
 * starten. Ein Wecker-Alarm in einer Sekunde darf es: über ihn zum [BackupAlarm.Receiver].
 */
internal fun BackupAlarm.forwardRing(message: RingMessage) {
    val intent = Intent(context, BackupAlarm.Receiver::class.java).setAction(ACTION_WATCH_RING)
        .putExtra(PhoneRingService.EXTRA_ALARM_ID, message.alarmId)
    message.target?.let { intent.putExtra(PhoneRingService.EXTRA_TARGET, it) }
    message.snoozesLeft?.let { intent.putExtra(PhoneRingService.EXTRA_SNOOZES_LEFT, it) }
    if (canScheduleExact(context)) {
        val operation = PendingIntent.getBroadcast(
            context, 4, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(now().plus(FORWARD_DELAY).toEpochMilli(), showIntent()), operation,
            )
            return
        } catch (e: SecurityException) {
            Log.w(TAG, "Exakter Alarm nicht erlaubt", e)
        }
    }
    // Ohne exakte Alarme: direkt versuchen (kann ab Android 12 im Hintergrund scheitern).
    PhoneRingService.ring(
        context, message.alarmId, fromWatch = true, target = message.target, snoozesLeft = message.snoozesLeft,
    )
}
