package com.phasewake.app.core

/**
 * Erkennt den Einschlafzeitpunkt für den Einschlaf-Modus.
 * Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 6.
 */
object SleepOnset {
    /** 30 Epochen = 15 Minuten Ruhe. */
    const val STILL_EPOCHS: Int = 30

    /**
     * Höchstens so viele einzelne Zucker in einer ruhigen Strecke: eine Epoche mit Bewegung an oder über
     * der Schwelle, deren beide Nachbarn ruhig sind. Sonst brach jeder Zucker alle 5–10 Min im Schlaf die Strecke, und
     * die durchgehende Messung fand das Einschlafen Stunden später als die Stichproben.
     */
    const val MAX_TWITCHES: Int = 2

    /**
     * Getragen heisst: ein Puls höchstens so viele Epochen (15 Min) vor dem Beginn der Strecke
     * oder in ihm, und mindestens einer in der Strecke nach ihrer ersten Epoche. Die Apple Watch misst den Puls ruhend
     * von selbst, Apple nennt keinen festen Abstand; üblich sind 5 bis 15 Min (neuere Modelle öfter). 15 Min deckt den
     * grössten üblichen Abstand: Eine getragene Uhr hat damit vor und in jeder 15-Min-Strecke einen Puls, eine
     * abgelegte misst ab dem Ablegen keinen mehr und findet keine Strecke.
     */
    const val WORN_EPOCHS: Int = 30

    /**
     * @param motions Bewegung je Epoche seit «Gute Nacht», in zeitlicher Reihenfolge.
     * @param threshold Leicht-Schwelle; ruhig heisst Bewegung vorhanden und darunter.
     * @return Index der ersten Epoche der ersten ruhigen Strecke, oder `null`, solange es keine gibt. Eine Strecke sind
     *   [STILL_EPOCHS] Epochen, die erste und die letzte ruhig, dazwischen höchstens [MAX_TWITCHES] einzelne Zucker;
     * eine
     *   leere Epoche oder zwei bewegte nacheinander unterbrechen sie.
     * @param heartRates Puls je Epoche oder `null` (keiner bekannt): Dann beginnt eine Strecke nur, wenn die Uhr
     * getragen
     *   wurde, also höchstens [WORN_EPOCHS] Epochen davor oder in ihrer ersten Epoche ein Puls gemessen ist und
     * mindestens
     *   einer in der Strecke danach: Abgelegt oder auf dem Ladegerät misst die Uhr
     *   keinen, und ihr Rauschen zählte sonst als Einschlafen – auch, wenn sie kurz nach einem Puls abgelegt wurde.
     * @param heartLead So viele Epochen vor [motions] beginnt [heartRates] (Puls vor dem Beginn der Messung zählt für
     * die
     *   erste Strecke); 0 = gleich gezählt.
     */
    fun find(
        motions: List<Double?>,
        threshold: Double = DetectorConfig.DEFAULT_LIGHT_THRESHOLD,
        heartRates: List<Double?>? = null,
        heartLead: Int = 0,
    ): Int? {
        fun pulse(index: Int): Boolean = heartRates?.getOrNull(index + heartLead) != null
        fun worn(start: Int, end: Int): Boolean = heartRates == null ||
            ((maxOf(-heartLead, start - WORN_EPOCHS)..start).any(::pulse) && (start + 1..end).any(::pulse))
        fun quiet(index: Int): Boolean = motions[index].let { it != null && it < threshold }
        for (start in 0..motions.size - STILL_EPOCHS) {
            val end = start + STILL_EPOCHS - 1
            if (!quiet(start) || !quiet(end)) continue
            var twitches = 0
            var still = true
            for (index in start + 1 until end) {
                if (quiet(index)) continue
                twitches++
                if (motions[index] == null || !quiet(index - 1) || !quiet(index + 1) || twitches > MAX_TWITCHES) {
                    still = false
                    break
                }
            }
            if (still && worn(start, end)) return start
        }
        return null
    }

    /**
     * Ruhe hat begonnen, die Strecke ist aber noch nicht vollständig (Verlängerung der Einschlaf-Messung an der
     * Zeitgrenze, `docs/PHASENERKENNUNG.md` Abschnitt 6): Beginn der frühesten Strecke, die bis zur letzten Epoche
     * reicht und nach den Regeln von [find] weiterlaufen kann (erste und letzte Epoche ruhig, dazwischen höchstens
     * [MAX_TWITCHES] einzelne Zucker, keine leere Epoche, keine zwei bewegten nacheinander), sofern sie kürzer als
     * [STILL_EPOCHS] ist. Der Puls zählt hier nicht (er entscheidet erst [find]). Sonst `null`; auch, wenn diese
     * Strecke schon lang genug ist: Dann wartet nichts mehr auf sie. Identisch zu `SleepOnset.settling` (Swift).
     */
    fun settling(motions: List<Double?>, threshold: Double = DetectorConfig.DEFAULT_LIGHT_THRESHOLD): Int? {
        val last = motions.size - 1
        fun quiet(index: Int): Boolean = motions[index].let { it != null && it < threshold }
        if (last < 0 || !quiet(last)) return null
        for (start in 0..last) {
            if (!quiet(start)) continue
            var twitches = 0
            var still = true
            for (index in start + 1 until last) {
                if (quiet(index)) continue
                twitches++
                if (motions[index] == null || !quiet(index - 1) || !quiet(index + 1) || twitches > MAX_TWITCHES) {
                    still = false
                    break
                }
            }
            if (still) return start.takeIf { last - it + 1 < STILL_EPOCHS }
        }
        return null
    }
}
