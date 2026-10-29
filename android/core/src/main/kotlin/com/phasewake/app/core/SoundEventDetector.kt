package com.phasewake.app.core

/**
 * Findet Geräusche in einem Strom von Pegeln. Rein rechnerisch, ohne Audio: Der Aufrufer misst den
 * Pegel je Block von [FRAME_MS] ms und führt die Aktionen aus (Ausschnitt beginnen, beenden, speichern).
 *
 * Regeln:
 * - Grundrauschen = Median der Sekundenpegel der letzten [FLOOR_SECONDS] s (Sekundenpegel = Mittel der
 *   Blockpegel einer Sekunde); erst ab [MIN_FLOOR_SECONDS] s Vorlauf wird erkannt.
 * - Ein Geräusch beginnt, wenn der Pegel mindestens [MIN_LOUD_MS] ms am Stück über Grundrauschen +
 *   [THRESHOLD_DB] liegt. Beginn = erster dieser Blöcke.
 * - Laut ist ein Block nur, wenn er zudem mindestens [MIN_EVENT_DB] dBFS erreicht: In einem sehr
 *   stillen Zimmer zählen leise Geräusche (Rascheln, Lüfter) nicht, auch wenn sie 12 dB über dem
 *   Grundrauschen liegen.
 * - Ausschnitt: [PRE_ROLL_MS] ms vor dem Beginn bis höchstens [MAX_CLIP_MS] ms insgesamt; früher zu
 *   Ende, wenn es [CLIP_TAIL_MS] ms lang leise ist.
 * - Das Geräusch dauert, bis es [EVENT_GAP_MS] ms lang leise ist (Dauer = Beginn bis Ende des letzten
 *   lauten Blocks), höchstens [MAX_EVENT_MS] ms. So gibt es höchstens einen Ausschnitt je 30 s, und
 *   langes Schnarchen zählt als ein Geräusch mit seiner ganzen Dauer.
 *
 * @param atMs Beginn des Blocks in ms (beliebiger, gleichmässig steigender Bezug, z. B. ab Aufnahmebeginn).
 */
class SoundEventDetector {

    /** Was der Aufrufer nach einem Block tun soll; in dieser Reihenfolge ausführen. */
    sealed interface Action {
        /** Ausschnitt beginnt bei [clipStartMs] (Vorlauf ab dort übernehmen); das Geräusch bei [onsetMs]. */
        data class ClipStart(val clipStartMs: Long, val onsetMs: Long) : Action

        /** Ausschnitt endet bei [clipEndMs] (exklusiv): speichern und einordnen. */
        data class ClipEnd(val onsetMs: Long, val clipEndMs: Long) : Action

        /** Geräusch zu Ende. */
        data class EventEnd(val onsetMs: Long, val durationMs: Long, val peakDb: Double) : Action
    }

    private val seconds = ArrayDeque<Double>()
    private var secondSum = 0.0
    private var secondFrames = 0
    private var floor: Double? = null

    private var loudRun = 0
    private var runStartMs = 0L
    private var runPeak = Double.NEGATIVE_INFINITY

    // Laufendes Geräusch.
    private var onsetMs: Long? = null
    private var lastLoudEndMs = 0L
    private var peak = Double.NEGATIVE_INFINITY
    private var clipStartMs: Long? = null

    /** Aktuelle Schwelle in dBFS; `null` während des Vorlaufs. */
    val threshold: Double? get() = floor?.plus(THRESHOLD_DB)

