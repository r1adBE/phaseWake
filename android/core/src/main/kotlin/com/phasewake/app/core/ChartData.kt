package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Ein Abend im Schlaf-Streifen: Einschlafen bis Aufwachen als Uhrzeit in Minuten ab Mittag (wie
 * [Regularity.minutesFromNoon]; [endMinute] liegt über 1440, wenn bis nach Mittag geschlafen wurde).
 * Ohne Daten beides `null`.
 */
data class SleepBar(val date: LocalDate, val startMinute: Int?, val endMinute: Int?) {
    val hasData: Boolean get() = startMinute != null && endMinute != null
}

/**
 * Schlaf-Streifen über [ChartData.NIGHTS] Nachtdaten, älteste zuerst.
 *
 * @property axisStart Anfang der Zeitachse in Minuten ab Mittag, ganze Stunde.
 * @property axisEnd Ende der Zeitachse in Minuten ab Mittag, ganze Stunde.
 * @property bedtimeMinute Bettzeit vor dem nächsten Wecker ([Bedtime.bedtime]); `null` ohne Wecker.
 * @property regular Regelmässige Abende (von [regularCounted]) laut [Regularity.count].
 */
data class SleepBars(
    val bars: List<SleepBar>,
    val axisStart: Int,
    val axisEnd: Int,
    val bedtimeMinute: Int?,
    val regular: Int,
    val regularCounted: Int,
)

/** Schlafdauer eines Nachtdatums; `null` ohne Daten. */
data class DurationBar(val date: LocalDate, val asleepMinutes: Long?, val reached: Boolean)

/**
 * Schlafdauer pro Nacht, älteste zuerst.
 *
 * @property averageMinutes Durchschnitt der Nächte mit Daten (ganze Minuten, abgerundet).
 * @property reached Nächte mit erreichtem Schlafziel (von [counted] Nächten mit Daten).
 * @property scaleMinutes Höhe der Skala: das Grössere von Ziel und längster Nacht.
 */
data class Durations(
    val bars: List<DurationBar>,
    val goalMinutes: Int,
    val averageMinutes: Long,
    val reached: Int,
    val counted: Int,
    val scaleMinutes: Long,
)

/** Wodurch geweckt wurde, für Farbe und Form des Punkts. */
enum class WakeKind { LIGHT, AWAKE, TARGET, LATE }

/**
 * Weckmoment einer Uhr-Nacht.
 *
 * @property minutesBefore Minuten vor der Zielzeit (0 = zur Zielzeit oder später), höchstens [windowMinutes].
 * @property minutesAfter Minuten nach der Zielzeit (nach Schlummern).
 */
data class WakeMoment(
    val date: LocalDate,
    val wokeAt: Instant,
    val minutesBefore: Int,
    val minutesAfter: Int,
    val windowMinutes: Int,
    val kind: WakeKind,
    val reason: WakeReason,
)

/**
 * Weckmomente der letzten Uhr-Nächte, älteste zuerst.
 *
 * @property scaleMinutes Höhe der Skala: das längste Weckfenster.
 * @property averageBefore Durchschnitt von [WakeMoment.minutesBefore], gerundet.
 */
data class WakeMoments(val points: List<WakeMoment>, val scaleMinutes: Int, val averageBefore: Int)

/** Eine Stufe im Schlafprofil, in Minuten ab [Hypnogram.start]. */
data class HypnoStep(val fromMinute: Double, val toMinute: Double, val stage: SleepStage)

/**
 * Schlafprofil einer Nacht aus der Gesundheits-App mit Weckfenster und Weckmoment (alles in
 * Minuten ab [start]).
 *
 * @property stageAtWake Phase beim Wecken ([StageCheck.stageAtWake]); `null` ohne Daten.
 */
data class Hypnogram(
    val start: Instant,
    val end: Instant,
    val steps: List<HypnoStep>,
    val windowFrom: Double,
    val windowTo: Double,
    val wokeAt: Double,
    val stageAtWake: SleepStage?,
) {
    val totalMinutes: Double get() = Duration.between(start, end).toMillis() / 60_000.0

    /** Minuten je Phase (ohne [SleepStage.UNKNOWN]), ganze Minuten abgerundet. */
    fun minutesOf(stage: SleepStage): Long = steps.filter { it.stage == stage }.sumOf { it.toMinute - it.fromMinute }.toLong()
}

/** Befinden eines Nachtdatums (1–5 oder `null`) und die Notizen dieser Nacht (Kennungen). */
data class FeelingPoint(val date: LocalDate, val feeling: Int?, val notes: List<String>)

/**
 * Befinden über [ChartData.NIGHTS] Nachtdaten, älteste zuerst.
 *
 * @property thisWeek Durchschnitt der jüngsten [ChartData.SHORT_NIGHTS] Nachtdaten; `null` ohne Angabe.
 * @property lastWeek Durchschnitt der [ChartData.SHORT_NIGHTS] Nachtdaten davor; `null` ohne Angabe.
 */
