package com.phasewake.app.wear

/**
 * [PhaseWakeWear] ohne Start-Arbeiten ([PhaseWakeWear.catchUpAndReschedule]: Nachholen vom Handy, Neuplanen im
 * Hintergrund) für Robolectric-Tests, die `wearApp` brauchen. Sonst liefe ein Neuplanen neben dem geprüften Weckdienst.
 */
class QuietWear : PhaseWakeWear() {
    override fun catchUpAndReschedule() = Unit
}