    /**
     * Verarbeitet einen Block von [FRAME_MS] ms mit Pegel [levelDb] (dBFS), Beginn [atMs]. Gibt die
     * Aktionen dieses Blocks zurück (oft keine); das Grundrauschen wird danach nachgeführt.
     */
    fun onFrame(atMs: Long, levelDb: Double): List<Action> {
        val actions = mutableListOf<Action>()
        val limit = threshold
        val loud = limit != null && levelDb > limit && levelDb >= MIN_EVENT_DB
        val frameEnd = atMs + FRAME_MS

        val onset = onsetMs
        if (onset == null) {
            if (loud) {
                if (loudRun == 0) {
                    runStartMs = atMs
                    runPeak = Double.NEGATIVE_INFINITY
                }
                loudRun++
                runPeak = maxOf(runPeak, levelDb)
                if (loudRun * FRAME_MS >= MIN_LOUD_MS) {
                    onsetMs = runStartMs
                    lastLoudEndMs = frameEnd
                    peak = runPeak
                    val start = runStartMs - PRE_ROLL_MS
                    clipStartMs = start
                    loudRun = 0
                    actions += Action.ClipStart(start, runStartMs)
                }
            } else {
                loudRun = 0
            }
        } else {
            if (loud) {
                lastLoudEndMs = frameEnd
                peak = maxOf(peak, levelDb)
            }
            val clip = clipStartMs
            if (clip != null && (frameEnd - clip >= MAX_CLIP_MS || frameEnd - lastLoudEndMs >= CLIP_TAIL_MS)) {
                actions += Action.ClipEnd(onset, minOf(frameEnd, clip + MAX_CLIP_MS))
                clipStartMs = null
            }
            if (frameEnd - lastLoudEndMs >= EVENT_GAP_MS || frameEnd - onset >= MAX_EVENT_MS) {
                actions += endEvent()
            }
        }

        addToFloor(levelDb)
        return actions
    }

    /** Aufnahme endet: offenen Ausschnitt und offenes Geräusch abschliessen. */
    fun finish(atMs: Long): List<Action> {
        val onset = onsetMs ?: return emptyList()
        val actions = mutableListOf<Action>()
        clipStartMs?.let { clip -> actions += Action.ClipEnd(onset, minOf(atMs, clip + MAX_CLIP_MS).coerceAtLeast(onset)) }
        clipStartMs = null
        actions += endEvent()
        return actions
    }

    private fun endEvent(): List<Action> {
        val onset = onsetMs ?: return emptyList()
        val result = mutableListOf<Action>()
        clipStartMs?.let { clip -> result += Action.ClipEnd(onset, minOf(lastLoudEndMs, clip + MAX_CLIP_MS)) }
        result += Action.EventEnd(onset, (lastLoudEndMs - onset).coerceAtLeast(0), peak)
        onsetMs = null
        clipStartMs = null
        peak = Double.NEGATIVE_INFINITY
        loudRun = 0
        return result
    }

    private fun addToFloor(levelDb: Double) {
        secondSum += levelDb
        secondFrames++
        if (secondFrames * FRAME_MS < 1000) return
        seconds.addLast(secondSum / secondFrames)
        secondSum = 0.0
        secondFrames = 0
        while (seconds.size > FLOOR_SECONDS) seconds.removeFirst()
        floor = if (seconds.size >= MIN_FLOOR_SECONDS) median(seconds) else null
    }

    companion object {
        /** Länge eines Blocks in ms. */
        const val FRAME_MS: Long = 100

        /** Sekunden, über die das Grundrauschen als Median läuft. */
        const val FLOOR_SECONDS: Int = 60

        /** Vorlauf in Sekunden, bevor erkannt wird. */
        const val MIN_FLOOR_SECONDS: Int = 10

        /** So viele dB über dem Grundrauschen gilt ein Block als laut. */
        const val THRESHOLD_DB: Double = 12.0

        /** Absolute Untergrenze für einen lauten Block in dBFS (leiser zählt nie als Geräusch). */
        const val MIN_EVENT_DB: Double = -50.0

        /** So lange muss es am Stück laut sein, bis ein Geräusch beginnt (ms). */
        const val MIN_LOUD_MS: Long = 500

        /** Vorlauf des Ausschnitts vor dem Beginn in ms, damit der Anfang hörbar ist. */
        const val PRE_ROLL_MS: Long = 2_000

        /** Höchstlänge eines Ausschnitts in ms. */
        const val MAX_CLIP_MS: Long = 10_000

        /** Nach so langer Stille (ms) endet der Ausschnitt früher. */
        const val CLIP_TAIL_MS: Long = 2_000

        /** Nach so langer Stille (ms) ist das Geräusch zu Ende. */
        const val EVENT_GAP_MS: Long = 30_000

        /** Höchstdauer eines Geräuschs in ms (10 Min); danach beginnt ein neues. */
        const val MAX_EVENT_MS: Long = 10 * 60_000

        /** Median von [values]; bei gerader Anzahl der Mittelwert der beiden mittleren. Nicht leer aufrufen. */
        fun median(values: Collection<Double>): Double {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