data class FeelingSeries(val points: List<FeelingPoint>, val thisWeek: Double?, val lastWeek: Double?)

/**
 * Daten für die Diagramme im Verlauf: reine Rechnung, die Plattformen zeichnen nur. Jede Funktion
 * gibt `null` zurück, wenn es nichts zu zeigen gibt (das Diagramm bleibt dann verborgen).
 * Nachtdaten wie bei den Notizen ([SleepNotes.nightDate]). Identisch zu `ChartData` (Swift).
 */
object ChartData {
    const val NIGHTS: Int = 14
    const val SHORT_NIGHTS: Int = 7

    /** Standard-Zeitachse 21:00–11:00 als Minuten ab Mittag. */
    const val AXIS_START: Int = 9 * 60
    const val AXIS_END: Int = 23 * 60

    /** Stufe im Schlafprofil von oben nach unten: Wach 0, REM 1, Leicht 2, Tief 3; unbekannt `null`. */
    fun level(stage: SleepStage): Int? = when (stage) {
        SleepStage.AWAKE -> 0
        SleepStage.REM -> 1
        SleepStage.LIGHT -> 2
        SleepStage.DEEP -> 3
        SleepStage.UNKNOWN -> null
    }

    /**
     * Jüngstes Nachtdatum der Diagramme: [today] ([SleepNotes.nightDate] von «jetzt»), wenn es dafür
     * schon Daten gibt, sonst die Nacht davor (am Abend ist die kommende Nacht noch leer).
     */
    fun lastDate(dates: Set<LocalDate>, today: LocalDate): LocalDate = if (today in dates) today else today.minusDays(1)

    private fun range(last: LocalDate, nights: Int): List<LocalDate> = (nights - 1 downTo 0).map { last.minusDays(it.toLong()) }

    /**
     * Schlaf-Streifen der [nights] Nachtdaten bis [lastDate]. Je Nachtdatum die längste Nacht der
     * Gesundheits-App. Achse 21:00–11:00, erweitert auf ganze Stunden, damit alle Balken und die
     * Bettzeit Platz haben (höchstens 24 Stunden).
     *
     * @param nextTarget Zielzeit des nächsten Weckers für die Bettzeit-Linie; `null` ohne Wecker.
     */
    fun sleepBars(
        sleeps: List<SleepSummary>,
        zone: ZoneId,
        today: LocalDate,
        nextTarget: Instant?,
        goalMinutes: Int,
        nights: Int = NIGHTS,
    ): SleepBars? {
        val longest = sleeps.groupBy { SleepNotes.nightDate(it.end, zone) }.mapValues { (_, list) -> list.maxBy { it.asleepMinutes } }
        val last = lastDate(longest.keys, today)
        val bars = range(last, nights).map { date ->
            val sleep = longest[date] ?: return@map SleepBar(date, null, null)
            // Uhrzeiten (nicht Dauer), damit eine Zeitumstellung den Balken nicht verschiebt.
            val start = Regularity.minutesFromNoon(sleep.start, zone).toInt()
            val end = Regularity.minutesFromNoon(sleep.end, zone).toInt()
            SleepBar(date, start, if (end <= start) end + 24 * 60 else end)
        }
        if (bars.none { it.hasData }) return null
        val bedtime = nextTarget?.let { Regularity.minutesFromNoon(Bedtime.bedtime(it, goalMinutes), zone).toInt() }
        val starts = bars.mapNotNull { it.startMinute } + listOfNotNull(bedtime)
        val ends = bars.mapNotNull { it.endMinute } + listOfNotNull(bedtime)
        val axisStart = minOf(AXIS_START, starts.min() / 60 * 60)
        val axisEnd = maxOf(AXIS_END, (ends.max() + 59) / 60 * 60).coerceAtMost(axisStart + 24 * 60)
        val (regular, counted) = Regularity.count(Regularity.bedtimesOf(sleeps, zone), zone, until = last)
        return SleepBars(bars, axisStart, axisEnd, bedtime, regular, counted)
    }

    /** Schlafdauer der [nights] Nachtdaten bis [lastDate] (je Nachtdatum die längste Nacht). */
    fun durations(
        sleeps: List<SleepSummary>,
        zone: ZoneId,
        today: LocalDate,
        goalMinutes: Int,
        nights: Int = SHORT_NIGHTS,
    ): Durations? {
        val asleep = NoteEffect.asleepByNight(sleeps, zone)
        val last = lastDate(asleep.keys, today)
        val bars = range(last, nights).map { date ->
            val minutes = asleep[date]
            DurationBar(date, minutes, minutes != null && SleepGoal.reached(minutes, goalMinutes))
        }
        val values = bars.mapNotNull { it.asleepMinutes }
        if (values.isEmpty()) return null
        return Durations(
            bars = bars,
            goalMinutes = goalMinutes,
            averageMinutes = values.sum() / values.size,
            reached = bars.count { it.reached },
            counted = values.size,
            scaleMinutes = maxOf(goalMinutes.toLong(), values.max()),
        )
    }

