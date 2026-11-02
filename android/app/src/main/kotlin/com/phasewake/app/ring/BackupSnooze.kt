package com.phasewake.app.ring

import android.app.PendingIntent
import android.content.Context
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

/**
 * Schlummern am Handy: nach [at] erneut klingeln, auch aus dem Tiefschlaf. [snoozes]: bisherige Runden. Zuerst im
 * geräteverschlüsselten Speicher abgelegt ([PhoneSnooze]), damit das Schlummer-Ende einen Neustart übersteht.
 */
internal fun BackupAlarm.snoozeUntil(at: Instant, alarmId: String?, snoozes: Int) {
    PhoneSnooze.save(context, PhoneSnooze.Saved(at, alarmId, snoozes))
    setAlarm(at, showIntent(), snoozePending(context, alarmId, snoozes))
}

/** Hebt das Schlummern am Handy auf: Alarm und Abgelegtes (es klingelt wieder oder wurde beendet). */
internal fun BackupAlarm.cancelSnooze() {
    alarmManager.cancel(snoozePending(context, null, 0))
    PhoneSnooze.clear(context)
}

/**
 * Alarm zum Schlummer-Ende am Handy: Aktion des Zweitweckers ohne Zielzeit (nicht noch einmal [BackupAlarm.backupRang]),
 * eigener Request-Code 3. Gleiche Absicht beim Stellen, Wiederherstellen ([PhoneSnooze.restore]) und Aufheben.
 */
internal fun snoozePending(context: Context, alarmId: String?, snoozes: Int): PendingIntent {
    val intent = Intent(context, BackupAlarm.Receiver::class.java).setAction(ACTION)
        .putExtra(PhoneRingService.EXTRA_SNOOZES_DONE, snoozes)
    if (alarmId != null) intent.putExtra(PhoneRingService.EXTRA_ALARM_ID, alarmId)
    return PendingIntent.getBroadcast(context, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
