package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Eine Schlafsitzung aus der Gesundheits-App (Health Connect bzw. HealthKit), z. B. von Samsung Health.
 * Wird beim Öffnen gelesen und nur im Speicher gehalten, nie in Phase Wake gespeichert.
 * Phasen werden für alle Werte auf [start] bis [end] zugeschnitten; Minuten sind ganze Minuten, abgerundet
 * (Millisekunden erst summiert, dann gerundet).
 */
data class HealthSession(
    /** Beginn der Sitzung («im Bett»). */
    val start: Instant,
    /** Ende der Sitzung (Aufwachen). */
    val end: Instant,
    /** Schlafphasen der Sitzung, falls die App sie liefert; sonst leer. */
    val stages: List<StageSpan> = emptyList(),
    /** Herkunft: Paketname (Android, z. B. «com.sec.android.app.shealth») bzw. Quelle (Apple); `null` = unbekannt. */
    val source: String? = null,
) {
    /** Dauer von [start] bis [end] in ganzen Minuten; 0, wenn [end] nicht nach [start] liegt. */
    val durationMinutes: Long get() = Duration.between(start, end).toMinutes().coerceAtLeast(0)

    /** Ob mindestens eine bekannte Phase (wach, leicht, tief, REM) vorliegt; [SleepStage.UNKNOWN] zählt nicht. */
    val hasStages: Boolean get() = stages.any { ChartData.level(it.stage) != null }

    /** Einschlafen: Beginn der ersten Phase ausser «wach» ([SleepStage.UNKNOWN] zählt als Schlaf); ohne solche [start]. */
    val asleepAt: Instant get() = clipped().filter { it.stage != SleepStage.AWAKE }.minOfOrNull { it.start } ?: start

    /** Schlaf ohne Wachphasen in Minuten ([SleepStage.UNKNOWN] zählt als Schlaf); ganz ohne Phasen die ganze Dauer. */
    val asleepMinutes: Long
        get() = if (stages.isEmpty()) durationMinutes else minutes(clipped().filter { it.stage != SleepStage.AWAKE })

    /** Wachzeit in Minuten; `null` ohne bekannte Phasen ([hasStages]). */
    val awakeMinutes: Long? get() = if (hasStages) minutesOf(SleepStage.AWAKE) else null

    /**
     * Nickerchen statt Nacht: kürzer als [HealthHistory.MIN_NIGHT_MINUTES] und ganz am Tag – Beginn frühestens
     * [HealthHistory.NAP_EARLIEST], Ende spätestens [HealthHistory.NAP_LATEST], beides Ortszeit in [zone] am selben
     * Tag. Kurzer Schlaf in der Nacht (z. B. 01:00–03:00) bleibt eine Nacht. Setzt die Sitzung eine Nacht fort
     * (Weiterschlafen, [HealthHistory.CONTINUATION_GAP]), zählt sie trotzdem zu dieser Nacht.
     */
    fun isNap(zone: ZoneId): Boolean {
        if (durationMinutes >= HealthHistory.MIN_NIGHT_MINUTES) return false
        val from = start.atZone(zone).toLocalDateTime()
        val to = end.atZone(zone).toLocalDateTime()
        return from.toLocalDate() == to.toLocalDate() &&
            !from.toLocalTime().isBefore(HealthHistory.NAP_EARLIEST) && !to.toLocalTime().isAfter(HealthHistory.NAP_LATEST)
    }

    /** Minuten in [stage]. */
    fun minutesOf(stage: SleepStage): Long = minutes(clipped().filter { it.stage == stage })

    /** Bekannte Phasen (ohne [SleepStage.UNKNOWN]) nach Beginn, in Minuten ab [start] – für das Band in der Karte. */
    val steps: List<HypnoStep>
        get() = clipped().filter { ChartData.level(it.stage) != null }.map {
            HypnoStep(Duration.between(start, it.start).toMillis() / MS_PER_MINUTE, Duration.between(start, it.end).toMillis() / MS_PER_MINUTE, it.stage)
        }

    private fun clipped(): List<StageSpan> = StageCheck.clip(stages, start, end)

    private fun minutes(spans: List<StageSpan>): Long = spans.sumOf { Duration.between(it.start, it.end).toMillis() } / MS_PER_MINUTE.toLong()

    private companion object {
        const val MS_PER_MINUTE = 60_000.0
    }
}

/** Art eines Verlaufseintrags. */
enum class HistoryKind {
    /** Nacht, in der Phase Wake geweckt hat ([NightRecord]). */
    PHASE_WAKE,

