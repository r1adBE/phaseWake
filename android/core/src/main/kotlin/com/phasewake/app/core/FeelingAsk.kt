package com.phasewake.app.core

/**
 * Befinden auf der Uhr direkt nach dem Wecken (Galaxy Watch und Apple Watch gleich, Entscheid 23.11.2026): Nach dem
 * Stopp zeigt die Uhr «Wie fühlst du dich?» als eigenen Bildschirm, still (ohne Ton und Vibration), höchstens
 * [SECONDS] lang. Die Wahl reist mit der Nacht zum Handy über den Weg der Nächte (Warteschlange bzw.
 * `transferUserInfo`, wiederholt bis zugestellt); dort gilt sie wie eine Eingabe am Handy (gleiche Nacht, gleiches
 * Feld). Gleich in Swift (`FeelingAsk`).
 */
object FeelingAsk {
    /** So lange bleibt die Abfrage ohne Eingabe stehen, in Sekunden; danach verschwindet sie ohne Wahl. */
    const val SECONDS: Long = 60

    /** [SECONDS] in Millisekunden. */
    const val MILLIS: Long = SECONDS * 1000

    /**
     * Ob die Uhr nach dem Ende der Nacht fragt: nur nach echtem Wecken ([recorded]: geklingelt, kein Probewecker und
     * kein Powernap, die Nacht kommt in den Verlauf) und nur, wenn der Nutzer auf der Uhr gestoppt hat
     * ([stoppedOnWatch]; nicht am Handy, nicht nach der Zeitgrenze ohne Reaktion, nicht nach einem Abbruch). Ein schon
     * bekanntes Befinden der Nacht ([known]) wird nicht noch einmal erfragt.
     */
    fun asks(recorded: Boolean, stoppedOnWatch: Boolean, known: Boolean = false): Boolean =
        recorded && stoppedOnWatch && !known

    /**
     * Ob die Abfrage, gestellt um [askedAtMs] (ms seit 1970), um [nowMs] noch gilt: ab dem Stellen weniger als
     * [MILLIS] lang. Läuft die Uhrzeit zurück (vor [askedAtMs]), gilt sie nicht mehr: lieber keine Abfrage als eine,
     * die stehen bleibt.
     */
    fun isOpen(askedAtMs: Long, nowMs: Long): Boolean = nowMs >= askedAtMs && nowMs - askedAtMs < MILLIS

    /**
     * Befinden, wenn dieselbe Nacht noch einmal ankommt (Handy: die Uhr schickt sie mit ihrer Wahl erneut): Ein schon
     * gespeichertes [stored] (am Handy angegeben oder früher übernommen) hat Vorrang vor dem ankommenden [incoming].
     * So überschreibt eine verspätet zugestellte oder wiederholte Wahl der Uhr nie eine Eingabe am Handy, und
     * dieselbe Nacht zweimal ändert nichts. `null` = keines.
     */
    fun merged(stored: Int?, incoming: Int?): Int? = stored ?: incoming
}