    /** Weckmomente der jüngsten [count] Uhr-Nächte (nach Weckzeit). */
    fun wakeMoments(nights: List<NightRecord>, zone: ZoneId, count: Int = NIGHTS): WakeMoments? {
        val recent = nights.sortedBy { it.wokeAt }.takeLast(count)
        if (recent.isEmpty()) return null
        val points = recent.map { night ->
            val window = Duration.between(night.windowStart, night.target).toMinutes().toInt().coerceAtLeast(0)
            val after = night.minutesAfterTarget.toInt()
            WakeMoment(
                date = SleepNotes.nightDate(night, zone),
                wokeAt = night.wokeAt,
                minutesBefore = night.minutesBeforeTarget.toInt().coerceAtMost(window),
                minutesAfter = after,
                windowMinutes = window,
                kind = when {
                    after > 0 -> WakeKind.LATE
                    night.reason == WakeReason.LIGHT_SLEEP -> WakeKind.LIGHT
                    night.reason == WakeReason.AWAKE -> WakeKind.AWAKE
                    else -> WakeKind.TARGET
                },
                reason = night.reason,
            )
        }
        val average = Math.round(points.sumOf { it.minutesBefore }.toDouble() / points.size).toInt()
        return WakeMoments(points, maxOf(1, points.maxOf { it.windowMinutes }), average)
    }

    /**
     * Schlafprofil der Nacht von [night]: die Phasen der Gesundheits-App, die mit Lücken unter
     * [SleepSummary.NEW_NIGHT_GAP] zusammenhängen und das Weckfenster bis zum Wecken berühren. Der
     * Bereich reicht mindestens über Weckfenster und Weckmoment. `null` ohne bekannte Phasen.
     */
    fun hypnogram(stages: List<StageSpan>, night: NightRecord): Hypnogram? {
        val groups = mutableListOf<MutableList<StageSpan>>()
        for (span in stages.sortedBy { it.start }) {
            val group = groups.lastOrNull()
            if (group != null && Duration.between(group.maxOf { it.end }, span.start) < SleepSummary.NEW_NIGHT_GAP) group += span
            else groups += mutableListOf(span)
        }
        val until = maxOf(night.wokeAt, night.target)
        val group = groups.lastOrNull { g -> g.minOf { it.start } <= until && g.maxOf { it.end } >= night.windowStart } ?: return null
        val known = group.filter { level(it.stage) != null }
        if (known.isEmpty()) return null
        val start = minOf(group.minOf { it.start }, night.windowStart)
        val end = maxOf(group.maxOf { it.end }, until)
        fun minutes(at: Instant) = Duration.between(start, at).toMillis() / 60_000.0
        return Hypnogram(
            start = start,
            end = end,
            steps = known.map { HypnoStep(minutes(it.start), minutes(it.end), it.stage) },
            windowFrom = minutes(night.windowStart),
            windowTo = minutes(night.target),
            wokeAt = minutes(night.wokeAt),
            stageAtWake = StageCheck.stageAtWake(group, night.wokeAt),
        )
    }

    /**
     * Befinden der [days] Nachtdaten bis [lastDate]: je Nachtdatum das der zuletzt geweckten Nacht
     * (wie bei den Notizen), dazu die Notizen. `null`, wenn in dieser Zeit kein Befinden angegeben ist.
     */
    fun feelingSeries(
        nights: List<NightRecord>,
        notes: Map<LocalDate, List<String>>,
        zone: ZoneId,
        today: LocalDate,
        days: Int = NIGHTS,
    ): FeelingSeries? {
        val feelings = nights.filter { it.feeling != null }
            .groupBy { SleepNotes.nightDate(it, zone) }
            .mapValues { (_, list) -> list.maxBy { it.wokeAt }.feeling!! }
        val last = lastDate(feelings.keys, today)
        val points = range(last, days).map { FeelingPoint(it, feelings[it], notes[it].orEmpty()) }
        if (points.none { it.feeling != null }) return null
        fun average(list: List<FeelingPoint>) = list.mapNotNull { it.feeling }.takeIf { it.isNotEmpty() }?.average()
        return FeelingSeries(
            points = points,
            thisWeek = average(points.takeLast(SHORT_NIGHTS)),
            lastWeek = average(points.dropLast(SHORT_NIGHTS).takeLast(SHORT_NIGHTS)),
        )
    }
}
