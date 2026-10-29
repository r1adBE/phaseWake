package com.phasewake.app.ring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.BackupAlarm.Companion.ACTION
import com.phasewake.app.ring.BackupAlarm.Companion.ACTION_SNOOZE_SAFETY
import com.phasewake.app.ring.BackupAlarm.Companion.ACTION_SUNRISE
import com.phasewake.app.ring.BackupAlarm.Companion.ACTION_WATCH_RING
import com.phasewake.app.ring.BackupAlarm.Companion.SYSTEM_ACTIONS
import com.phasewake.app.ring.BackupAlarm.Companion.isUnlocked
import java.time.Instant
import kotlinx.coroutines.launch

// Was die Empfänger des Zweitweckers tun; die Klassen selbst bleiben in [BackupAlarm] (Namen im Manifest).

/** Zweitwecker, Schlummer-Sicherung, Weiterleitung der Uhr und Sonnenaufgang ([BackupAlarm.Receiver]). */
internal fun BroadcastReceiver.onBackupAlarm(context: Context, intent: Intent) {
    val alarmId = intent.getStringExtra(PhoneRingService.EXTRA_ALARM_ID)
    val target = if (intent.hasExtra(PhoneRingService.EXTRA_TARGET)) intent.getLongExtra(PhoneRingService.EXTRA_TARGET, 0L) else null
    when (intent.action) {
        // Mit Zielzeit (nur der Zweitwecker selbst): Beim Beenden am Handy erfährt die Uhr, welche Nacht.
        ACTION -> PhoneRingService.ring(context, alarmId, target = target, snoozesDone = intent.getIntExtra(PhoneRingService.EXTRA_SNOOZES_DONE, 0))
        // Die Uhr hat sich nach dem Schlummern nicht mehr gemeldet: das Handy klingelt selbst.
        ACTION_SNOOZE_SAFETY -> {
            SnoozeSafety.clear(context)
            PhoneRingService.ring(context, alarmId, snoozesDone = intent.getIntExtra(PhoneRingService.EXTRA_SNOOZES_DONE, 0))
            // Vor dem ersten Entsperren: der abgelegte Zweitwecker bleibt, wie er ist.
            if (!isUnlocked(context)) return
        }
        ACTION_WATCH_RING -> {
            val left = if (intent.hasExtra(PhoneRingService.EXTRA_SNOOZES_LEFT)) intent.getIntExtra(PhoneRingService.EXTRA_SNOOZES_LEFT, 0) else null
            PhoneRingService.ring(context, alarmId, fromWatch = true, target = target, snoozesLeft = left)
            return
        }
        ACTION_SUNRISE -> Unit // unten, nach dem Merken
        else -> return
    }
    // Vor dem ersten Entsperren (Neustart in der Nacht) sind Wecker und Einstellungen nicht lesbar.
    if (!isUnlocked(context)) {
        BackupDirectBoot.clear(context)
        // Nach dem Entsperren nachholen: Nacht aussetzen, Einmal-Wecker ausschalten.
        if (intent.action == ACTION && alarmId != null && target != null) BackupDirectBoot.markRang(context, alarmId, target)
        return
    }
    val pending = goAsync()
    val app = context.phoneApp
    app.appScope.launch {
        try {
            BackupDirectBoot.takeRang(context)?.let { (id, at) -> app.backup.backupRang(id, at) }
            if (intent.action == ACTION_SUNRISE) app.backup.startSunrise(intent)
            // Nur der Zweitwecker selbst trägt die Zielzeit, nicht das Ende des Schlummerns.
            if (intent.action == ACTION && target != null) app.backup.backupRang(alarmId, Instant.ofEpochMilli(target))
            // Die Uhr hat diese Nacht nicht beendet: einen Einmal-Wecker schaltet dann das Handy aus.
            if (intent.action == ACTION_SNOOZE_SAFETY) app.backup.disableOneShot(alarmId)
            app.backup.reschedule()
        } finally {
            pending.finish()
        }
    }
}

/** Systemereignisse ([BackupAlarm.SystemReceiver]): neu planen; vor dem ersten Entsperren nur das Abgelegte. */
internal fun BroadcastReceiver.onBackupSystemEvent(context: Context, intent: Intent) {
    if (intent.action !in SYSTEM_ACTIONS) return
    if (!isUnlocked(context)) {
        BackupDirectBoot.restore(context)
        SnoozeSafety.restore(context)
        return
    }
    val pending = goAsync()
    val app = context.phoneApp
    app.appScope.launch {
        try {
            BackupDirectBoot.takeRang(context)?.let { (id, at) -> app.backup.backupRang(id, at) }
            app.backup.reschedule()
        } finally {
            pending.finish()
        }
    }
}
