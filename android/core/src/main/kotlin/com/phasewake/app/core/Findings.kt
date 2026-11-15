package com.phasewake.app.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Was die Auffälligkeiten ([Findings]) von einer Nacht der Gesundheits-App brauchen; gleich in Swift (`NightFacts`).
 * Aus einer Sitzung, die als Nacht zählt ([HealthHistory.nights]: ohne Nickerchen, Doppel, mit Weiterschlafen).
 */
data class NightFacts(
    /** Nachtdatum über die Mitte von [asleepAt] bis [end] in der Zone der Aufzeichnung (wie [SleepNotes.nightDate] einer [SleepSummary]). */
    val date: LocalDate,
    /** Einschlafbeginn ([HealthSession.asleepAt]): die «Bettzeit» wie in Regelmässigkeit und Trends. */
    val asleepAt: Instant,
    /** Ende der Nacht. */
    val end: Instant,
    /** Zone der Aufzeichnung ([HealthSession.timeZone]); `null` = die des Geräts. */
    val timeZone: ZoneId?,
    /** Schlaf in Minuten ([HealthSession.asleepMinutes]). */
    val asleepMinutes: Long,
    /**
     * Einschlafdauer: aus der Gesundheits-App wie auf der Detailseite ([NightStages.latencyMinutes]), sonst aus der
     * eigenen Messung der Uhr ([Findings.ownLatency], Entscheid r1AD 15.11.2026); `null` = unbekannt.
     */
    val latencyMinutes: Long? = null,
    /** Tiefschlaf in Minuten; `null` ohne Phasen ([StageStats.of] ergibt `null`). */
    val deepMinutes: Long? = null,
    /** Wach nach dem Einschlafen in Minuten (alle Wachphasen ohne die Einschlafdauer); `null` ohne Phasen. */
    val awakeMinutes: Long? = null,
) {
    /** Zone für Uhrzeiten dieser Nacht: die der Aufzeichnung, sonst [fallback] (Gerät). */
    fun zoneOr(fallback: ZoneId): ZoneId = timeZone ?: fallback

    companion object {
        /**
         * Fakten einer Nacht ([HealthHistory.nights]); Nachtdatum in der Zone der Aufzeichnung, ohne sie in [zone].
         * Einschlafdauer aus der Gesundheits-App, ohne sie aus der eigenen Messung: zuerst die Nächte der Uhr [watch]
         * ([OwnOnset.of]), sonst die Aufzeichnungen [own] ([Findings.ownLatency]).
         */
        fun of(session: HealthSession, zone: ZoneId, own: List<OwnOnset> = emptyList(), watch: List<OwnOnset> = emptyList()): NightFacts {
            val stats = StageStats.forSession(session)
            val latency = stats?.latencyMinutes
            return NightFacts(
                date = SleepNotes.nightDate(session.asleepAt, session.end, session.zoneOr(zone)),
                asleepAt = session.asleepAt,
                end = session.end,
                timeZone = session.timeZone,
                asleepMinutes = session.asleepMinutes,
                latencyMinutes = latency ?: Findings.ownLatency(session.start, session.end, own, watch),
                deepMinutes = stats?.total(SleepStage.DEEP)?.minutes,
                // Nur die Einschlafdauer der Gesundheits-App liegt in deren Wachphasen.
                awakeMinutes = stats?.let { ((it.total(SleepStage.AWAKE)?.minutes ?: 0) - (latency ?: 0)).coerceAtLeast(0) },
            )
        }

        /** Fakten aller Nächte aus [sessions] (dieselben wie im Verlauf, [HealthHistory.nights]), nach Beginn; [own], [watch] siehe oben. */
        fun of(
            sessions: List<HealthSession>,
            clearedAt: Instant?,
            zone: ZoneId,
            own: List<OwnOnset> = emptyList(),
            watch: List<OwnOnset> = emptyList(),
        ): List<NightFacts> = HealthHistory.nights(sessions, clearedAt, zone).map { of(it, zone, own, watch) }
    }
}

