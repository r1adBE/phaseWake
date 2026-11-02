package com.phasewake.app.ring

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.DiagKind
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.BackupAlarm.Companion.GRACE
import com.phasewake.app.shared.OnsetShift
import kotlinx.coroutines.flow.first

// Einschlaf-Modus der Galaxy Watch (Review K-07): Die frühere Zielzeit einer Nacht gilt auch für den Zweitwecker.

/** Zuletzt gemeldete frühere Zielzeit der Uhr ([OnsetShift.encode]). */
private val ONSET_SHIFT = stringPreferencesKey("backup_onset_shift")

/**
 * Die Uhr meldet eine frühere Zielzeit ([OnsetShift]): merken (eine neuere ersetzt eine ältere) und neu planen. Eine
 * schon vorbeigegangene Nacht oder eine ältere Meldung als die gemerkte ändert nichts.
 */
internal suspend fun BackupAlarm.onsetShiftReceived(shift: OnsetShift) {
    if (shift.expired(now(), GRACE)) return
    var stored = false
    store.edit { prefs ->
        val current = OnsetShift.decode(prefs[ONSET_SHIFT])
        if (current == null || shift.at >= current.at) {
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
    return null
}
