package com.phasewake.app.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Einteilung der Übersicht im Verlauf: je Säule eine Nacht, eine Woche oder ein Monat. */
enum class OverviewUnit(
    /** So viele Säulen zeigt die Übersicht. */
    val count: Int,
) {
    DAYS(7),
    WEEKS(8),
    MONTHS(4),
}

/**
 * Eine Säule der Übersicht über die Nachtdaten [first] bis [last] (eingeschlossen; bei [OverviewUnit.DAYS] gleich).
 * Wochen und Monate richten sich nach dem Morgen (Nachtdatum + 1 Tag), wie die Daten im Verlauf.
 */
data class OverviewBar(
    /** Erstes Nachtdatum der Säule. */
    val first: LocalDate,
    /** Letztes Nachtdatum der Säule (eingeschlossen). */
    val last: LocalDate,
    /** Durchschnittlicher Schlaf der Nächte mit Daten (ganze Minuten, abgerundet); `null` ohne Daten. */
    val asleepMinutes: Long?,
    /** Nächte mit Daten in dieser Säule. */
    val nights: Int,
    /** Ob [asleepMinutes] das Schlafziel erreicht ([SleepGoal.reached]). */
    val reached: Boolean,
    /**
     * Durchschnittliche Minuten je bekannter Phase (wach, REM, leicht, tief) über die Nächte dieser Säule; leer, wenn
     * eine Nacht der Säule keine Phasen hat (dann zeigt die Säule nur die Dauer, neutral). Für die Aufteilung der Säule.
     */
    val stageMinutes: Map<SleepStage, Long> = emptyMap(),
)

/**
 * Übersicht «Tage / Wochen / Monate»: Säulen, älteste zuerst, und die Durchschnitte aller Nächte im gezeigten Bereich.
 * Uhrzeiten als Minuten ab Mittag ([Regularity.minutesFromNoon]).
 */
data class SleepOverview(
    /** Einteilung der Säulen. */
    val unit: OverviewUnit,
    /** Säulen, älteste zuerst ([OverviewUnit.count] Stück). */
    val bars: List<OverviewBar>,
    /** Schlafziel in Minuten, für die Ziellinie. */
    val goalMinutes: Int,
    /** Durchschnittlicher Schlaf je Nacht (ganze Minuten, abgerundet). */
    val averageMinutes: Long,
    /** Durchschnittliche Einschlafzeit (Beginn des Schlafs), Minuten ab Mittag, gerundet. */
    val bedtimeMinute: Int,
    /** Durchschnittliche Aufwachzeit (Ende), Minuten ab Mittag, gerundet. */
    val wakeMinute: Int,
    /** Nächte mit Daten im ganzen Bereich. */
    val counted: Int,
    /** Davon Nächte mit erreichtem Schlafziel ([SleepGoal.reached]). */
    val reachedNights: Int,
    /** Höhe der Skala: das Grössere von Ziel und höchster Säule. */
    val scaleMinutes: Long,
)

/** Verlauf des Pulses einer Nacht aus den Epochen einer Messung, in Stücken zu [PulseCurve.BUCKET_EPOCHS] Epochen. */
data class PulseCurve(
    /** Beginn der ersten Epoche. */
    val start: Instant,
    /** Ende der letzten Epoche. */
    val end: Instant,
    /** Mittel je Stück in Schlägen pro Minute; `null` ohne Werte im Stück. Stück `i` beginnt bei `start + i · 5 Min`. */
    val buckets: List<Double?>,
    /** Mittel aller Epochen mit Puls, gerundet. */
    val average: Int,
    /** Kleinstes Stück-Mittel (für die Skala). */
    val low: Double,
    /** Grösstes Stück-Mittel (für die Skala). */
    val high: Double,
) {
    companion object {
        /** Epochen je Stück: 10 × 30 s = 5 Min, damit die Linie ruhig bleibt. */
        const val BUCKET_EPOCHS: Int = 10

        /** Mindestens so viele Epochen mit Puls, sonst keine Kurve. */
        const val MIN_VALUES: Int = 10

        /** Mindestens so viele Stücke mit Puls (6 × 5 Min), sonst keine Kurve: Wenige Punkte gäben eine fast flache, nichtssagende Linie. */
        const val MIN_BUCKETS: Int = 6
    }
}

/**
 * Daten für die Übersicht «Tage / Wochen / Monate» und den Pulsverlauf: reine Rechnung (die Plattform zeichnet nur).
 * Schlaf je Nachtdatum wie in [ChartData.sleepBars]: die längste Nacht der Gesundheits-App.
 */