    /** Schlafsitzung aus der Gesundheits-App ohne Phase-Wake-Wecker ([HealthSession]). */
    HEALTH,
}

/** Ein Eintrag im Verlauf; nach [kind] ist genau eines von [night] und [session] gesetzt. */
data class HistoryItem(
    /** Art des Eintrags. */
    val kind: HistoryKind,
    /** Nachtdatum ([SleepNotes.nightDate]): eigene Nacht über den Fensterbeginn, Sitzung über ihre Mitte. */
    val date: LocalDate,
    /** Die eigene Nacht bei [HistoryKind.PHASE_WAKE]. */
    val night: NightRecord? = null,
    /** Die Sitzung bei [HistoryKind.HEALTH]. */
    val session: HealthSession? = null,
) {
    /** Zeitpunkt zum Sortieren: Wecken bzw. Ende der Sitzung. */
    val at: Instant get() = night?.wokeAt ?: session?.end ?: Instant.EPOCH

    /**
     * Feste Kennung für Navigation und Listen: eigene Nacht «n:‹Wecker›:‹Fensterbeginn in ms›» (zwei Wecker können
     * gleich beginnen), fremde Nacht «h:‹Nachtdatum›» (höchstens eine je Nachtdatum).
     */
    val key: String get() = night?.let { "n:${it.alarmId}:${it.windowStart.toEpochMilli()}" } ?: "h:$date"
}

/**
 * Verlauf mit Nächten aus der Gesundheits-App: reine Rechnung, gleich in Swift (`HealthHistory`).
 * Eigene Nächte haben Vorrang; je Nachtdatum höchstens eine fremde Sitzung; Nickerchen am Tag zählen nicht;
 * Weiterschlafen nach dem Wecken ([CONTINUATION_GAP]) gehört zur Nacht davor.
 */
object HealthHistory {
    /**
     * Sitzungen unter 3 Stunden können Nickerchen sein (keine Nacht), wenn sie ganz am Tag liegen
     * ([HealthSession.isNap]). Dieselbe Regel gilt für Diagramme und Trends ([summaries]).
     */
    const val MIN_NIGHT_MINUTES: Long = 180

    /** Frühester Beginn eines Nickerchens (Ortszeit, einschliesslich): Wer vor 09:00 kurz schläft, hatte eine Nacht. */
    val NAP_EARLIEST: LocalTime = LocalTime.of(9, 0)

    /** Spätestes Ende eines Nickerchens (Ortszeit, einschliesslich): Wer nach 19:00 aufwacht, hatte eine Nacht. */
    val NAP_LATEST: LocalTime = LocalTime.of(19, 0)

    /**
     * Beginnt eine Sitzung höchstens so lange nach dem Ende einer Nacht (einschliesslich, auch in Kette), setzt sie
     * diese Nacht fort – z. B. Weiterschlafen nach dem Wecken, das Samsung Health als zweite Sitzung schreibt. Sie ist
     * dann nie ein Nickerchen und zählt überall mit der Nacht zusammen ([merge], [summaries], [stages]).
     */
    val CONTINUATION_GAP: Duration = Duration.ofMinutes(60)

    /**
     * Kleine Überlappung, die noch als Anschluss gilt, nicht als Doppel (Audit 2c82e15): Health Connect schreibt die Teile
     * einer getrennten Nacht oft mit einer Sekunde Überlappung. Erst wer sich länger überschneidet, ist ein Doppel.
     */
    val OVERLAP_TOLERANCE: Duration = Duration.ofMinutes(5)

    /** Überschneiden sich [a] und [b] um mehr als [OVERLAP_TOLERANCE] (dann zählt nur eine, [distinct])? */
    private fun clash(a: HealthSession, b: HealthSession): Boolean {
        val from = maxOf(a.start, b.start)
        val to = minOf(a.end, b.end)
        return Duration.between(from, to) > OVERLAP_TOLERANCE
    }

    /** So viele Tage zurück werden die Gesundheitsdaten gelesen: so weit wie der eigene Verlauf. */
    const val DAYS: Long = NightHistory.MAX_NIGHTS.toLong()

    /**
     * Sitzungen, die nach «Verlauf löschen» noch sichtbar sind: Ende echt nach [clearedAt]
     * (fremde Nächte lassen sich nicht löschen, nur ausblenden). [clearedAt] `null`: alle. Reihenfolge bleibt.
     */
    fun visible(sessions: List<HealthSession>, clearedAt: Instant?): List<HealthSession> =
        if (clearedAt == null) sessions else sessions.filter { it.end.isAfter(clearedAt) }

