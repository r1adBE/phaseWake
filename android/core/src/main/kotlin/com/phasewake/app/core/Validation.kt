package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Validierungs-Modus: Wie oft liegt der Weck-Entscheid wirklich im leichten Schlaf? Rechnet
 * nachträglich auf dem Handy, weckt nie und ändert nichts am Wecken. Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt
 * 12.
 *
 * - **Echte Entscheide**: je Nacht ([NightRecord]) Weckzeit und Grund.
 * - **Schattenentscheide**: Der [WakeDetector] läuft über rollende Fenster von [WINDOW_EPOCHS] Epochen (30 Min), alle
 *   [STEP_EPOCHS] Epochen (15 Min) eines, über die Epochen einer Aufzeichnung («Nacht aufzeichnen», Lernphase). Ein
 * Fenster
 *   zählt nur, wenn es durchgehend gemessen ist ([measured]); Stichproben (2 von 10 Epochen) liefern darum keine.
 * - **Referenz**: Phase der Gesundheits-App zum Entscheid ([reference], ±[TOLERANCE]). Sie ist eine Schätzung der Uhr,
 *   kein Schlaflabor.
 *
 * Reine Rechnung; identisch zu `Validation` (Swift), gemeinsame Fälle in `shared/testdata/validation.vectors`.
 */
object Validation {
    /** Länge eines Schattenfensters in Epochen (30 Min, wie das grösste Weckfenster). */
    const val WINDOW_EPOCHS: Int = 60

    /** Abstand der Schattenfenster in Epochen (15 Min); sie überlappen sich, die Entscheide sind nicht unabhängig. */
    const val STEP_EPOCHS: Int = 30

    /** Höchster Abstand zur nächsten Phase, wenn keine den Entscheid abdeckt (einschliesslich). */
    val TOLERANCE: Duration = Duration.ofSeconds(60)

    private const val EPOCH_SECONDS = DetectorConfig.EPOCH_SECONDS.toLong()

    /** Echter Entscheid (geweckt) oder Schattenentscheid (nur gerechnet). [code] steht im Export. */
    enum class Kind(val code: String) { REAL("real"), SHADOW("shadow") }

    /**
     * Referenzphase zum Entscheid. [UNKNOWN]: Nacht mit Phasen, aber keine bekannte bis ±[TOLERANCE]; [NONE]: Nacht
     * ohne.
     */
    enum class Ref(val code: String) {
        AWAKE("awake"), LIGHT("light"), REM("rem"), DEEP("deep"), UNKNOWN("unknown"), NONE("none");

        companion object {
            fun of(stage: SleepStage?): Ref = when (stage) {
                SleepStage.AWAKE -> AWAKE
                SleepStage.LIGHT -> LIGHT
                SleepStage.REM -> REM
                SleepStage.DEEP -> DEEP
                SleepStage.UNKNOWN, null -> UNKNOWN
            }
        }
    }

    /** Ein Entscheid zum Zeitpunkt [at] mit Grund [reason]. */
    data class Moment(val at: Instant, val reason: WakeReason)

    /**
     * Ein ausgewerteter Entscheid: Nacht [night] (1 = älteste mit Entscheiden), Zeitpunkt [at], angezeigt in [zone]
     * (Zone der Aufzeichnung der Gesundheits-App dieser Nacht, sonst des Geräts), Art, Grund und Referenzphase.
     */
    data class Entry(val night: Int, val at: Instant, val zone: ZoneId, val kind: Kind, val reason: WakeReason, val ref: Ref)

    /** Ob mindestens 3/4 der [epochs] Bewegung haben (durchgehend gemessen; Stichproben haben rund 1/5). */
    fun measured(epochs: List<Epoch>): Boolean = epochs.isNotEmpty() && epochs.count { it.motion != null } * 4 >= epochs.size * 3

    /**
     * Ein Fenster ab [start]: erster Entscheid des [WakeDetector] mit [config], Zeitpunkt am Ende der entscheidenden
     * Epoche (Epoche `i` endet bei [start] + (i + 1) · 30 s); ohne Entscheid [WakeReason.DEADLINE] am Ende des
     * Fensters.
     */
    fun window(start: Instant, epochs: List<Epoch>, config: DetectorConfig): Moment {
        val detector = WakeDetector(config)
        for ((i, epoch) in epochs.withIndex()) {
            val decision = detector.onEpoch(epoch)
            if (decision is Decision.Wake) return Moment(start.plusSeconds((i + 1) * EPOCH_SECONDS), decision.reason)
        }
        return Moment(start.plusSeconds(epochs.size * EPOCH_SECONDS), detector.finish().reason)
    }

    /**
     * Schattenentscheide einer Aufzeichnung ab [start]: Fenster ab Epoche 0, [STEP_EPOCHS], 2 · [STEP_EPOCHS] …,
     * solange ein ganzes Fenster ([WINDOW_EPOCHS]) Platz hat; nur durchgehend gemessene ([measured]). Je Fenster ein
     * neuer Detektor. Entscheiden zwei überlappende Fenster im selben Moment gleich, zählt der Moment einmal.
     */
    fun shadows(start: Instant, epochs: List<Epoch>, config: DetectorConfig): List<Moment> {
        val moments = mutableListOf<Moment>()
        var from = 0
        while (from + WINDOW_EPOCHS <= epochs.size) {
            val part = epochs.subList(from, from + WINDOW_EPOCHS)
            if (measured(part)) {
                val moment = window(start.plusSeconds(from * EPOCH_SECONDS), part, config)
                if (moment !in moments) moments += moment
            }
            from += STEP_EPOCHS
        }
        return moments
    }

