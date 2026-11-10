package com.phasewake.app

import com.phasewake.app.ring.BackupAlarm
import com.phasewake.app.shared.LearningSnapshot
import com.phasewake.app.shared.phaseWakeStore
import java.util.Collections

/**
 * [PhaseWakeApp] ohne Start-Arbeiten ([PhaseWakeApp.initUnlocked]: Abholen von der Uhr, Neuplanen im Hintergrund) für
 * Robolectric-Tests, die `phoneApp` brauchen. Sonst liefe ein zweites Planen neben dem geprüften. Der Stand der
 * Lernphase an die Uhr landet in [learningSent] statt im Data Layer.
 */
open class QuietApp : PhaseWakeApp() {
    override fun initUnlocked() = Unit

    /** Gesendete Stände der Lernphase in der Reihenfolge des Sendens. */
    val learningSent: MutableList<LearningSnapshot> = Collections.synchronizedList(mutableListOf())

    override suspend fun publishLearning() {
        learningSent += learning.current().snapshot()
    }
}

/**
 * [QuietApp], deren Zweitwecker «zuletzt gesehen» der Uhr nicht über die Wear-Dienste liest (keine Uhr bekannt): Unter
 * Robolectric antworten sie nie, ein Neuplanen bliebe sonst hängen. Für Tests, die über `phoneApp.backup` neu planen.
 */
class OfflineApp : QuietApp() {
    override val backup: BackupAlarm by lazy { BackupAlarm(this, alarms, phaseWakeStore, sync, watchSeenAt = { null }) }
}
