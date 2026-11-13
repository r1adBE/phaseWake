package com.phasewake.app.wear.wake

import android.os.SystemClock

/**
 * Dauer der Schritte beim Klingeln ([ring]) fürs Diagnose-Protokoll, eine Zeile am Ende (Befund 13.11.2026):
 * «ring steps lock=3ms vib=5ms notif=40ms activity=12ms rest=8ms queue=1ms sensors=2ms». [clock] in ms, für Tests ersetzbar.
 */
internal class RingSteps(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private var last = clock()
    private val marks = mutableListOf<Pair<String, Long>>()

    /** Schritt [name] ist fertig: Dauer seit dem vorherigen Schritt (oder dem Beginn). */
    fun mark(name: String) {
        val now = clock()
        marks += name to (now - last)
        last = now
    }

    /** Namen der Schritte in ihrer Reihenfolge. */
    val names: List<String> get() = marks.map { it.first }

    /** Die Zeile fürs Diagnose-Protokoll. */
    fun line(): String = "ring steps " + marks.joinToString(" ") { (name, millis) -> "$name=${millis}ms" }
}