    /**
     * Verlauf aus eigenen Nächten und Sitzungen, neueste zuerst ([HistoryItem.at]).
     *
     * Eine Sitzung zählt nur, wenn sie sichtbar ist ([visible]), kein Nickerchen ist ([HealthSession.isNap] in [zone])
     * oder eine Nacht fortsetzt ([CONTINUATION_GAP]; Teile einer Nacht stehen als eine Sitzung von Beginn des ersten bis
     * Ende des letzten Teils im Verlauf, Phasen beider, Quelle des ersten) und keine eigene Nacht dasselbe Nachtdatum hat oder sich mit ihr überschneidet (Fensterbeginn bis Wecken).
     * Überschneiden sich Sitzungen (mehrere Apps schreiben dieselbe Nacht), bleibt nur eine ([distinct]); danach
     * je Nachtdatum höchstens eine, nach derselben Vorliebe. Eigene Nächte bleiben alle. Gleichstand der Zeit: eigene Nacht zuerst, dann späterer
     * Fensterbeginn, dann [NightRecord.alarmId].
     */
    fun merge(nights: List<NightRecord>, sessions: List<HealthSession>, clearedAt: Instant?, zone: ZoneId): List<HistoryItem> {
        val ownDates = nights.map { SleepNotes.nightDate(it, zone) }.toSet()
        val foreign = nights(sessions, clearedAt, zone)
            .filter { s -> SleepNotes.nightDate(s, zone) !in ownDates && nights.none { s.start.isBefore(it.wokeAt) && s.end.isAfter(it.windowStart) } }
            .groupBy { SleepNotes.nightDate(it, zone) }
            .map { (date, list) -> HistoryItem(HistoryKind.HEALTH, date, session = list.sortedWith(preferred).first()) }
        val own = nights.map { HistoryItem(HistoryKind.PHASE_WAKE, SleepNotes.nightDate(it, zone), night = it) }
        return (own + foreign).sortedWith(newestFirst)
    }

    /**
     * Ohne doppelte Nächte: Von Sitzungen, die sich um mehr als [OVERLAP_TOLERANCE] überschneiden (Berühren und kurze
     * Überlappung zählen nicht), bleibt nur eine – zuerst die mit Phasen ([HealthSession.hasStages]),
     * dann die längste, dann der frühere Beginn, dann [HealthSession.source] (`null` zuerst). Nach Beginn sortiert.
     */
    fun distinct(sessions: List<HealthSession>): List<HealthSession> {
        val kept = mutableListOf<HealthSession>()
        for (s in sessions.sortedWith(preferred)) {
            if (kept.none { clash(s, it) }) kept += s
        }
        return kept.sortedBy { it.start }
    }

    /** Die Sitzungen, die als Nacht zählen ([chains]), je Nacht zu einer Sitzung zusammengefasst ([join]). */
    private fun nights(sessions: List<HealthSession>, clearedAt: Instant?, zone: ZoneId): List<HealthSession> =
        chains(sessions, clearedAt, zone).map(::join)

    /**
     * Nächte als Ketten von Sitzungen, nach Beginn: sichtbar, Ende nach Beginn, ohne Doppel ([distinct]). Jede Kette
     * beginnt mit einer Sitzung, die kein Nickerchen in [zone] ist; jede weitere beginnt höchstens [OVERLAP_TOLERANCE]
     * vor dem Ende der vorigen und höchstens [CONTINUATION_GAP] danach. Nickerchen ohne Anschluss fallen weg, ebenso solche, die sich
     * mit einer Nacht überschneiden (andere App, dieselbe Zeit).
     */
    private fun chains(sessions: List<HealthSession>, clearedAt: Instant?, zone: ZoneId): List<List<HealthSession>> {
        val valid = visible(sessions, clearedAt).filter { it.end.isAfter(it.start) }
        val nights = distinct(valid.filter { !it.isNap(zone) })
        val naps = distinct(valid.filter { it.isNap(zone) })
            .filter { s -> nights.none { clash(s, it) } }
        val chains = mutableListOf<MutableList<HealthSession>>()
        for ((s, night) in (nights.map { it to true } + naps.map { it to false }).sortedBy { it.first.start }) {
            val last = chains.lastOrNull()?.last()
            // Anschluss: beginnt höchstens [OVERLAP_TOLERANCE] vor dem Ende des vorigen Teils, endet danach, Lücke ≤ CONTINUATION_GAP.
            if (last != null && !s.start.isBefore(last.end.minus(OVERLAP_TOLERANCE)) && s.end.isAfter(last.end) &&
                Duration.between(last.end, s.start) <= CONTINUATION_GAP
            ) {
                chains.last() += s
            } else if (night) {
                chains += mutableListOf(s)
            }
        }
        return chains
    }

