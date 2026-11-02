package com.phasewake.app

/**
 * [PhaseWakeApp] ohne Start-Arbeiten ([PhaseWakeApp.initUnlocked]: Abholen von der Uhr, Neuplanen im Hintergrund) für
 * Robolectric-Tests, die `phoneApp` brauchen. Sonst liefe ein zweites Planen neben dem geprüften.
 */
class QuietApp : PhaseWakeApp() {
    override fun initUnlocked() = Unit
}
