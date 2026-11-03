package com.phasewake.app.wear

import com.phasewake.app.shared.OnsetShift
import java.util.Collections

/**
 * [PhaseWakeWear] ohne Start-Arbeiten ([PhaseWakeWear.catchUpAndReschedule]: Nachholen vom Handy, Neuplanen im
 * Hintergrund) für Robolectric-Tests, die `wearApp` brauchen. Sonst liefe ein Neuplanen neben dem geprüften Weckdienst.
 * Meldungen der früheren Zielzeit ans Handy landen in [onsetShifts] statt im Data Layer.
 */
class QuietWear : PhaseWakeWear() {
    override fun catchUpAndReschedule() = Unit

    /** Gesendete [OnsetShift] in der Reihenfolge des Sendens (aus dem Hintergrund geschrieben). */
    val onsetShifts: MutableList<OnsetShift> = Collections.synchronizedList(mutableListOf())

    override suspend fun publishOnsetShift(shift: OnsetShift) {
        onsetShifts += shift
    }
}