    /**
     * Referenzphase zum Zeitpunkt [at]: die bekannte Phase (ohne [SleepStage.UNKNOWN]), die [at] abdeckt (Beginn
     * einschliesslich, Ende ausschliesslich; bei Überlappung die zuletzt gelistete wie [StageCheck.stageAt]); sonst die
     * nächste bis [TOLERANCE] davor (Abstand zum Ende) oder danach (Abstand zum Beginn), bei gleichem Abstand die
     * frühere. `null`: keine.
     */
    fun reference(spans: List<StageSpan>, at: Instant): SleepStage? {
        val known = spans.filter { it.stage != SleepStage.UNKNOWN && it.end.isAfter(it.start) }
        StageCheck.stageAt(known, at)?.let { return it }
        var best: StageSpan? = null
        var bestGap = Long.MAX_VALUE
        for (span in known.sortedBy { it.start }) {
            val gap = if (!at.isBefore(span.end)) Duration.between(span.end, at).toMillis() else Duration.between(at, span.start).toMillis()
            if (gap <= TOLERANCE.toMillis() && gap < bestGap) {
                best = span
                bestGap = gap
            }
        }
        return best?.stage
    }

    /**
     * Alle Entscheide, nach Nacht (Nachtdatum in [zone], [SleepNotes.nightDate]: eigene Nacht über den Fensterbeginn,
     * Aufzeichnung über ihren Beginn) und Zeit; echte vor Schattenentscheiden zur selben Zeit.
     *
     * Schattenentscheide rechnen mit derselben Konfiguration wie das echte Fenster: Kalibrierung aus den eigenen
     * Nächten vor der Aufzeichnung ([Calibration.calibrate]) und den gelernten Werten [learned] (aktueller Stand). Das
     * Grundrauschen der Einschlaf-Messung kennt das Handy nicht; ab 4 Werten gilt ohnehin das des Fensters
     * ([NoiseFloor.windowFloor]). Referenz: Phasen der Sitzungen, die als Nacht zählen ([HealthHistory.stages],
     * [clearedAt]). Eine Nacht hat Referenz, wenn eine bekannte Phase ihre Zeit (Fensterbeginn bzw. Beginn der
     * Aufzeichnung bis spätestes Wecken, Zielzeit bzw. Ende, je ±[TOLERANCE]) berührt; sonst [Ref.NONE]. Nächte ohne
     * Entscheid fehlen (z. B. nur Stichproben).
     *
     * @param recordings abgeschlossene Aufzeichnungen ([Learning.Night]).
     */
    fun evaluate(
        nights: List<NightRecord>,
        recordings: List<Learning.Night>,
        sessions: List<HealthSession>,
        clearedAt: Instant?,
        learned: Learning.Values?,
        zone: ZoneId,
    ): List<Entry> {
        val spans = HealthHistory.stages(sessions, clearedAt, zone).filter { it.stage != SleepStage.UNKNOWN }
        val counted = HealthHistory.nights(sessions, clearedAt, zone)
        fun config(before: Instant) =
            Learning.withLearned(Calibration.calibrate(NightHistory.motionsForCalibration(nights.filter { it.windowStart.isBefore(before) })), learned)
        val own = nights.sortedBy { it.windowStart }.groupBy { SleepNotes.nightDate(it, zone) }
        val recorded = recordings.sortedBy { it.start }.groupBy { SleepNotes.nightDate(it.start, zone) }
        val entries = mutableListOf<Entry>()
        var number = 0
        for (date in (own.keys + recorded.keys).sorted()) {
            val real = own[date].orEmpty()
            val records = recorded[date].orEmpty()
            val moments = real.map { Kind.REAL to Moment(it.wokeAt, it.reason) } +
                records.flatMap { r -> shadows(r.start, r.epochs, config(r.start)).map { Kind.SHADOW to it } }
            if (moments.isEmpty()) continue
            number++
            val ranges = real.map { it.windowStart to maxOf(it.wokeAt, it.target) } +
                records.map { it.start to it.start.plusSeconds(it.epochs.size * EPOCH_SECONDS) }
            val from = ranges.minOf { it.first }.minus(TOLERANCE)
            val to = ranges.maxOf { it.second }.plus(TOLERANCE)
            val known = spans.filter { !it.start.isAfter(to) && !it.end.isBefore(from) }
            val nightZone = counted.firstOrNull { !it.start.isAfter(to) && !it.end.isBefore(from) }?.zoneOr(zone) ?: zone
            // Stabil nach Zeit, echte zuerst: dieselbe Reihenfolge wie Swift (dort mit Laufnummer).
            for ((kind, moment) in moments.sortedWith(compareBy({ it.second.at }, { it.first.ordinal }))) {
                val ref = if (known.isEmpty()) Ref.NONE else Ref.of(reference(known, moment.at))
                entries += Entry(number, moment.at, nightZone, kind, moment.reason, ref)
            }
        }
        return entries
    }
}
