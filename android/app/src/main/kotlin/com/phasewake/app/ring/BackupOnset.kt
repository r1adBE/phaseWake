package com.phasewake.app.ring

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.DiagKind
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.BackupAlarm.Companion.GRACE
import com.phasewake.app.shared.OnsetShift
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Einschlaf-Modus der Galaxy Watch (Review K-07): Die frühere Zielzeit einer Nacht gilt auch für den Zweitwecker.

/** Zuletzt gemeldete frühere Zielzeit der Uhr ([OnsetShift.encode]). */
private val ONSET_SHIFT = stringPreferencesKey("backup_onset_shift")

/**
 * Die Uhr meldet eine frühere Zielzeit ([OnsetShift]): merken (eine neuere ersetzt eine ältere) und neu planen. Eine
 * Meldung ohne frühere Zielzeit (Zielzeit = eingestellte, Review H-1) hebt eine gemerkte auf. Eine schon
 * vorbeigegangene Nacht, eine ältere Meldung als die gemerkte oder dieselbe noch einmal (Abholen beim Start) ändert nichts.
 */
internal suspend fun BackupAlarm.onsetShiftReceived(shift: OnsetShift) {
    if (shift.expired(now(), GRACE)) {
        forgetOnsetShiftItem()
        return
    }
    var stored = false
    store.edit { prefs ->
        val current = OnsetShift.decode(prefs[ONSET_SHIFT])
        if (current == null || (shift != current && shift.at >= current.at)) {
            prefs[ONSET_SHIFT] = shift.encode()
            stored = true
        }
    }
    if (!stored) return
    context.phoneApp.diag.log(DiagKind.SCHEDULED, "watch onset target ${java.time.Instant.ofEpochMilli(shift.target)}")
    reschedule()
}

/**
 * Gemerkte frühere Zielzeit, solange ihre Nacht läuft; danach wird sie gelöscht ([OnsetShift.expired]). `null`, wenn
 * keine gilt.
 */
internal suspend fun BackupAlarm.onsetShift(): OnsetShift? {
    val shift = OnsetShift.decode(store.data.first()[ONSET_SHIFT]) ?: return null
    if (!shift.expired(now(), GRACE)) return shift
    store.edit { it.remove(ONSET_SHIFT) }
    forgetOnsetShiftItem()
    return null
}

/** Nach der Nacht: auch das Paket der Uhr aus dem Data Layer entfernen (Review N-1); im Hintergrund, nicht beim Planen. */
private fun BackupAlarm.forgetOnsetShiftItem() {
    context.phoneApp.appScope.launch { sync.deleteOnsetShift() }
}