/**
 * Eigene Messung des Einschlafens einer Nacht von [start] bis [end] mit Beginn der Einschlafdauer [bedAt] und
 * Einschlafzeit [asleepAt]: eine Nacht der Uhr ([of], Felder 11 und 12 von [Codec.encodeNight]) oder eine abgeschlossene
 * Aufzeichnung (Galaxy Watch «Nacht aufzeichnen» bzw. Lernphase, Apple Watch Rekorder; Zeile `onset=`). Gleich in Swift (`OwnOnset`).
 */
data class OwnOnset(val start: Instant, val end: Instant, val bedAt: Instant, val asleepAt: Instant) {
    companion object {
        /** Messung einer Nacht der Uhr: Beginn der Einschlafdauer bis zum Wecken; `null` ohne Beginn oder Einschlafzeit. */
        fun of(night: NightRecord): OwnOnset? {
            val bed = night.bedAt ?: return null
            return OwnOnset(bed, night.wokeAt, bed, night.asleepAt ?: return null)
        }
    }
}

/** Art einer Auffälligkeit; die Reihenfolge ist die Priorität (die erste ist die wichtigste). */
enum class FindingKind { LONG_LATENCY, SHORT_NIGHTS, LOW_DEEP, MUCH_AWAKE, SOCIAL_JETLAG, BEDTIME_VARIES }

/** Womit wenig Tiefschlaf zusammenhängt (nur genannt, wenn belegbar, [Findings.LINK_POINTS]). */
enum class DeepLink { SHORT_NIGHTS, LATE_BEDTIME }

/**
 * Eine Auffälligkeit der letzten [Findings.NIGHTS] Nächte. [value]: Ø Einschlafdauer (Min), Zahl kurzer Nächte,
 * Tiefschlaf- bzw. Wach-Anteil (%), Verschiebung am Wochenende bzw. Spanne der Bettzeit (Min) – ganze Zahlen, wie
 * angezeigt und wie die Regel sie vergleicht. Bei [FindingKind.LOW_DEEP] mit [link]: Anteil mit bzw. ohne die Ursache.
 */
data class Finding(
    /** Art. */
    val kind: FindingKind,
    /** Wert wie angezeigt (siehe oben). */
    val value: Int,
    /** Zusammenhang bei wenig Tiefschlaf; `null` = keiner belegt. */
    val link: DeepLink? = null,
    /** Tiefschlaf-Anteil (%) in den Nächten mit der Ursache. */
    val withPercent: Int = 0,
    /** Tiefschlaf-Anteil (%) in den Nächten ohne. */
    val withoutPercent: Int = 0,
)

/**
 * Auffälligkeiten: feste Regeln über die letzten [NIGHTS] Nachtdaten aus der Gesundheits-App, je erst ab [MIN_NIGHTS]
 * Nächten mit den nötigen Daten. Werte werden erst auf ganze Zahlen gerundet (halbe aufwärts), dann verglichen – wie
 * angezeigt. Keine Diagnose. Gleich in Swift (`Findings`); gemeinsame Fälle in `shared/testdata/findings.vectors`.
 */
object Findings {
    /** Betrachtete Nachtdaten bis heute. */
    const val NIGHTS: Int = 7

    /** Mindestens so viele Nächte mit den Daten der Regel. */
    const val MIN_NIGHTS: Int = 5

    /** Lange Einschlafdauer: Ø über diesem Wert (Minuten). */
    const val LATENCY_MINUTES: Int = 45

    /** Kurze Nacht: weniger Schlaf als das (Minuten, 6 Std.). */
    const val SHORT_NIGHT_MINUTES: Int = 360

    /** Kurze Nächte: mindestens so viele der [NIGHTS]. */
    const val SHORT_NIGHTS: Int = 3

    /** Wenig Tiefschlaf: Anteil am Schlaf unter diesem Wert (%; untere Grenze von [StageStats.TYPICAL]). */
    val DEEP_PERCENT: Int = StageStats.TYPICAL.getValue(SleepStage.DEEP).low

