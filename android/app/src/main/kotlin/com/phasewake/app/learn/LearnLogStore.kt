package com.phasewake.app.learn

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.LearnLog
import com.phasewake.app.core.LearnSkip
import com.phasewake.app.core.SleepNotes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Was die Lern-Übersicht festhält ([skips]: Meldungen der Uhr; [alarmNights]: Nächte mit Wecker, seit
 * [alarmsKnownFrom]). Gerechnet wird nach [LearnLog] (Kern), gespeichert hier.
 */
data class LearnLogState(
    val skips: List<LearnSkip> = emptyList(),
    val alarmNights: Set<LocalDate> = emptySet(),
    val alarmsKnownFrom: LocalDate? = null,
)

/**
 * Lern-Übersicht am Handy (Entscheid r1AD 13.11.2026), privat im DataStore der App wie die Lernphase (keine Sicherung):
 * die Meldungen der Uhr ([addSkip]) und die Nächte mit Wecker ([noteAlarms], bei jedem Neuplanen des Zweitweckers).
 * Höchstens 14 Nächte je Art; «Verlauf löschen» leert beides ([clear]). Keine neuen Rechte.
 */
class LearnLogStore(private val store: DataStore<Preferences>, private val zone: () -> ZoneId = ZoneId::systemDefault) {

    /** Aktueller Stand für die Übersicht. */
    val state: Flow<LearnLogState> = store.data.map { prefs ->
        val since = prefs[ALARMS_SINCE]?.let { SleepNotes.nightDate(Instant.ofEpochMilli(it), zone()).plusDays(1) }
        LearnLogState(LearnLog.decodeSkips(prefs[SKIPS]), LearnLog.decodeAlarmNights(prefs[ALARM_NIGHTS]).keys, since)
    }

    /** Meldung der Uhr ([LearnSkip]) speichern; dieselbe Nacht ersetzt die frühere. */
    suspend fun addSkip(skip: LearnSkip) {
        store.edit { prefs ->
            prefs[SKIPS] = LearnLog.encodeSkips(LearnLog.addSkip(LearnLog.decodeSkips(prefs[SKIPS]), skip, zone()))
        }
    }

    /**
     * Neuplanen um [now]: die Nächte mit Wecker der nächsten Tage aus [alarms] (mit [holidays]) merken
     * ([LearnLog.mergeAlarmNights]); vergangene bleiben. Beim ersten Mal beginnt das Wissen über Wecker (davor unbekannt).
     */
    suspend fun noteAlarms(alarms: List<AlarmSpec>, holidays: Set<LocalDate>, now: Instant) {
        val planned = LearnLog.upcomingTargets(alarms, now, zone(), holidays)
        store.edit { prefs ->
            val merged = LearnLog.mergeAlarmNights(LearnLog.decodeAlarmNights(prefs[ALARM_NIGHTS]), planned, now, zone())
            prefs[ALARM_NIGHTS] = LearnLog.encodeAlarmNights(merged)
            if (prefs[ALARMS_SINCE] == null) prefs[ALARMS_SINCE] = now.toEpochMilli()
        }
    }

    /** «Verlauf löschen»: Meldungen und vergangene Wecker-Nächte weg; das Wissen über Wecker beginnt ab [now] neu. */
    suspend fun clear(now: Instant) {
        store.edit { prefs ->
            prefs.remove(SKIPS)
            prefs[ALARM_NIGHTS] = LearnLog.encodeAlarmNights(LearnLog.decodeAlarmNights(prefs[ALARM_NIGHTS]).filterValues { it.isAfter(now) })
            prefs[ALARMS_SINCE] = now.toEpochMilli()
        }
    }

    private companion object {
        val SKIPS = stringPreferencesKey("learn_log_skips")
        val ALARM_NIGHTS = stringPreferencesKey("learn_log_alarm_nights")
        val ALARMS_SINCE = longPreferencesKey("learn_log_alarms_since")
    }
}
