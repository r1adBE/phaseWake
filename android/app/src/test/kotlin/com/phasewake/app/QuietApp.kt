package com.phasewake.app

import com.phasewake.app.shared.LearningSnapshot
import java.util.Collections

/**
 * [PhaseWakeApp] ohne Start-Arbeiten ([PhaseWakeApp.initUnlocked]: Abholen von der Uhr, Neuplanen im Hintergrund) für
 * Robolectric-Tests, die `phoneApp` brauchen. Sonst liefe ein zweites Planen neben dem geprüften. Der Stand der
 * Lernphase an die Uhr landet in [learningSent] statt im Data Layer.
 */
class QuietApp : PhaseWakeApp() {
    override fun initUnlocked() = Unit

    /** Gesendete Stände der Lernphase in der Reihenfolge des Sendens. */
    val learningSent: MutableList<LearningSnapshot> = Collections.synchronizedList(mutableListOf())

    override suspend fun publishLearning() {
        learningSent += learning.current().snapshot()
    }
}
