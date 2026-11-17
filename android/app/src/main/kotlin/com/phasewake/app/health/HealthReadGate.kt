package com.phasewake.app.health

import java.time.Duration
import java.time.Instant

/**
 * Wann der Verlauf aus Health Connect neu gelesen wird (Audit PERF-05): beim ersten Mal, höchstens alle [MIN_INTERVAL]
 * (Fortsetzen der App, Öffnen des Verlaufs), sofort nach einer Erteilung der Berechtigung ([due] mit neuem `grant`) und
 * wenn der Zeitraum weiter zurück reicht als beim letzten Lesen (z. B. ältere eigene Nächte nach dem Wiederherstellen).
 * Lebt so lange wie die Oberfläche, die das Gelesene hält; [reset], wenn kein Zugriff mehr besteht.
 */
internal class HealthReadGate {
    private var lastAt: Instant? = null
    private var lastFrom: Instant? = null
    private var lastGrant = 0

    /** Ob jetzt ([now]) für den Zeitraum ab [from] gelesen wird; [grant] zählt jede Erteilung der Berechtigung. */
    fun due(now: Instant, from: Instant, grant: Int): Boolean = due(lastAt, lastFrom, lastGrant, now, from, grant)

    /** Gelesen um [now] ab [from] nach der Erteilung Nummer [grant]. */
    fun mark(now: Instant, from: Instant, grant: Int) {
        lastAt = now
        lastFrom = from
        lastGrant = grant
    }

    /** Kein Zugriff (mehr): das nächste Lesen ist gleich fällig. */
    fun reset() {
        lastAt = null
        lastFrom = null
    }

    companion object {
        /** Mindestabstand zweier Lesevorgänge ohne neuen Anlass. */
        val MIN_INTERVAL: Duration = Duration.ofMinutes(5)

        /**
         * Ob nach dem letzten Lesen um [lastAt] (ab [lastFrom], nach Erteilung [lastGrant]) jetzt neu gelesen wird. Eine
         * zurückgestellte Uhr ([now] vor [lastAt]) liest gleich.
         */
        fun due(lastAt: Instant?, lastFrom: Instant?, lastGrant: Int, now: Instant, from: Instant, grant: Int): Boolean =
            lastAt == null || lastFrom == null || grant != lastGrant || now.isBefore(lastAt) ||
                Duration.between(lastAt, now) >= MIN_INTERVAL || from.isBefore(lastFrom)
    }
}
