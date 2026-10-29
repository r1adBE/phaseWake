package com.phasewake.app.ring

import androidx.datastore.preferences.core.edit
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.WakePlan
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.BackupAlarm.Companion.GRACE
import com.phasewake.app.ring.BackupAlarm.Companion.PLANNED_ID
import com.phasewake.app.ring.BackupAlarm.Companion.PLANNED_TARGET
import com.phasewake.app.ring.BackupAlarm.Companion.SUPPRESSED
import com.phasewake.app.ring.BackupAlarm.Companion.decodeSuppressed
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first

// Planen des Zweitweckers: nächster Wecker mit Handy-Wecker, dazu Probewecker oder Powernap.

/**
 * Plant Zweitwecker und Sonnenaufgang neu (nur unter der Sperre von [BackupAlarm.reschedule]). Wecker mit
 * «An Feiertagen nicht wecken» überspringen die Feiertage wie auf der Uhr; ein Probewecker oder Powernap
 * mit Handy-Wecker bekommt ebenfalls eine Sicherung.
 */
internal suspend fun BackupAlarm.planBackup() {
    rescheduleSunrise()
    // Eine Schlummer-Sicherung ist unabhängig vom Zweitwecker; nach einem Neustart wiederherstellen.
    SnoozeSafety.restore(context)
    alarmManager.cancel(pending())
    val app = context.phoneApp
    val holidays = app.holidays.store.current()
    val backups = alarms.current().alarms.filter { it.phoneBackup }
    val prefs = store.data.first()
    // Hat die Uhr eine Nacht übernommen oder klingelte der Zweitwecker schon, gilt für diesen
    // Wecker erst die nächste Nacht danach. Andere Wecker bleiben unberührt.
    val suppressed = decodeSuppressed(prefs[SUPPRESSED])
    val current = now()
    // Ohne Uhr (nie eine gemeldet): das Handy ist der Wecker und klingelt pünktlich zur Zielzeit.
    val grace = if (sync.watchReady() == null) Duration.ZERO else GRACE
    val upcoming = backups.mapNotNull { spec ->
        AlarmSchedule.nextPlan(spec, maxOf(current, suppressed[spec.id] ?: Instant.EPOCH), zone, holidays)
    }
    // Probewecker oder Powernap: eigener Plan, auch in der Nachfrist (er kommt nicht wieder).
    val transient = app.transient.active()?.takeIf { it.spec.phoneBackup }?.plan?.takeIf { plan ->
        plan.target.plus(grace).isAfter(current) && (suppressed[plan.alarmId] ?: Instant.EPOCH) < plan.target
    }
    val plan = (upcoming + listOfNotNull(inGrace(backups, prefs[PLANNED_ID], prefs[PLANNED_TARGET], suppressed, grace, holidays), transient))
        .minWithOrNull(compareBy({ it.target }, { it.alarmId }))
    if (plan == null) {
        if (prefs[PLANNED_ID] != null) store.edit { it.remove(PLANNED_ID); it.remove(PLANNED_TARGET) }
        BackupDirectBoot.clear(context)
        return
    }
    if (prefs[PLANNED_ID] != plan.alarmId || prefs[PLANNED_TARGET] != plan.target.toEpochMilli()) {
        store.edit {
            it[PLANNED_ID] = plan.alarmId
            it[PLANNED_TARGET] = plan.target.toEpochMilli()
        }
        app.diag.log(DiagKind.SCHEDULED, "phone backup ${plan.target.plus(grace)}")
    }
    val at = plan.target.plus(grace)
    setAlarm(at, showIntent(), pending(plan.alarmId, plan.target))
    // Für einen Neustart in der Nacht: auch vor dem ersten Entsperren lesbar ablegen.
    BackupDirectBoot.save(context, at, plan.alarmId, plan.target)
}

/**
 * Läuft gerade die Nachfrist eines schon geplanten Zweitweckers, bleibt er bestehen (Neuplanen zwischen
 * Zielzeit und Zielzeit + Nachfrist, z. B. beim Prozessstart). Sonst `null`.
 */
private fun BackupAlarm.inGrace(
    backups: List<com.phasewake.app.core.AlarmSpec>,
    id: String?,
    targetMs: Long?,
    suppressed: Map<String, Instant>,
    grace: Duration,
    holidays: Set<LocalDate>,
): WakePlan? {
    val spec = backups.firstOrNull { it.id == id } ?: return null
    val target = Instant.ofEpochMilli(targetMs ?: 0L)
    val current = now()
    val valid = !target.isAfter(current) && target.plus(grace).isAfter(current) &&
        AlarmSchedule.nextTarget(spec, target.minusMillis(1), zone, holidays) == target &&
        (suppressed[spec.id] ?: Instant.EPOCH) < target
    return if (valid) WakePlan(spec.id, AlarmSchedule.windowStart(target, spec.windowMinutes), target) else null
}

/** Geplante Zielzeit des Zweitweckers (für die Diagnose); `null`, wenn keiner geplant ist. */
internal suspend fun BackupAlarm.plannedTarget(): Instant? =
    store.data.first()[PLANNED_TARGET]?.let(Instant::ofEpochMilli)