    /**
     * Eine Kette ([chains]) als eine Sitzung: Beginn des ersten bis Ende des letzten Teils, Quelle des ersten, Phasen
     * aller Teile (auf ihren Teil zugeschnitten, [spans]) – die Lücken dazwischen zählen nicht als Schlaf.
     */
    private fun join(parts: List<HealthSession>): HealthSession {
        if (parts.size == 1) return parts.single()
        return HealthSession(parts.first().start, parts.last().end, trimmed(parts), parts.first().source)
    }

    /**
     * Phasen der Teile einer Kette ([spans]), jeder Teil erst ab dem Ende des vorigen: Eine kurze Überlappung
     * (≤ [OVERLAP_TOLERANCE]) zählt nicht doppelt – gleich in Verlauf, Detailseite ([join]), Diagrammen und Trends
     * ([summaries]) und im Schlafprofil eigener Nächte ([stages]); Audit BE-2. [filler] `false`: Teile ohne Phasen
     * liefern nichts statt einer «unbekannt»-Phase.
     */
    private fun trimmed(parts: List<HealthSession>, filler: Boolean = true): List<StageSpan> =
        parts.flatMapIndexed { i, p ->
            val from = if (i == 0) p.start else maxOf(p.start, parts[i - 1].end)
            val own = if (filler || p.stages.isNotEmpty()) spans(p) else emptyList()
            own.mapNotNull { span -> if (span.end.isAfter(from)) StageSpan(maxOf(span.start, from), span.end, span.stage) else null }
        }

    /** Phasen der Sitzung, auf sie zugeschnitten; ohne Phasen eine «unbekannt»-Phase über die ganze Dauer. */
    private fun spans(s: HealthSession): List<StageSpan> =
        if (s.stages.isEmpty()) listOf(StageSpan(s.start, s.end, SleepStage.UNKNOWN)) else StageCheck.clip(s.stages, s.start, s.end)

    /**
     * Eintrag zu einem Nachtdatum, z. B. beim Antippen eines Diagrammwerts: der erste in [items] (neueste zuerst,
     * also bei mehreren eigenen Nächten die zuletzt geweckte) mit diesem [date]; keiner: `null`.
     */
    fun itemFor(items: List<HistoryItem>, date: LocalDate): HistoryItem? = items.firstOrNull { it.date == date }

    /**
     * Phasen der Sitzungen, die als Nacht zählen (ohne Doppel, mit Weiterschlafen, [CONTINUATION_GAP]), nach Beginn:
     * zum Anreichern der eigenen Nächte. Je Teil auf ihn zugeschnitten und ab dem Ende des vorigen Teils ([trimmed]).
     */
    fun stages(sessions: List<HealthSession>, clearedAt: Instant?, zone: ZoneId): List<StageSpan> =
        chains(sessions, clearedAt, zone).flatMap { trimmed(it, filler = false) }.sortedBy { it.start }

    /**
     * Nächte für Diagramme, Trends, Wochenbilanz, Serien und Regelmässigkeit: je Kette eine Nacht ([SleepSummary.of]),
     * dieselbe Regel wie der Verlauf (Sitzungen, die als Nacht zählen: sichtbar, kein Nickerchen, ohne Doppel – nichts
     * zählt zweimal; Weiterschlafen nach [CONTINUATION_GAP] zählt zur Nacht, die Lücke nicht als Schlaf). Eine Sitzung
     * ohne Phasen zählt als Schlaf über ihre ganze Dauer. Zwei Ketten mit mehr als [CONTINUATION_GAP] Lücke sind zwei
     * Nächte, auch am selben Nachtdatum (Entscheid r1AD 14.11.2026, Audit W-2). Nach Beginn.
     */
    fun summaries(sessions: List<HealthSession>, clearedAt: Instant?, zone: ZoneId): List<SleepSummary> =
        // Je Teil zugeschnitten und ab dem Ende des vorigen wie in der Detailseite ([join], [trimmed]).
        chains(sessions, clearedAt, zone).mapNotNull { SleepSummary.of(trimmed(it)) }

    private val preferred: Comparator<HealthSession> =
        compareByDescending<HealthSession> { it.hasStages }
            .thenByDescending { Duration.between(it.start, it.end).toMillis() }
            .thenBy { it.start }
            .thenBy(nullsFirst()) { it.source }

    private val newestFirst: Comparator<HistoryItem> =
        compareByDescending<HistoryItem> { it.at }
            .thenBy { it.kind.ordinal }
            .thenByDescending { it.night?.windowStart }
            .thenBy { it.night?.alarmId }
}