    /** Zusammenhang mit wenig Tiefschlaf: mindestens so viele Prozentpunkte weniger … */
    const val LINK_POINTS: Int = 3

    /** … und je mindestens so viele Nächte mit und ohne die Ursache. */
    const val LINK_NIGHTS: Int = 2

    /** Späte Bettzeit: später als der Median der Bettzeiten plus so viele Minuten. */
    const val LATE_MINUTES: Int = 60

    /** Viel Wachzeit: Wach-Anteil nach dem Einschlafen über diesem Wert (%). */
    const val AWAKE_PERCENT: Int = 20

    /** Sozialer Jetlag: Mitte des Schlafs am Wochenende so viele Minuten oder mehr verschoben (darüber). */
    const val JETLAG_MINUTES: Int = 90

    /** Je mindestens so viele Nächte am Wochenende und unter der Woche. */
    const val JETLAG_NIGHTS: Int = 2

    /** Schwankende Bettzeit: Spanne zwischen frühester und spätester über diesem Wert (Minuten). */
    const val BEDTIME_RANGE_MINUTES: Int = 90

    /** Ausgeblendet auf der Startseite: erscheint frühestens nach dieser Zeit wieder. */
    val HIDE_FOR: Duration = Duration.ofDays(7)

    /** Wochenrückblick: Tiefschlaf-Anteil nur mit so vielen Nächten mit Phasen je Woche ([Trends.MIN_NIGHTS_PER_WEEK]). */
    const val WEEK_NIGHTS: Int = Trends.MIN_NIGHTS_PER_WEEK

    /** Nächte der [NIGHTS] Nachtdaten bis [today] (einschliesslich); je Nachtdatum die mit dem meisten Schlaf. */
    fun recent(facts: List<NightFacts>, today: LocalDate): List<NightFacts> = byDate(facts, today.minusDays(NIGHTS - 1L), today)

    /** Alle Auffälligkeiten bis [today] (Nachtdatum von «jetzt»), wichtigste zuerst. Uhrzeiten in der Zone der Aufzeichnung, sonst [zone]. */
    fun of(facts: List<NightFacts>, today: LocalDate, zone: ZoneId): List<Finding> {
        val nights = recent(facts, today)
        val staged = nights.filter { it.deepMinutes != null && it.awakeMinutes != null }
        val out = mutableListOf<Finding>()
        val latencies = nights.mapNotNull { it.latencyMinutes }
        if (latencies.size >= MIN_NIGHTS) {
            val average = round(latencies.sum().toDouble() / latencies.size)
            if (average > LATENCY_MINUTES) out += Finding(FindingKind.LONG_LATENCY, average)
        }
        val short = nights.count { isShort(it) }
        if (nights.size >= MIN_NIGHTS && short >= SHORT_NIGHTS) out += Finding(FindingKind.SHORT_NIGHTS, short)
        if (staged.size >= MIN_NIGHTS) {
            deepShare(staged)?.takeIf { it < DEEP_PERCENT }?.let { out += lowDeep(it, staged, nights, zone) }
            val awake = staged.sumOf { it.awakeMinutes!! }
            percent(awake, staged.sumOf { it.asleepMinutes } + awake)?.takeIf { it > AWAKE_PERCENT }?.let { out += Finding(FindingKind.MUCH_AWAKE, it) }
        }
        if (nights.size >= MIN_NIGHTS) {
            val jetlag = jetlag(nights, zone)?.takeIf { it > JETLAG_MINUTES }
            if (jetlag != null) out += Finding(FindingKind.SOCIAL_JETLAG, jetlag)
            // Verschiebung am Wochenende erklärt die Schwankung schon (gleicher Tipp): nicht zweimal.
            val bedtimes = bedtimes(nights, zone)
            val range = round(bedtimes.max() - bedtimes.min())
            if (jetlag == null && range > BEDTIME_RANGE_MINUTES) out += Finding(FindingKind.BEDTIME_VARIES, range)
        }
        return out.sortedBy { it.kind.ordinal }
    }

