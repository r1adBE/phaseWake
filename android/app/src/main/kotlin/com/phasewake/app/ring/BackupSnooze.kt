package com.phasewake.app.ring

import android.app.PendingIntent
import android.content.Intent
import com.phasewake.app.core.Snooze
import com.phasewake.app.ring.BackupAlarm.Companion.ACTION
import com.phasewake.app.ring.BackupAlarm.Companion.safetyPending
import java.time.Instant

// Schlummern über das Handy: Schlummer-Ende und Sicherung nach dem Schlummern auf der Uhr.

/**
 * Sicherung nach dem Schlummern einer Nacht, die die Uhr führt: klingelt um [at] als Zweitwecker
 * dieses Weckers (mit dessen Weckart und Weckton), wenn bis dahin kein neues «klingeln» der Uhr kam.
 * Liegt neben dem Zweitwecker: Dessen Aussetzen über [watchHandled] berührt sie nicht. Mit Handy
 * «Aus» keine Sicherung (wie beim Zweitwecker). [snoozesDone]: bisherige Schlummer-Runden.
 */
internal suspend fun BackupAlarm.snoozeSafety(alarmId: String?, at: Instant, snoozesDone: Int) {
    val spec = alarmId?.let { id -> alarms.current().alarms.firstOrNull { it.id == id } }
    if (spec != null && !spec.phoneBackup) {
        cancelSnoozeSafety(alarmId)
        return
    }
    val done = snoozesDone.coerceIn(0, Snooze.MAX_COUNT)
    // Zuerst ablegen (auch vor dem ersten Entsperren lesbar), dann planen.
    SnoozeSafety.save(context, SnoozeSafety.Saved(at, alarmId, done))
    setAlarm(at, showIntent(), safetyPending(context, alarmId, done))
}

/** Schlummern am Handy: nach [at] erneut klingeln, auch aus dem Tiefschlaf. [snoozes]: bisherige Runden. */
internal fun BackupAlarm.snoozeUntil(at: Instant, alarmId: String?, snoozes: Int) {
    val intent = Intent(context, BackupAlarm.Receiver::class.java).setAction(ACTION)
        .putExtra(PhoneRingService.EXTRA_SNOOZES_DONE, snoozes)
    if (alarmId != null) intent.putExtra(PhoneRingService.EXTRA_ALARM_ID, alarmId)
    setAlarm(at, showIntent(), PendingIntent.getBroadcast(context, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
}

internal fun BackupAlarm.cancelSnooze() = alarmManager.cancel(
    PendingIntent.getBroadcast(
        context, 3, Intent(context, BackupAlarm.Receiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    ),
)
