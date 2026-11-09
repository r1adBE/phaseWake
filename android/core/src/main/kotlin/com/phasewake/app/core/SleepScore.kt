package com.phasewake.app.core

import java.time.Duration
import java.time.Instant

/** Ein Faktor des Schlafwerts ([SleepScore]); Reihenfolge = Reihenfolge der Kacheln. */
enum class ScoreFactor {
    /** Schlaf (ohne Wachphasen) gegen das Schlafziel. */
    DURATION,

    /** Anteil Tiefschlaf. */
    DEEP,

    /** Anteil REM-Schlaf. */
    REM,

    /** Anteil Wachzeit. */
    AWAKE,

    /** Einschlafdauer. */
    LATENCY,
}

/** Bewertung in Worten, für den Schlafwert und jeden Faktor gleich ([SleepScore.band]). */
enum class ScoreBand {
    /** 85–100. */
    EXCELLENT,

    /** 70–84. */
    GOOD,

    /** 50–69. */
    FAIR,

    /** 0–49. */
    POOR,
}

/**
 * Messwerte einer Nacht für den Schlafwert; fehlende Werte `null` (der Faktor fällt dann weg).
 * Prozente wie in [StageTotal.percent] (Anteil an wach, REM, leicht, tief).
 */
data class ScoreInput(
    /** Schlaf ohne Wachphasen in Minuten. */
    val asleepMinutes: Long?,
    /** Schlafziel in Minuten ([SleepGoal]). */
    val goalMinutes: Int,
    /** Anteil Tiefschlaf in Prozent. */
    val deepPercent: Int? = null,
    /** Anteil REM-Schlaf in Prozent. */
    val remPercent: Int? = null,
    /** Anteil Wachzeit in Prozent. */
    val awakePercent: Int? = null,
    /** Einschlafdauer in Minuten. */
    val latencyMinutes: Long? = null,
)

/** Ein Faktor mit seinem Wert: [points] 0–100, [weight] laut [SleepScore.WEIGHTS], [band] aus [points]. */
data class FactorScore(val factor: ScoreFactor, val points: Int, val weight: Int, val band: ScoreBand)

/**
 * Schlafwert einer Nacht: [score] 0–100, [band] in Worten, die vorhandenen Faktoren in der Reihenfolge von [ScoreFactor].
 * [provisional]: ohne Schlafphasen (weder Tief noch REM noch Wach) nur vorläufig, höchstens [SleepScore.PROVISIONAL_MAX].
 */
data class SleepScoreResult(val score: Int, val band: ScoreBand, val factors: List<FactorScore>, val provisional: Boolean = false) {
    /** Faktor [factor]; `null`, wenn er fehlt. */
    fun factor(factor: ScoreFactor): FactorScore? = factors.firstOrNull { it.factor == factor }
}

/**
 * Schlafwert 0–100 mit Bewertung in Worten: reine Rechnung, Regel in `docs/PHASENERKENNUNG.md`, Abschnitt 11.
 * Jeder Faktor gibt 0–100 Punkte (ganze Zahl, gerundet); der Wert ist das gewichtete Mittel der vorhandenen Faktoren
 * (fehlt einer, zählen die übrigen mit ihren Gewichten), gerundet. Ohne Schlafdauer kein Wert. Ohne Schlafphasen
 * (weder Tief noch REM noch Wach) ist der Wert vorläufig und höchstens [PROVISIONAL_MAX] («Gut»).
 * Nur zur Orientierung, keine medizinische Bewertung.
 */
object SleepScore {
    /** Gewichte der Faktoren; zusammen 100. */
    val WEIGHTS: Map<ScoreFactor, Int> = mapOf(
        ScoreFactor.DURATION to 35,
        ScoreFactor.DEEP to 20,
        ScoreFactor.REM to 20,
        ScoreFactor.AWAKE to 15,
        ScoreFactor.LATENCY to 10,
    )

    /** Ab dieser Wachzeit in Prozent gibt «Wach» 0 Punkte (bis zum üblichen Bereich 100). */
    const val AWAKE_ZERO_PERCENT: Int = 30

    /** Einschlafdauer bis hierhin (Minuten, eingeschlossen) gibt 100 Punkte. */
    const val LATENCY_FULL_MINUTES: Long = 20

    /** Ab dieser Einschlafdauer (Minuten) 0 Punkte. */
    const val LATENCY_ZERO_MINUTES: Long = 60

    /** Höchster Wert ohne Schlafphasen: Dauer und Einschlafdauer allein reichen nicht für «Ausgezeichnet». */
    const val PROVISIONAL_MAX: Int = 84

    private const val FULL = 100.0