    /** Die wichtigste Auffälligkeit, die nicht ausgeblendet ist (ausgeblendet um [hidden], seit weniger als [HIDE_FOR]). */
    fun home(findings: List<Finding>, hidden: Map<FindingKind, Instant>, now: Instant): Finding? =
        findings.firstOrNull { f -> hidden[f.kind]?.let { now.isBefore(it.plus(HIDE_FOR)) } != true }

    /** [hidden] mit [kind] ausgeblendet um [now]; Abgelaufene fallen weg. */
    fun hide(hidden: Map<FindingKind, Instant>, kind: FindingKind, now: Instant): Map<FindingKind, Instant> =
        hidden.filter { now.isBefore(it.value.plus(HIDE_FOR)) } + (kind to now)

    /** Speicherformat «ART|ms» je Zeile. */
    fun encodeHidden(hidden: Map<FindingKind, Instant>): String =
        hidden.entries.sortedBy { it.key.ordinal }.joinToString("\n") { "${it.key.name}|${it.value.toEpochMilli()}" }

    /** Liest [encodeHidden]; Unbekanntes und Unlesbares fällt weg, `null` ergibt nichts. */
    fun decodeHidden(text: String?): Map<FindingKind, Instant> = text.orEmpty().lines().mapNotNull { line ->
        val kind = FindingKind.entries.firstOrNull { it.name == line.substringBefore('|') }
        val ms = line.substringAfter('|', "").toLongOrNull()
        if (kind == null || ms == null) null else kind to Instant.ofEpochMilli(ms)
    }.toMap()

    /**
     * Wochenrückblick: Tiefschlaf-Anteil am Schlaf (%) der Woche bis [weekEnd] und der Woche davor, je nur mit
     * [WEEK_NIGHTS] Nächten mit Phasen; sonst `null`.
     */
    fun weekDeep(facts: List<NightFacts>, weekEnd: LocalDate): Pair<Int, Int>? {
        fun week(end: LocalDate) = byDate(facts, end.minusDays(NIGHTS - 1L), end).filter { it.deepMinutes != null }
            .takeIf { it.size >= WEEK_NIGHTS }?.let(::deepShare)
        val now = week(weekEnd) ?: return null
        return week(weekEnd.minusDays(NIGHTS.toLong()))?.let { now to it }
    }

    /**
     * Einschlafdauer aus der eigenen Messung für eine Nacht der Gesundheits-App von [start] bis [end] (nur, wenn diese
     * keine liefert; Entscheid r1AD 15.11.2026): zuerst aus den Nächten der Uhr [watch], sonst aus den Aufzeichnungen [own].
     * Je die Messung, die sich am längsten mit der Nacht überschneidet (gleich: die erste; wie die Aufzeichnung zur Nacht
     * auf der Detailseite), in ganzen Minuten von [OwnOnset.bedAt] bis [OwnOnset.asleepAt] (abgerundet). `null` ohne
     * Überschneidung oder wenn die Einschlafzeit vor dem Beginn liegt.
     */
    fun ownLatency(start: Instant, end: Instant, own: List<OwnOnset>, watch: List<OwnOnset> = emptyList()): Long? =
        latencyOf(start, end, watch) ?: latencyOf(start, end, own)

    private fun latencyOf(start: Instant, end: Instant, own: List<OwnOnset>): Long? {
        val best = own.filter { it.start.isBefore(end) && it.end.isAfter(start) }
            .maxByOrNull { Duration.between(maxOf(it.start, start), minOf(it.end, end)).toMillis() } ?: return null
        if (best.asleepAt.isBefore(best.bedAt)) return null
        return Duration.between(best.bedAt, best.asleepAt).toMillis() / StageStats.MS_PER_MINUTE
    }

