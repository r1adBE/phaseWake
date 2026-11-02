package com.phasewake.app.ring

import androidx.datastore.preferences.core.edit
import com.phasewake.app.core.DiagKind
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.BackupAlarm.Companion.GRACE
import com.phasewake.app.ring.BackupAlarm.Companion.PLANNED_ID
import com.phasewake.app.ring.BackupAlarm.Companion.PLANNED_TARGET
import com.phasewake.app.ring.BackupAlarm.Companion.SUPPRESSED
import com.phasewake.app.ring.BackupAlarm.Companion.decodeSuppressed
import java.time.Instant
import kotlinx.coroutines.flow.first

// Planen des Zweitweckers: nächster Wecker mit Handy-Wecker, dazu Probewecker oder Powernap.

/**
 * Plant Zweitwecker und Sonnenaufgang neu (nur unter der Sperre von [BackupAlarm.reschedule]). Wecker mit
 * «An Feiertagen nicht wecken» überspringen die Feiertage wie auf der Uhr; ein Probewecker oder Powernap
 * mit Handy-Wecker bekommt ebenfalls eine Sicherung.
 */
internal suspend fun BackupAlarm.planBackup() {
    rescheduleSunrise()
    val current = now()
    // Schlummer-Sicherung und Schlummern am Handy sind unabhängig vom Zweitwecker; nach einem Neustart wiederherstellen.
    SnoozeSafety.restore(context, current)
    PhoneSnooze.restore(context, current)
    alarmManager.cancel(pending())
    val app = context.phoneApp
    val holidays = app.holidays.store.current()
    val backups = alarms.current().alarms.filter { it.phoneBackup }
    val prefs = store.data.first()
    // Hat die Uhr eine Nacht übernommen oder klingelte der Zweitwecker schon, gilt für diesen
    // Wecker erst die nächste Nacht danach. Andere Wecker bleiben unberührt.
    val suppressed = decodeSuppressed(prefs[SUPPRESSED])
    // Ohne Uhr (nie oder seit einer Woche keine gemeldet, Review N-5): das Handy ist der Wecker und klingelt pünktlich.
    val grace = RingRules.backupGrace(watchSeenAt(), current.toEpochMilli(), GRACE)
    // Probewecker oder Powernap: eigener Plan, auch in der Nachfrist (er kommt nicht wieder).
    val transient = app.transient.active()?.takeIf { it.spec.phoneBackup }?.plan
    // In der Nachfrist eines schon geplanten Zweitweckers (z. B. beim Prozessstart) bleibt er bestehen.
    val plannedTarget = prefs[PLANNED_TARGET]?.let(Instant::ofEpochMilli)
    // Einschlaf-Modus der Galaxy Watch (Review K-07): Hat sie für diese Nacht eine frühere Zielzeit festgelegt, klingelt
    // auch der Zweitwecker dann (samt Nachfrist); die eingestellte Zeit dieser Nacht gilt danach als erledigt.
    val shift = onsetShift()
    val unshifted = RingRules.backupPlan(backups, transient, prefs[PLANNED_ID], plannedTarget, suppressed, current, grace, zone, holidays)
    if (unshifted == null) {
        if (prefs[PLANNED_ID] != null) store.edit { it.remove(PLANNED_ID); it.remove(PLANNED_TARGET) }
        BackupDirectBoot.clear(context)
        return
    }
    val plan = shift?.applyTo(unshifted, current, grace) ?: unshifted
    if (prefs[PLANNED_ID] != plan.alarmId || prefs[PLANNED_TARGET] != plan.target.toEpochMilli()) {
        store.edit {
            it[PLANNED_ID] = plan.alarmId
            it[PLANNED_TARGET] = plan.target.toEpochMilli()
        }
        app.diag.log(DiagKind.SCHEDULED, "phone backup ${plan.target.plus(grace)}")
    }
    val at = plan.target.plus(grace)
    setAlarm(at, showIntent(), pending(plan.alarmId, plan.target))
    // Für einen Neustart in der Nacht: auch vor dem ersten Entsperren lesbar ablegen, samt den folgenden Zweitweckern
    // (klingelt einer vor dem Entsperren, stellt BackupDirectBoot.advance den nächsten).
    val following = RingRules.directBootPlans(unshifted, backups, transient, suppressed, grace, GRACE, zone, holidays, DIRECT_BOOT_PLANS)
        .map { shift?.applyTo(it, current, grace) ?: it }
    BackupDirectBoot.save(context, following.map { BackupDirectBoot.Entry(it.target.plus(grace), it.alarmId, it.target) })
}

/** So viele Zweitwecker liegen für die Zeit vor dem ersten Entsperren bereit (mehrere Wecker über gut zwei Tage). */
private const val DIRECT_BOOT_PLANS = 6

/** Geplante Zielzeit des Zweitweckers (für die Diagnose); `null`, wenn keiner geplant ist. */
internal suspend fun BackupAlarm.plannedTarget(): Instant? =
    store.data.first()[PLANNED_TARGET]?.let(Instant::ofEpochMilli)
