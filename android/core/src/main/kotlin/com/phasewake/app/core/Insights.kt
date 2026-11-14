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

/** Bewertet die Messqualität einer Uhr-Nacht ([DataQuality]); die Aufwärm-Epochen zählen nicht mit. */
object NightQuality {
    /** Ab diesem Anteil gilt ein Signal als durchgehend vorhanden. */
    const val COMPLETE_SHARE: Double = 0.8

    /** Ab diesem Anteil zählt der Puls als mitentscheidend. */
    const val HEART_SHARE: Double = 0.5

    /**
     * Qualität der Nacht aus dem Anteil der Epochen mit Bewegung bzw. Puls nach [warmupEpochs].
     * Ohne Epochen oder ohne jede Bewegung: [DataQuality.NONE].
     */
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
 * @property asleepMinutes Summe aller Phasen ausser «wach» (Millisekunden summiert, dann auf ganze Minuten abgerundet).
 * @property timeZone Zone der Aufzeichnung ([HealthSession.timeZone]); `null` = unbekannt, dann die des Geräts. Nachtdatum
 *   und Uhrzeiten (Diagramme, Ø-Bett-/Aufwachzeit, Trends, Regelmässigkeit, Schlafziel) rechnen in ihr ([zoneOr]; Audit F-3).
 */
data class SleepSummary(val start: Instant, val end: Instant, val asleepMinutes: Long, val timeZone: ZoneId? = null) {
    /** Zone für Nachtdatum und Uhrzeiten dieser Nacht: die der Aufzeichnung ([timeZone]), sonst [fallback] (Gerät). */
    fun zoneOr(fallback: ZoneId): ZoneId = timeZone ?: fallback