    /** Tiefschlaf-Anteil am Schlaf in ganzen Prozent (halbe aufwärts) – eine Nacht wie die Regel; `null` ohne Schlaf. */
    fun deepPercent(deepMinutes: Long, asleepMinutes: Long): Int? = StageStats.sleepPercent(deepMinutes, asleepMinutes)

    private fun lowDeep(share: Int, staged: List<NightFacts>, nights: List<NightFacts>, zone: ZoneId): Finding {
        val minutes = bedtimes(nights, zone)
        val median = SoundEventDetector.median(minutes)
        val late = nights.zip(minutes).filter { it.second > median + LATE_MINUTES }.map { it.first }.toSet()
        val links = listOfNotNull(link(DeepLink.SHORT_NIGHTS, staged) { isShort(it) }, link(DeepLink.LATE_BEDTIME, staged) { it in late })
        // Der grössere Unterschied; gleich: kurze Nächte (Reihenfolge von [DeepLink]).
        val best = links.maxWithOrNull(compareBy<Finding> { it.withoutPercent - it.withPercent }.thenByDescending { it.link!!.ordinal })
        return best?.copy(value = share) ?: Finding(FindingKind.LOW_DEEP, share)
    }

    private fun link(link: DeepLink, staged: List<NightFacts>, test: (NightFacts) -> Boolean): Finding? {
        val (with, without) = staged.partition(test)
        if (with.size < LINK_NIGHTS || without.size < LINK_NIGHTS) return null
        val a = deepShare(with) ?: return null
        val b = deepShare(without) ?: return null
        return if (b - a >= LINK_POINTS) Finding(FindingKind.LOW_DEEP, 0, link, a, b) else null
    }

    /** Mitte des Schlafs am Wochenende (Nachtdatum Freitag, Samstag) gegenüber unter der Woche in Minuten; `null` mit zu wenig Nächten. */
    private fun jetlag(nights: List<NightFacts>, zone: ZoneId): Int? {
        val mids = Regularity.unwrap(nights.map { Regularity.minutesFromNoon(it.asleepAt.plus(Duration.between(it.asleepAt, it.end).dividedBy(2)), it.zoneOr(zone)) })
        val weekend = nights.indices.filter { nights[it].date.dayOfWeek == DayOfWeek.FRIDAY || nights[it].date.dayOfWeek == DayOfWeek.SATURDAY }
        val weekdays = nights.indices - weekend.toSet()
        if (weekend.size < JETLAG_NIGHTS || weekdays.size < JETLAG_NIGHTS) return null
        return round(kotlin.math.abs(weekend.map { mids[it] }.average() - weekdays.map { mids[it] }.average()))
    }

    /** Bettzeiten in Minuten ab Mittag ohne Sprung ([Regularity.unwrap]), Reihenfolge wie [nights]. */
    private fun bedtimes(nights: List<NightFacts>, zone: ZoneId): List<Double> =
        Regularity.unwrap(nights.map { Regularity.minutesFromNoon(it.asleepAt, it.zoneOr(zone)) })

    private fun isShort(night: NightFacts) = night.asleepMinutes < SHORT_NIGHT_MINUTES

    private fun byDate(facts: List<NightFacts>, from: LocalDate, to: LocalDate): List<NightFacts> =
        facts.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.groupBy { it.date }
            .map { (_, list) -> list.maxBy { it.asleepMinutes } }.sortedBy { it.date }

    /** Tiefschlaf aller [nights] am Schlaf aller [nights], gerundet. */
    private fun deepShare(nights: List<NightFacts>): Int? = percent(nights.sumOf { it.deepMinutes ?: 0 }, nights.sumOf { it.asleepMinutes })

    private fun percent(part: Long, whole: Long): Int? = if (whole <= 0) null else round(part * 100.0 / whole)

    /** Halbe aufwärts (wie Swift `(x + 0.5).rounded(.down)`). */
    private fun round(value: Double): Int = kotlin.math.floor(value + 0.5).toInt()
}
