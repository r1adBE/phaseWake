package com.phasewake.app.wear.schedule

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.WakePlan
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/**
 * Zuletzt geplante Nacht eines echten Weckers samt der Zeitzone, in der sie berechnet wurde (Review 04.11. b, P1).
 * Wechselt die Zone, während ihr Weckfenster schon läuft, bleibt diese Nacht zur geplanten absoluten Zeit
 * ([ScheduleRules.keptNight]). Die Zone bleibt, solange derselbe Plan gilt – auch ein zweites Neuplanen nach dem
 * Wechsel prüft in der alten Zone; erst ein neuer Plan speichert die aktuelle. Ältere Daten ohne Zone: wie bisher.
 */
internal class PlannedNight(
    private val store: DataStore<Preferences>,
    /** Eintrag im Diagnoseprotokoll, wenn eine Nacht über einen Zonenwechsel bleibt. */
    private val log: (String) -> Unit,
) {
    /** Zuletzt protokollierte behaltene Nacht; ein Eintrag nur bei einer Änderung (neu geplant wird oft). */
    @Volatile
    private var logged: WakePlan? = null

    /** Gespeicherte Nacht; `null`, wenn keine. Eine unlesbare Zone gilt als fehlend. */
    suspend fun load(): ScheduleRules.Planned? {
        val prefs = store.data.first()
        val id = prefs[ID] ?: return null
        val target = prefs[TARGET]?.let(Instant::ofEpochMilli) ?: return null
        return ScheduleRules.Planned(id, target, prefs[ZONE]?.let { runCatching { ZoneId.of(it) }.getOrNull() })
    }

    /**
     * Die über einen Zonenwechsel behaltene Nacht ([ScheduleRules.keptNight]) oder `null`. Weicht sie vom Plan der
     * neuen Ortszeit [normal] ab, steht es einmal im Diagnoseprotokoll.
     */
    suspend fun kept(
        specs: Collection<AlarmSpec>,
        zone: ZoneId,
        now: Instant,
        holidays: Set<LocalDate>,
        doneUntil: Map<String, Instant>,
        normal: WakePlan?,
    ): WakePlan? {
        val kept = ScheduleRules.keptNight(load(), specs, zone, now, holidays, doneUntil) ?: return null
        if (kept != normal && kept != logged) {
            logged = kept
            log("zone change, keep night ${kept.target}")
        }
        return kept
    }

    /** Merkt [plan] mit der Zone [zone]; gilt derselbe Plan schon, bleibt seine Zone. Ohne Plan: gelöscht. */
    suspend fun remember(plan: WakePlan?, zone: ZoneId) {
        store.edit { prefs ->
            if (plan == null) {
                prefs.remove(ID)
                prefs.remove(TARGET)
                prefs.remove(ZONE)
            } else if (prefs[ID] != plan.alarmId || prefs[TARGET] != plan.target.toEpochMilli()) {
                prefs[ID] = plan.alarmId
                prefs[TARGET] = plan.target.toEpochMilli()
                prefs[ZONE] = zone.id
            }
        }
    }

    private companion object {
        val ID = stringPreferencesKey("planned_night_id")
        val TARGET = longPreferencesKey("planned_night_target")
        val ZONE = stringPreferencesKey("planned_night_zone")
    }
}