    /** Schlafwert aus [input]; `null` ohne Schlafdauer (oder mit Schlafziel ≤ 0). */
    fun of(input: ScoreInput): SleepScoreResult? {
        val asleep = input.asleepMinutes ?: return null
        if (input.goalMinutes <= 0) return null
        val raw = listOfNotNull(
            ScoreFactor.DURATION to durationPoints(asleep, input.goalMinutes),
            input.deepPercent?.let { ScoreFactor.DEEP to atLeastPoints(it, low(SleepStage.DEEP)) },
            input.remPercent?.let { ScoreFactor.REM to atLeastPoints(it, low(SleepStage.REM)) },
            input.awakePercent?.let { ScoreFactor.AWAKE to awakePoints(it) },
            input.latencyMinutes?.let { ScoreFactor.LATENCY to latencyPoints(it) },
        )
        val factors = raw.map { (factor, points) -> FactorScore(factor, points, WEIGHTS.getValue(factor), band(points)) }
        val weighted = round(factors.sumOf { it.points * it.weight }.toDouble() / factors.sumOf { it.weight })
        val provisional = input.deepPercent == null && input.remPercent == null && input.awakePercent == null
        val score = if (provisional) minOf(weighted, PROVISIONAL_MAX) else weighted
        return SleepScoreResult(score, band(score), factors, provisional)
    }

    /** Bewertung zu [points]: ab 85 ausgezeichnet, ab 70 gut, ab 50 mittel, darunter schwach. */
    fun band(points: Int): ScoreBand = when {
        points >= 85 -> ScoreBand.EXCELLENT
        points >= 70 -> ScoreBand.GOOD
        points >= 50 -> ScoreBand.FAIR
        else -> ScoreBand.POOR
    }

    /** Schlafdauer: 100 ab dem Ziel, 0 bei höchstens der Hälfte, dazwischen gerade. Kein Abzug für längeren Schlaf. */
    fun durationPoints(asleepMinutes: Long, goalMinutes: Int): Int {
        if (asleepMinutes >= goalMinutes) return 100
        val half = goalMinutes / 2.0
        return round((FULL * (asleepMinutes - half) / half).coerceIn(0.0, FULL))
    }

    /** Tief und REM: 100 ab der unteren Grenze des üblichen Bereichs ([StageStats.TYPICAL]), darunter anteilig. */
    fun atLeastPoints(percent: Int, low: Int): Int =
        if (percent >= low) 100 else round((FULL * percent / low).coerceIn(0.0, FULL))

    /** Wach: 100 im üblichen Bereich (bis 9 %), 0 ab [AWAKE_ZERO_PERCENT], dazwischen gerade. */
    fun awakePoints(percent: Int): Int {
        val high = StageStats.TYPICAL.getValue(SleepStage.AWAKE).high
        if (percent <= high) return 100
        return round((FULL * (AWAKE_ZERO_PERCENT - percent) / (AWAKE_ZERO_PERCENT - high)).coerceIn(0.0, FULL))
    }

    /** Einschlafdauer: 100 bis [LATENCY_FULL_MINUTES], 0 ab [LATENCY_ZERO_MINUTES], dazwischen gerade. */
    fun latencyPoints(minutes: Long): Int {
        if (minutes <= LATENCY_FULL_MINUTES) return 100
        val span = (LATENCY_ZERO_MINUTES - LATENCY_FULL_MINUTES).toDouble()
        return round((FULL * (LATENCY_ZERO_MINUTES - minutes) / span).coerceIn(0.0, FULL))
    }

    /** Eingabe aus den Phasen einer Nacht ([StageStats]): Schlaf, Anteile Tief, REM und Wach, Einschlafdauer (falls bekannt). */
    fun inputOf(stats: NightStages, goalMinutes: Int): ScoreInput = ScoreInput(
        asleepMinutes = stats.asleepMinutes,
        goalMinutes = goalMinutes,
        deepPercent = stats.total(SleepStage.DEEP)?.percent,
        remPercent = stats.total(SleepStage.REM)?.percent,
        awakePercent = stats.total(SleepStage.AWAKE)?.percent,
        latencyMinutes = stats.latencyMinutes,
    )

    /**
     * Eingabe ohne Phasen, aus einer eigenen Aufzeichnung mit erkanntem Einschlafen: Schlaf = [asleepAt] bis [end]
     * (ganze Minuten, abgerundet), Einschlafdauer = [bedAt] bis [asleepAt], falls [bedAt] nicht danach liegt.
     * `null`, wenn [end] nicht nach [asleepAt] liegt.
     */
    fun inputOf(bedAt: Instant?, asleepAt: Instant, end: Instant, goalMinutes: Int): ScoreInput? {
        if (!end.isAfter(asleepAt)) return null
        return ScoreInput(
            asleepMinutes = Duration.between(asleepAt, end).toMinutes(),
            goalMinutes = goalMinutes,
            latencyMinutes = bedAt?.takeIf { !it.isAfter(asleepAt) }?.let { Duration.between(it, asleepAt).toMinutes() },
        )
    }

    private fun low(stage: SleepStage): Int = StageStats.TYPICAL.getValue(stage).low

    /** Kaufmännisch gerundet (x.5 aufwärts); alle Werte sind ≥ 0. */
    private fun round(value: Double): Int = Math.floor(value + 0.5).toInt()
}
