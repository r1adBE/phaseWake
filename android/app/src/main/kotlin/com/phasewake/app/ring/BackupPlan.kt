package com.phasewake.app.ring

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.WakePlan
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.BackupAlarm.Companion.GRACE
import com.phasewake.app.ring.BackupAlarm.Companion.PLANNED_ID
import com.phasewake.app.ring.BackupAlarm.Companion.PLANNED_TARGET
import com.phasewake.app.ring.BackupAlarm.Companion.SUPPRESSED
import com.phasewake.app.ring.BackupAlarm.Companion.decodeSuppressed
import com.phasewake.app.shared.OnsetShift
import java.time.Instant
import java.time.ZoneId
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
    // Zone und eingestellte Zielzeit, mit denen dieser Zweitwecker geplant wurde: Lief sein Fenster schon beim
    // Zonenwechsel, bleibt er (RingRules.keptBackup), auch mit früherer Zielzeit des Einschlaf-Modus.
    val planned = plannedBackup(prefs, plannedTarget, shift)
    val unshifted = RingRules.backupPlan(
        backups, transient, prefs[PLANNED_ID], plannedTarget, suppressed, current, grace, zone, holidays, planned,
    )
    if (unshifted == null) {
        if (prefs[PLANNED_ID] != null) {
            store.edit {
                it.remove(PLANNED_ID)
                it.remove(PLANNED_TARGET)
                it.remove(PLANNED_ZONE)
                it.remove(PLANNED_BASE)
            }
        }
        BackupDirectBoot.clear(context)
        return
    }
    val kept = RingRules.keptBackup(backups, planned, suppressed, current, grace, zone, holidays)
    if (kept != null && kept == unshifted && kept != loggedKept) {
        loggedKept = kept
        app.diag.log(DiagKind.SCHEDULED, "phone zone change, keep night ${kept.target}")
    }
    val plan = shift?.applyTo(unshifted, current, grace) ?: unshifted
    // Neue Nacht (Wecker oder eingestellte Zielzeit anders): mit der aktuellen Zone; dieselbe Nacht behält die Zone
    // ihrer Planung, auch wenn der Einschlaf-Modus nur die Zielzeit vorverlegt oder die Zone gewechselt hat.
    // Ältere Daten ohne eingestellte Zielzeit: die gespeicherte Zielzeit.
    val newNight = prefs[PLANNED_ID] != unshifted.alarmId || planned?.target != unshifted.target
    val changed = prefs[PLANNED_ID] != plan.alarmId || prefs[PLANNED_TARGET] != plan.target.toEpochMilli()
    // Kommt ein Probewecker oder Powernap vor einer behaltenen Nacht, bleibt deren Eintrag für danach stehen.
    val keepEntry = kept != null && unshifted != kept
    if (!keepEntry && (newNight || changed || prefs[PLANNED_BASE] == null)) {
        store.edit {
            it[PLANNED_ID] = plan.alarmId
            it[PLANNED_TARGET] = plan.target.toEpochMilli()
            it[PLANNED_BASE] = unshifted.target.toEpochMilli()
            if (newNight) it[PLANNED_ZONE] = zone.id
        }
    }
    // Nachgeholt (RingRules.catchUp): Zielzeit + Nachfrist schon vorbei, dann gleich.
    val at = RingRules.backupAt(plan, grace, current)
    if (newNight || changed) {
        app.diag.log(DiagKind.SCHEDULED, "phone backup $at")
    }
    setAlarm(at, showIntent(), pending(plan.alarmId, plan.target))
    // Für einen Neustart in der Nacht: auch vor dem ersten Entsperren lesbar ablegen, samt den folgenden Zweitweckern
    // (klingelt einer vor dem Entsperren, stellt BackupDirectBoot.advance den nächsten).
    val following = RingRules.directBootPlans(unshifted, backups, transient, suppressed, grace, GRACE, zone, holidays, DIRECT_BOOT_PLANS)
        .map { shift?.applyTo(it, current, grace) ?: it }
    BackupDirectBoot.save(context, following.map { BackupDirectBoot.Entry(it.target.plus(grace), it.alarmId, it.target) })
}

/** Zone, in der der geplante Zweitwecker ([PLANNED_TARGET]) berechnet wurde; fehlt bei älteren Daten. */
private val PLANNED_ZONE = stringPreferencesKey("backup_planned_zone")

/** Eingestellte Zielzeit (ohne Einschlaf-Modus) der Nacht des geplanten Zweitweckers; fehlt bei älteren Daten. */
private val PLANNED_BASE = longPreferencesKey("backup_planned_base")

/**
 * Geplanter Zweitwecker für [RingRules.keptBackup]: eingestellte Zielzeit [PLANNED_BASE] (ältere Daten: [plannedTarget]),
 * Zone der Planung und die frühere Zielzeit aus [shift], wenn sie diese Nacht vorverlegt. `null` ohne geplanten.
 */
private fun plannedBackup(prefs: Preferences, plannedTarget: Instant?, shift: OnsetShift?): RingRules.PlannedBackup? {
    val id = prefs[PLANNED_ID] ?: return null
    val base = prefs[PLANNED_BASE]?.let(Instant::ofEpochMilli) ?: plannedTarget ?: return null
    val zone = prefs[PLANNED_ZONE]?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    val earlier = shift?.takeIf { it.alarmId == id && it.original == base.toEpochMilli() && it.target < it.original }
        ?.let { Instant.ofEpochMilli(it.target) }
    return RingRules.PlannedBackup(id, base, zone, earlier)
}

/** Zuletzt protokollierte, über einen Zonenwechsel behaltene Nacht; ein Eintrag nur bei einer Änderung. */
@Volatile
private var loggedKept: WakePlan? = null

/** So viele Zweitwecker liegen für die Zeit vor dem ersten Entsperren bereit (mehrere Wecker über gut zwei Tage). */
private const val DIRECT_BOOT_PLANS = 6

/** Geplante Zielzeit des Zweitweckers (für die Diagnose); `null`, wenn keiner geplant ist. */
internal suspend fun BackupAlarm.plannedTarget(): Instant? =
    store.data.first()[PLANNED_TARGET]?.let(Instant::ofEpochMilli)
