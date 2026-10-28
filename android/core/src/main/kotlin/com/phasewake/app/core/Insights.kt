package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Wie gut die Messung einer Nacht war – damit der Morgenbericht ehrlich sagen kann, worauf er beruht. */
enum class DataQuality {
    /** Bewegung und Puls fast durchgehend. */
    FULL,

    /** Bewegung durchgehend, Puls fehlte meist (Uhr locker oder Sensor aus): nur Bewegung entschied. */
    MOTION_ONLY,

    /** Lücken in der Bewegung (Uhr abgelegt, Verbindung unterbrochen). */
    PARTIAL,

    /** Keine verwertbaren Messwerte im Fenster. */
    NONE,
}

object NightQuality {
    /** Ab diesem Anteil gilt ein Signal als durchgehend vorhanden. */
    const val COMPLETE_SHARE: Double = 0.8

    /** Ab diesem Anteil zählt der Puls als mitentscheidend. */
    const val HEART_SHARE: Double = 0.5

    fun of(night: NightRecord, warmupEpochs: Int = DetectorConfig.DEFAULT_WARMUP_EPOCHS): DataQuality {
        val epochs = night.epochs.drop(warmupEpochs)
        if (epochs.isEmpty()) return DataQuality.NONE
        val motion = epochs.count { it.motion != null }.toDouble() / epochs.size
        val heart = epochs.count { it.heartRate != null }.toDouble() / epochs.size
        return when {
            motion == 0.0 -> DataQuality.NONE
            motion < COMPLETE_SHARE -> DataQuality.PARTIAL
            heart >= HEART_SHARE -> DataQuality.FULL
            else -> DataQuality.MOTION_ONLY
        }
    }
}

/**
 * Eine ganze Nacht laut Gesundheits-App: von der ersten bis zur letzten Phase.
 *
 * @property start Beginn der ersten Schlafphase (ungefähr die Einschlafzeit).
 * @property asleepMinutes Summe aller Phasen ausser «wach».
 */
data class SleepSummary(val start: Instant, val end: Instant, val asleepMinutes: Long) {
    companion object {
        /** Ab dieser Lücke zwischen zwei Phasen beginnt eine neue Nacht. */
        val NEW_NIGHT_GAP: Duration = Duration.ofHours(3)

        /** Mindestschlaf, damit eine Strecke als Nacht zählt (kein Mittagsschlaf). */
        const val MIN_ASLEEP_MINUTES: Long = 180

        /** Fasst Phasen zu Nächten zusammen, älteste zuerst. */
        fun group(spans: List<StageSpan>): List<SleepSummary> {
            val groups = mutableListOf<MutableList<StageSpan>>()
            for (span in spans.sortedBy { it.start }) {
                val last = groups.lastOrNull()
                if (last != null && Duration.between(last.maxOf { it.end }, span.start) < NEW_NIGHT_GAP) last += span
                else groups += mutableListOf(span)
            }
            return groups.mapNotNull { group ->
                val asleep = group.filter { it.stage != SleepStage.AWAKE }
                if (asleep.isEmpty()) return@mapNotNull null
                val minutes = asleep.sumOf { Duration.between(it.start, it.end).toMinutes() }
                if (minutes < MIN_ASLEEP_MINUTES) null
                else SleepSummary(asleep.minOf { it.start }, group.maxOf { it.end }, minutes)
            }
        }
    }
}

/**
 * Wochenvergleich für den Verlauf. Werte sind `null`, wenn zu wenige Nächte vorliegen – lieber
 * nichts sagen als etwas Falsches.
 *
 * @property asleepThisWeek Durchschnittlicher Schlaf der letzten 7 Tage in Minuten.
 * @property asleepLastWeek Dasselbe für die 7 Tage davor.
 * @property bedtimeSpreadMinutes Wie stark die Einschlafzeit der letzten 14 Tage schwankt (mittlere Abweichung).
 * @property feelingThisWeek Durchschnittliches Befinden der letzten 7 Tage (1–5).
 * @property feelingLastWeek Dasselbe für die 7 Tage davor.
 */
data class Trends(
    val asleepThisWeek: Long?,
    val asleepLastWeek: Long?,
    val bedtimeSpreadMinutes: Long?,
    val feelingThisWeek: Double?,
    val feelingLastWeek: Double?,
) {
    /** Differenz des Schlafs zur Vorwoche in Minuten; `null` ohne beide Wochen. */
    val asleepChange: Long? get() = if (asleepThisWeek != null && asleepLastWeek != null) asleepThisWeek - asleepLastWeek else null

    companion object {
        const val MIN_NIGHTS_PER_WEEK: Int = 3
        const val MIN_NIGHTS_FOR_SPREAD: Int = 5

        fun compute(nights: List<NightRecord>, sleeps: List<SleepSummary>, now: Instant, zone: ZoneId): Trends {
            val weekAgo = now.minus(Duration.ofDays(7))
            val twoWeeksAgo = now.minus(Duration.ofDays(14))
            fun <T> inRange(items: List<T>, at: (T) -> Instant, from: Instant, to: Instant) =
                items.filter { at(it).isAfter(from) && !at(it).isAfter(to) }

            fun asleep(from: Instant, to: Instant): Long? = inRange(sleeps, { it.end }, from, to)
                .takeIf { it.size >= MIN_NIGHTS_PER_WEEK }?.let { list -> list.sumOf { it.asleepMinutes } / list.size }

            fun feeling(from: Instant, to: Instant): Double? = inRange(nights, { it.wokeAt }, from, to)
                .mapNotNull { it.feeling }.takeIf { it.size >= MIN_NIGHTS_PER_WEEK }?.average()

            // Einschlafzeit als Minuten ab Mittag, damit 23:30 und 00:30 nahe beieinander liegen.
            val bedtimes = inRange(sleeps, { it.end }, twoWeeksAgo, now).map {
                val t = it.start.atZone(zone).toLocalTime()
                Math.floorMod(t.hour * 60 + t.minute - 12 * 60, 24 * 60).toDouble()
            }
            val spread = bedtimes.takeIf { it.size >= MIN_NIGHTS_FOR_SPREAD }?.let { list ->
                val mean = list.average()
                list.map { kotlin.math.abs(it - mean) }.average().toLong()
            }
            return Trends(
                asleepThisWeek = asleep(weekAgo, now),
                asleepLastWeek = asleep(twoWeeksAgo, weekAgo),
                bedtimeSpreadMinutes = spread,
                feelingThisWeek = feeling(weekAgo, now),
                feelingLastWeek = feeling(twoWeeksAgo, weekAgo),
            )
        }
    }
}
