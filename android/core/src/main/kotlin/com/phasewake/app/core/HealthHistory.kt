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
     * Tag. Kurzer Schlaf in der Nacht (z. B. 01:00–03:00) bleibt eine Nacht.
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
    /** Nachtdatum ([SleepNotes.nightDate]): eigene Nacht über den Fensterbeginn, Sitzung über ihr Ende. */
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
 * Eigene Nächte haben Vorrang; je Nachtdatum höchstens eine fremde Sitzung; Nickerchen am Tag zählen nicht.
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
     * und keine eigene Nacht dasselbe Nachtdatum hat oder sich mit ihr überschneidet (Fensterbeginn bis Wecken).
     * Überschneiden sich Sitzungen (mehrere Apps schreiben dieselbe Nacht), bleibt nur eine ([distinct]); danach
     * je Nachtdatum höchstens eine, nach derselben Vorliebe. Eigene Nächte bleiben alle. Gleichstand der Zeit: eigene Nacht zuerst, dann späterer
     * Fensterbeginn, dann [NightRecord.alarmId].
     */
    fun merge(nights: List<NightRecord>, sessions: List<HealthSession>, clearedAt: Instant?, zone: ZoneId): List<HistoryItem> {
        val ownDates = nights.map { SleepNotes.nightDate(it, zone) }.toSet()
        val foreign = nights(sessions, clearedAt, zone)
            .filter { s -> SleepNotes.nightDate(s.end, zone) !in ownDates && nights.none { s.start.isBefore(it.wokeAt) && s.end.isAfter(it.windowStart) } }
            .groupBy { SleepNotes.nightDate(it.end, zone) }
            .map { (date, list) -> HistoryItem(HistoryKind.HEALTH, date, session = list.sortedWith(preferred).first()) }
        val own = nights.map { HistoryItem(HistoryKind.PHASE_WAKE, SleepNotes.nightDate(it, zone), night = it) }
        return (own + foreign).sortedWith(newestFirst)
    }

    /**
     * Ohne doppelte Nächte: Von Sitzungen, die sich zeitlich überschneiden (Beginn vor dem Ende der anderen und
     * umgekehrt; blosses Berühren zählt nicht), bleibt nur eine – zuerst die mit Phasen ([HealthSession.hasStages]),
     * dann die längste, dann der frühere Beginn, dann [HealthSession.source] (`null` zuerst). Nach Beginn sortiert.
     */
    fun distinct(sessions: List<HealthSession>): List<HealthSession> {
        val kept = mutableListOf<HealthSession>()
        for (s in sessions.sortedWith(preferred)) {
            if (kept.none { s.start.isBefore(it.end) && it.start.isBefore(s.end) }) kept += s
        }
        return kept.sortedBy { it.start }
    }

    /** Die Sitzungen, die als Nacht zählen: sichtbar, Ende nach Beginn, kein Nickerchen in [zone], ohne Doppel ([distinct]). */
    private fun nights(sessions: List<HealthSession>, clearedAt: Instant?, zone: ZoneId): List<HealthSession> =
        distinct(visible(sessions, clearedAt).filter { it.end.isAfter(it.start) && !it.isNap(zone) })

    /**
     * Eintrag zu einem Nachtdatum, z. B. beim Antippen eines Diagrammwerts: der erste in [items] (neueste zuerst,
     * also bei mehreren eigenen Nächten die zuletzt geweckte) mit diesem [date]; keiner: `null`.
     */
    fun itemFor(items: List<HistoryItem>, date: LocalDate): HistoryItem? = items.firstOrNull { it.date == date }

    /** Phasen der Sitzungen, die als Nacht zählen (ohne Doppel), nach Beginn: zum Anreichern der eigenen Nächte. */
    fun stages(sessions: List<HealthSession>, clearedAt: Instant?, zone: ZoneId): List<StageSpan> =
        nights(sessions, clearedAt, zone).flatMap { it.stages }.sortedBy { it.start }

    /**
     * Nächte für Diagramme, Trends, Wochenbilanz, Serien und Regelmässigkeit: [SleepSummary.group] über die
     * Phasen der Sitzungen, die als Nacht zählen (sichtbar, kein Nickerchen, ohne Doppel – nichts zählt zweimal).
     * Eine Sitzung ohne Phasen zählt als Schlaf über ihre ganze Dauer.
     */
    fun summaries(sessions: List<HealthSession>, clearedAt: Instant?, zone: ZoneId): List<SleepSummary> = SleepSummary.group(
        nights(sessions, clearedAt, zone).flatMap { s -> s.stages.ifEmpty { listOf(StageSpan(s.start, s.end, SleepStage.UNKNOWN)) } },
    )

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
