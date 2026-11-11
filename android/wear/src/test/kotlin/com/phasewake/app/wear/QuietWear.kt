package com.phasewake.app.wear

import android.app.AlarmManager
import androidx.datastore.preferences.core.edit
import com.phasewake.app.shared.OnsetShift
import com.phasewake.app.shared.phaseWakeStore
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf

/**
 * [PhaseWakeWear] ohne Start-Arbeiten ([PhaseWakeWear.catchUpAndReschedule]: Nachholen vom Handy, Neuplanen im
 * Hintergrund) für Robolectric-Tests, die `wearApp` brauchen. Sonst liefe ein Neuplanen neben dem geprüften Weckdienst.
 * Meldungen der früheren Zielzeit ans Handy landen in [onsetShifts] statt im Data Layer.
 *
 * Der DataStore ist einer pro Prozess und lebt über einen Test hinaus. Jede Test-App beginnt darum mit leerem Speicher:
 * Sonst spielten Wecker, «erledigt bis», Nachhol-Zeitraum oder die gemerkte Nacht eines früheren Tests je nach
 * Reihenfolge der Tests hinein (so schlugen einzelne Tests gelegentlich fehl).
 */
class QuietWear : PhaseWakeWear() {
    override fun onCreate() {
        runBlocking { phaseWakeStore.edit { it.clear() } }
        super.onCreate()
    }

    override fun catchUpAndReschedule() = Unit

    /** Gesendete [OnsetShift] in der Reihenfolge des Sendens (aus dem Hintergrund geschrieben). */
    val onsetShifts: MutableList<OnsetShift> = Collections.synchronizedList(mutableListOf())

    /** Je gesendete [OnsetShift]: die Zeitpunkte der in diesem Moment gestellten System-Alarme (Reihenfolge, Audit AN-6). */
    val alarmsAtShift: MutableList<List<Long>> = Collections.synchronizedList(mutableListOf())

    override suspend fun publishOnsetShift(shift: OnsetShift) {
        alarmsAtShift += shadowOf(getSystemService(AlarmManager::class.java)).scheduledAlarms.map { it.triggerAtMs }
        onsetShifts += shift
    }
}