    companion object {
        /**
         * Lücke zwischen zwei Phasen, die noch zur selben Nacht gehört (einschliesslich); darüber beginnt eine neue.
         * Eine Regel für «eine Nacht» überall: gleich wie [HealthHistory.CONTINUATION_GAP] (60 Min; Entscheid r1AD
         * 14.11.2026, Audit W-2 – vorher 3 Std.).
         */
        val NEW_NIGHT_GAP: Duration = HealthHistory.CONTINUATION_GAP

        /**
         * Fasst Phasen zu Nächten zusammen (Lücke höchstens [NEW_NIGHT_GAP]), älteste zuerst; Strecken ganz ohne Schlaf
         * fallen weg. Ohne Mindestdauer: Welche Sitzung eine Nacht ist (Nickerchen nur am Tag), entscheidet vorher
         * [HealthHistory] – wie im Verlauf.
         */
        fun group(spans: List<StageSpan>): List<SleepSummary> {
            val groups = mutableListOf<MutableList<StageSpan>>()
            for (span in spans.sortedBy { it.start }) {
                val last = groups.lastOrNull()
                if (last != null && Duration.between(last.maxOf { it.end }, span.start) <= NEW_NIGHT_GAP) last += span
                else groups += mutableListOf(span)
            }
            return groups.mapNotNull(::of)
        }

        /**
         * Eine Nacht aus [spans]: Beginn der ersten Schlafphase bis Ende der letzten Phase, Schlaf aller Phasen ausser
         * «wach». `null` ohne Schlaf. Für die Nächte des Verlaufs je Kette ([HealthHistory.summaries]).
         */
        fun of(spans: List<StageSpan>): SleepSummary? {
            val asleep = spans.filter { it.stage != SleepStage.AWAKE }
            if (asleep.isEmpty()) return null
            // Millisekunden erst summieren, dann abrunden – wie die Detailseite ([HealthSession.asleepMinutes]);
            // je Phase abgerundet fehlten bei vielen Phasen bis zu einer Minute je Phase.
            val minutes = asleep.sumOf { Duration.between(it.start, it.end).toMillis() } / StageStats.MS_PER_MINUTE
            return SleepSummary(asleep.minOf { it.start }, spans.maxOf { it.end }, minutes)
        }
    }
}

/**
 * Wochenvergleich für den Verlauf. Werte sind `null`, wenn zu wenige Nächte vorliegen – lieber
 * nichts sagen als etwas Falsches.
 */
data class Trends(
    /** Durchschnittlicher Schlaf der letzten 7 Tage in Minuten. */
    val asleepThisWeek: Long?,
    /** Dasselbe für die 7 Tage davor. */
    val asleepLastWeek: Long?,
    /** Wie stark die Einschlafzeit der letzten 14 Tage schwankt (mittlere Abweichung). */
    val bedtimeSpreadMinutes: Long?,
    /** Durchschnittliches Befinden der letzten 7 Tage (1–5). */
    val feelingThisWeek: Double?,
    /** Dasselbe für die 7 Tage davor. */
    val feelingLastWeek: Double?,
) {
    /** Differenz des Schlafs zur Vorwoche in Minuten; `null` ohne beide Wochen. */
    val asleepChange: Long? get() = if (asleepThisWeek != null && asleepLastWeek != null) asleepThisWeek - asleepLastWeek else null

    companion object {
        /** Mindestanzahl Nächte je Woche für einen Durchschnitt; darunter `null`. */
        const val MIN_NIGHTS_PER_WEEK: Int = 3

        /** Mindestanzahl Nächte in zwei Wochen für die Schwankung der Einschlafzeit. */
        const val MIN_NIGHTS_FOR_SPREAD: Int = 5

        /**
         * Wochenvergleich bis [now]: «diese Woche» = die 7 Tage bis [now], «Vorwoche» = die 7 Tage davor
         * (feste 24-h-Tage). Schlaf nach Ende der Nacht, Befinden nach Weckzeit eingeordnet; Befinden zu Nächten aus
         * anderen Apps ([health]) nach dem Ende ihrer Sitzung.
         */
        fun compute(
            nights: List<NightRecord>,
            sleeps: List<SleepSummary>,
            now: Instant,
            zone: ZoneId,
            health: List<HealthFeeling> = emptyList(),
        ): Trends {
            val weekAgo = now.minus(Duration.ofDays(7))
            val twoWeeksAgo = now.minus(Duration.ofDays(14))
            fun <T> inRange(items: List<T>, at: (T) -> Instant, from: Instant, to: Instant) =
                items.filter { at(it).isAfter(from) && !at(it).isAfter(to) }

            fun asleep(from: Instant, to: Instant): Long? = inRange(sleeps, { it.end }, from, to)
                .takeIf { it.size >= MIN_NIGHTS_PER_WEEK }?.let { list -> list.sumOf { it.asleepMinutes } / list.size }

            fun feeling(from: Instant, to: Instant): Double? {
                val own = inRange(nights, { it.wokeAt }, from, to).mapNotNull { it.feeling }
                val other = inRange(health, { it.at }, from, to).map { it.feeling }
                return (own + other).takeIf { it.size >= MIN_NIGHTS_PER_WEEK }?.average()
            }

            // Einschlafzeit als Minuten ab Mittag, damit 23:30 und 00:30 nahe beieinander liegen; ohne Sprung um
            // Mittag ([Regularity.unwrap], Audit W-1).
            val bedtimes = Regularity.unwrap(inRange(sleeps, { it.end }, twoWeeksAgo, now).map { Regularity.minutesFromNoon(it.start, it.zoneOr(zone)) })
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

/**
 * Hinweis, wenn trotz vollem Weckfenster mehrere Nächte nacheinander erst zur Zielzeit geweckt
 * wurde. Ein grösseres Fenster schlägt [WindowAdvice] vor; dieser Hinweis nennt die
 * wahrscheinliche Ursache aus der Datenqualität.
 */
enum class MissHint {
    /** Lücken in der Messung: Uhr am Laden oder abgelegt. */
    GAPS,

    /** Bewegung vollständig, Puls fehlte meist: Uhr sitzt locker. */
    HEART_MISSING,

    /** Messung vollständig: ruhiger Schlaf; Wecken zur Zielzeit ist dann richtig. */
    CALM;

    companion object {
        /** So viele jüngste Nächte des Weckers müssen alle zur Zielzeit geendet haben. */
        const val NIGHTS: Int = 3

        /**
         * Hinweis für [spec], wenn dessen Fenster schon am grössten ist und die letzten [NIGHTS] Nächte
         * alle zur Zielzeit endeten; Ursache nach der Mehrheit der Datenqualität. Sonst `null`.
         */
        fun of(spec: AlarmSpec?, nights: List<NightRecord>): MissHint? {
            if (spec == null || spec.windowMinutes < AlarmSpec.MAX_WINDOW_MINUTES) return null
            val recent = nights.filter { it.alarmId == spec.id }.sortedBy { it.windowStart }.takeLast(NIGHTS)
            if (recent.size < NIGHTS || recent.any { it.reason != WakeReason.DEADLINE }) return null
            val qualities = recent.map { NightQuality.of(it) }
            val majority = NIGHTS / 2 + 1
            return when {
                qualities.count { it == DataQuality.PARTIAL || it == DataQuality.NONE } >= majority -> GAPS
                qualities.count { it == DataQuality.MOTION_ONLY } >= majority -> HEART_MISSING
                else -> CALM
            }
        }
    }
}
