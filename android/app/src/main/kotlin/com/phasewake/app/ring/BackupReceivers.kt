package com.phasewake.app.ring

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phasewake.app.core.DiagKind
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
    val done = intent.getIntExtra(PhoneRingService.EXTRA_SNOOZES_DONE, 0)
    // Ob dieses Wecken klingelt (neu oder als Ablösung eines laufenden); nur dann gilt die Nacht als geweckt.
    val rings = when (intent.action) {
        // Mit Zielzeit (nur der Zweitwecker selbst): Beim Beenden am Handy erfährt die Uhr, welche Nacht.
        ACTION -> PhoneRingService.ring(context, alarmId, target = target, snoozesDone = done).rings
        // Die Uhr hat sich nach dem Schlummern nicht mehr gemeldet: das Handy klingelt selbst.
        ACTION_SNOOZE_SAFETY -> {
            SnoozeSafety.clear(context)
            val started = PhoneRingService.ring(context, alarmId, snoozesDone = done).rings
            // Vor dem ersten Entsperren: der abgelegte Zweitwecker bleibt, wie er ist.
            if (!isUnlocked(context)) return
            started
        }
        ACTION_WATCH_RING -> {
            val left = if (intent.hasExtra(PhoneRingService.EXTRA_SNOOZES_LEFT)) intent.getIntExtra(PhoneRingService.EXTRA_SNOOZES_LEFT, 0) else null
            PhoneRingService.ring(context, alarmId, fromWatch = true, target = target, snoozesLeft = left)
            return
        }
        ACTION_SUNRISE -> false // unten, nach dem Merken
        else -> return
    }
    // Vor dem ersten Entsperren (Neustart in der Nacht) sind Wecker und Einstellungen nicht lesbar.
    if (!isUnlocked(context)) {
        // Ende des Schlummerns am Handy (ohne Zielzeit): der abgelegte Zweitwecker bleibt, wie er ist.
        if (intent.action == ACTION && target == null) return
        // Nach dem Entsperren nachholen: Nacht aussetzen, Einmal-Wecker ausschalten.
        if (intent.action == ACTION && alarmId != null && target != null && rings) BackupDirectBoot.markRang(context, alarmId, target)
        // Den nächsten abgelegten Zweitwecker stellen (zweiter Wecker am Morgen, nächste Nacht).
        if (intent.action == ACTION) BackupDirectBoot.advance(context, alarmId, target)
        return
    }
    val pending = goAsync()
    val app = context.phoneApp
    app.appScope.launch {
        try {
            BackupDirectBoot.takeRang(context).forEach { (id, at) -> app.backup.backupRang(id, at) }
            com.phasewake.app.transient.Transients.catchUpEnded(app)
            if (intent.action == ACTION_SUNRISE) app.backup.startSunrise(intent)
            // Nur der Zweitwecker selbst trägt die Zielzeit, nicht das Ende des Schlummerns; und nur, wenn er klingelt.
            if (intent.action == ACTION && target != null && rings) app.backup.backupRang(alarmId, Instant.ofEpochMilli(target))
            if (intent.action == ACTION && !rings) app.diag.log(DiagKind.RING, "phone backup not started")
            // Die Uhr hat diese Nacht nicht beendet: einen Einmal-Wecker schaltet dann das Handy aus.
            if (intent.action == ACTION_SNOOZE_SAFETY && rings) app.backup.disableOneShot(alarmId)
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
        PhoneSnooze.restore(context)
        return
    }
    val pending = goAsync()
    val app = context.phoneApp
    // Startete der Prozess vor dem Entsperren, holt er hier dessen Start-Arbeiten nach (Review M-1; sonst nichts).
    app.initUnlocked()
    app.appScope.launch {
        try {
            BackupDirectBoot.takeRang(context).forEach { (id, at) -> app.backup.backupRang(id, at) }
            com.phasewake.app.transient.Transients.catchUpEnded(app)
            // Neustart, bei dem der Speicher gleich entsperrt war (ohne Sperre oder schnell entsperrt): ein Zweitwecker,
            // der währenddessen fällig wurde, wird hier nachgeholt (Audit A-5), wie vor dem Entsperren in restore.
            if (intent.action in BOOT_ACTIONS) app.backup.catchUpAfterBoot()
            app.backup.reschedule()
        } finally {
            pending.finish()
        }
    }
}

/** Neustart-Ereignisse; nur nach ihnen fehlt ein verpasster Zweitwecker (Alarme sind beim Neustart weg). */
@android.annotation.SuppressLint("InlinedApi")
private val BOOT_ACTIONS = setOf(Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED)

/**
 * Holt nach einem Neustart mit entsperrtem Speicher den abgelegten Zweitwecker nach, der während des Neustarts fällig
 * wurde ([BackupDirectBoot.missed], Audit A-5): gleich danach über einen eigenen Alarm (nur ein Wecker-Alarm darf den
 * Weckdienst aus dem Hintergrund starten). Nicht, wenn die Nacht schon erledigt ist (die Uhr hat sie übernommen oder der
 * Zweitwecker hat geklungen, [BackupAlarm.doneUntil]). Der Eintrag wird entfernt und die Nacht bis zu ihrer Zielzeit
 * ausgesetzt, damit sie nicht zweimal klingelt.
 * @return der nachgeholte Eintrag oder `null`.
 */
internal suspend fun BackupAlarm.catchUpAfterBoot(current: Instant = now()): BackupDirectBoot.Entry? {
    val entry = BackupDirectBoot.missed(context, current) ?: return null
    BackupDirectBoot.remove(context, entry)
    val done = doneUntil()[entry.alarmId]
    if (done != null && !done.isBefore(entry.target)) return null
    BackupAlarm.setExact(context, current.plus(BackupAlarm.FORWARD_DELAY), showIntent(), catchUpPending(context, entry.alarmId, entry.target))
    // Diese Nacht gilt ab jetzt als übernommen: Das folgende Planen holt sie nicht ein zweites Mal nach (RingRules.catchUp).
    suppress(mapOf(entry.alarmId to entry.target))
    context.phoneApp.diag.log(DiagKind.RING, "phone backup missed during boot, catch up")
    return entry
}

/**
 * Nachgeholter Zweitwecker nach einem Neustart mit entsperrtem Speicher (Audit A-5): wie der Zweitwecker ([ACTION], mit
 * Kennung und Zielzeit), aber eigener Request-Code, damit das folgende Planen ihn nicht ersetzt.
 */
internal fun catchUpPending(context: Context, alarmId: String, target: Instant): PendingIntent {
    val intent = Intent(context, BackupAlarm.Receiver::class.java).setAction(ACTION)
        .putExtra(PhoneRingService.EXTRA_ALARM_ID, alarmId)
        .putExtra(PhoneRingService.EXTRA_TARGET, target.toEpochMilli())
    return PendingIntent.getBroadcast(context, REQUEST_CATCH_UP, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** Request-Code von [catchUpPending] (0: Zweitwecker, 3: Schlummern am Handy, 4: Weiterleitung, 5: Sicherung). */
private const val REQUEST_CATCH_UP = 10
