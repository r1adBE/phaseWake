package com.phasewake.app.wear.wake

// Kennzahlen der Einschlaf-Messung für das Diagnose-Protokoll (aus OnsetMonitor.kt ausgelagert, Zeilengrenze).

/** Kennzahlen seit der letzten Zeile im Diagnose-Protokoll (nur gemessene Epochen). */
internal class OnsetPeriod {
    var sinceMs = 0L
    var samplesAtStart = 0L
    var epochs = 0
    var empty = 0
    var sum = 0.0
    var count = 0
    var max: Double? = null
    var wakeLockMs = 0L

    fun add(motion: Double?) {
        epochs++
        if (motion == null) {
            empty++
            return
        }
        sum += motion
        count++
        max = maxOf(max ?: motion, motion)
    }

    fun mean(): Double? = if (count > 0) sum / count else null

    fun reset(nowMs: Long, samples: Long) {
        sinceMs = nowMs
        samplesAtStart = samples
        epochs = 0
        empty = 0
        sum = 0.0
        count = 0
        max = null
        wakeLockMs = 0L
    }
}