object SleepOverviews {
    /**
     * Übersicht der letzten [OverviewUnit.count] Nächte, Wochen bzw. Monate bis [ChartData.lastDate]. Wochen beginnen
     * mit [firstDay] (Sprache des Geräts). `null`, wenn im ganzen Bereich keine Nacht Daten hat.
     */
    fun of(
        sleeps: List<SleepSummary>,
        zone: ZoneId,
        today: LocalDate,
        goalMinutes: Int,
        unit: OverviewUnit,
        firstDay: DayOfWeek = DayOfWeek.MONDAY,
        /** Phasen aus der Gesundheits-App ([HealthHistory.stages]) für die Aufteilung der Säulen; leer: keine. */
        stages: List<StageSpan> = emptyList(),
    ): SleepOverview? {
        val longest = sleeps.groupBy { SleepNotes.nightDate(it, zone) }.mapValues { (_, list) -> list.maxBy { it.asleepMinutes } }
        val last = ChartData.lastDate(longest.keys, today)
        val ranges = ranges(last, unit, firstDay)
        val inRange = longest.filterKeys { date -> !date.isBefore(ranges.first().first) && !date.isAfter(ranges.last().second) }.values
        if (inRange.isEmpty()) return null
        val bars = ranges.map { (first, lastDate) ->
            val sleepsInBar = longest.filterKeys { !it.isBefore(first) && !it.isAfter(lastDate) }.values
            val nights = sleepsInBar.map { it.asleepMinutes }
            val average = if (nights.isEmpty()) null else nights.sum() / nights.size
            val reached = average != null && SleepGoal.reached(average, goalMinutes)
            OverviewBar(first, lastDate, average, nights.size, reached, stageAverage(sleepsInBar, stages))
        }
        val minutes = inRange.map { it.asleepMinutes }
        return SleepOverview(
            unit = unit,
            bars = bars,
            goalMinutes = goalMinutes,
            averageMinutes = minutes.sum() / minutes.size,
            // Ohne Sprung um Mittag (Audit W-1): Aufwachen 11:56 und 12:50 ergibt 12:23, nicht 00:23.
            // Je Nacht in der Zone ihrer Aufzeichnung (Audit F-3).
            bedtimeMinute = Regularity.meanMinute(inRange.map { Regularity.minutesFromNoon(it.start, it.zoneOr(zone)) }),
            wakeMinute = Regularity.meanMinute(inRange.map { Regularity.minutesFromNoon(it.end, it.zoneOr(zone)) }),
            counted = minutes.size,
            reachedNights = minutes.count { SleepGoal.reached(it, goalMinutes) },
            scaleMinutes = maxOf(goalMinutes.toLong(), bars.maxOf { it.asleepMinutes ?: 0 }),
        )
    }

    /**
     * Durchschnittliche Minuten je bekannter Phase über die Nächte in [sleeps] (Phasen auf die Nacht zugeschnitten,
     * Millisekunden summiert, dann abgerundet). Leer, wenn [sleeps] leer ist oder eine Nacht keine Schlafphase (REM,
     * leicht, tief) hat: Ein Durchschnitt nur über einen Teil der Nächte passte nicht zur Höhe der Säule (Durchschnitt aller Nächte).
     */
    fun stageAverage(sleeps: Collection<SleepSummary>, stages: List<StageSpan>): Map<SleepStage, Long> {
        val perNight = sleeps.map { sleep ->
            StageCheck.clip(stages, sleep.start, sleep.end).filter { ChartData.level(it.stage) != null }
        }
        // Eine Nacht nur mit «wach» (Quelle ohne Phasendetail) hat keine Phasen wie in [StageStats.of] (Audit W-5).
        if (perNight.isEmpty() || perNight.any { spans -> spans.none { it.stage != SleepStage.AWAKE } }) return emptyMap()
        return StageStats.ORDER.associateWith { stage ->
            perNight.sumOf { spans -> spans.filter { it.stage == stage }.sumOf { Duration.between(it.start, it.end).toMillis() } } /
                perNight.size / StageStats.MS_PER_MINUTE
        }
    }

    /** Bereiche der Säulen als Nachtdaten (erstes, letztes), älteste zuerst; der letzte enthält [last]. */
    fun ranges(last: LocalDate, unit: OverviewUnit, firstDay: DayOfWeek = DayOfWeek.MONDAY): List<Pair<LocalDate, LocalDate>> {
        // Wochen und Monate über den Morgen: Nachtdatum + 1 Tag.
        val morning = last.plusDays(1)
        return (unit.count - 1 downTo 0).map { back ->
            when (unit) {
                OverviewUnit.DAYS -> last.minusDays(back.toLong()).let { it to it }
                OverviewUnit.WEEKS -> {
                    val start = morning.with(TemporalAdjusters.previousOrSame(firstDay)).minusWeeks(back.toLong())
                    start.minusDays(1) to start.plusDays(5)
                }
                OverviewUnit.MONTHS -> {
                    val start = morning.withDayOfMonth(1).minusMonths(back.toLong())
                    start.minusDays(1) to start.plusMonths(1).minusDays(2)
                }
            }
        }
    }

    /**
     * Pulsverlauf aus [epochs] ab [start] (Epoche `i` beginnt bei `start + i · 30 s`). `null` mit weniger als
     * [PulseCurve.MIN_VALUES] Epochen bzw. [PulseCurve.MIN_BUCKETS] Stücken mit Puls.
     */
    fun pulse(epochs: List<Epoch>, start: Instant): PulseCurve? {
        val values = epochs.mapNotNull { it.heartRate }
        if (values.size < PulseCurve.MIN_VALUES) return null
        val buckets = epochs.chunked(PulseCurve.BUCKET_EPOCHS).map { chunk ->
            chunk.mapNotNull { it.heartRate }.takeIf { it.isNotEmpty() }?.average()
        }
        val known = buckets.filterNotNull()
        if (known.size < PulseCurve.MIN_BUCKETS) return null
        return PulseCurve(
            start = start,
            end = start.plus(Duration.ofSeconds(epochs.size.toLong() * DetectorConfig.EPOCH_SECONDS)),
            buckets = buckets,
            average = Math.round(values.average()).toInt(),
            low = known.min(),
            high = known.max(),
        )
    }
}
