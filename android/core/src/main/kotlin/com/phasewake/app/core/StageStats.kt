package com.phasewake.app.core

import java.time.Duration
import java.time.Instant

/** Bewertung eines Phasenanteils gegen den üblichen Bereich ([StageStats.TYPICAL]). */
enum class StageRating {
    /** Unter dem üblichen Bereich. */
    BELOW,

    /** Im üblichen Bereich (Grenzen eingeschlossen). */
    TYPICAL,

    /** Über dem üblichen Bereich. */
    ABOVE,
}

/** Üblicher Bereich eines Phasenanteils in ganzen Prozent; [low] und [high] gehören dazu. */
data class StageRange(
    /** Untere Grenze in Prozent (eingeschlossen). */
    val low: Int,
    /** Obere Grenze in Prozent (eingeschlossen). */
    val high: Int,
)

/** Zusammenhängender Block einer bekannten Phase (wach, REM, leicht, tief; nie [SleepStage.UNKNOWN]). */
data class StageBlock(
    /** Die Phase des Blocks. */
    val stage: SleepStage,
    /** Beginn des Blocks. */
    val start: Instant,
    /** Ende des Blocks (nach [start]). */
    val end: Instant,
) {
    /** Dauer in ganzen Minuten, abgerundet. */
    val minutes: Long get() = Duration.between(start, end).toMillis() / StageStats.MS_PER_MINUTE
}

/** Summe einer Phase in einer Nacht. */
data class StageTotal(
    /** Die Phase. */
    val stage: SleepStage,
    /** Minuten der Phase: Millisekunden summiert, dann abgerundet. */
    val minutes: Long,
    /** Anteil an allen vier Phasen in ganzen Prozent; die vier ergeben zusammen 100 (Restverfahren). */
    val percent: Int,
    /** Üblicher Bereich für Erwachsene ([StageStats.TYPICAL]). */
    val range: StageRange,
    /** [percent] gegen [range]. */
    val rating: StageRating,
)

/** Auswertung der Schlafphasen einer Nacht für die Detailseite ([StageStats.of]); gleich in Swift (`NightStages`). */
data class NightStages(
    /** Beginn des ausgewerteten Bereichs. */
    val start: Instant,
    /** Ende des ausgewerteten Bereichs. */
    val end: Instant,
    /** Bekannte Phasen nach Beginn; gleiche, aneinanderstossende oder überlappende Phasen zu einem Block zusammengelegt. */
    val blocks: List<StageBlock>,
    /** Immer vier Einträge: wach, REM, leicht, tief. */
    val totals: List<StageTotal>,
    /** Einschlafdauer in Minuten (abgerundet): nur wenn der erste Block «wach» ist und ein weiterer folgt; sonst `null`. */
    val latencyMinutes: Long?,
    /** Schlaf (alles ausser «wach», unbekannt zählt als Schlaf) im Verhältnis zum Bereich, gerundet, höchstens 100. */
    val efficiencyPercent: Int,
    /** Wachphasen mitten in der Nacht: Wach-Blöcke mit Schlaf davor und danach (Anfang und Ende zählen nicht). */
    val awakenings: Int,
    /** Längster Tiefschlaf-Block in Minuten; 0 ohne Tiefschlaf. */
    val longestDeepMinutes: Long,
    /** Schlaf in Minuten (wie bei [efficiencyPercent]), abgerundet. */
    val asleepMinutes: Long,
) {
    /** Summe von [stage]; `null` für [SleepStage.UNKNOWN]. */
    fun total(stage: SleepStage): StageTotal? = totals.firstOrNull { it.stage == stage }

    /** Block, der [at] enthält (Beginn eingeschlossen, Ende nicht); `null` in einer Lücke oder ausserhalb. */
    fun blockAt(at: Instant): StageBlock? = blocks.lastOrNull { !at.isBefore(it.start) && at.isBefore(it.end) }
}

/**
 * Phasen-Summen, Anteile mit üblichem Bereich und Kennzahlen einer Nacht: reine Rechnung, gleich in Swift (`StageStats`).
 * Alles in Millisekunden gerechnet und erst am Ende auf ganze Minuten bzw. Prozent gebracht.
 */
object StageStats {
    /**
     * Übliche Anteile für Erwachsene in Prozent (Richtwerte laut Auftrag vom 29.10.2026: wach unter 10 %,
     * REM 20–25 %, leicht 50–60 %, tief 13–23 %). Nur zur Orientierung, keine medizinische Bewertung.
     */
    val TYPICAL: Map<SleepStage, StageRange> = mapOf(
        SleepStage.AWAKE to StageRange(0, 9),
        SleepStage.REM to StageRange(20, 25),
        SleepStage.LIGHT to StageRange(50, 60),
        SleepStage.DEEP to StageRange(13, 23),
    )

    /** Reihenfolge der Summen und Gleichstand beim Restverfahren: wach, REM, leicht, tief (wie im Hypnogramm von oben). */
    val ORDER: List<SleepStage> = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)

    internal const val MS_PER_MINUTE: Long = 60_000

    private const val PERCENT: Long = 100

    /**
     * Auswertung der [spans] im Bereich [from] bis [to] (Spannen werden darauf zugeschnitten, [StageCheck.clip]).
     * `null`, wenn [to] nicht nach [from] liegt oder keine bekannte Phase im Bereich liegt.
     */
    fun of(spans: List<StageSpan>, from: Instant, to: Instant): NightStages? {
        if (!to.isAfter(from)) return null
        val clipped = StageCheck.clip(spans, from, to)
        val blocks = blocksOf(clipped)
        if (blocks.isEmpty()) return null
        val ms = ORDER.associateWith { stage -> clipped.filter { it.stage == stage }.sumOf { millis(it) } }
        val totals = totalsOf(ms)
        val sleepMs = clipped.filter { it.stage != SleepStage.AWAKE }.sumOf { millis(it) }
        val rangeMs = Duration.between(from, to).toMillis()
        val awakenings = blocks.indices.count { i ->
            blocks[i].stage == SleepStage.AWAKE &&
                blocks.take(i).any { it.stage != SleepStage.AWAKE } && blocks.drop(i + 1).any { it.stage != SleepStage.AWAKE }
        }
        return NightStages(
            start = from,
            end = to,
            blocks = blocks,
            totals = totals,
            latencyMinutes = if (blocks.size > 1 && blocks[0].stage == SleepStage.AWAKE) {
                Duration.between(blocks[0].start, blocks[1].start).toMillis() / MS_PER_MINUTE
            } else {
                null
            },
            efficiencyPercent = ((sleepMs * PERCENT + rangeMs / 2) / rangeMs).coerceAtMost(PERCENT).toInt(),
            awakenings = awakenings,
            longestDeepMinutes = blocks.filter { it.stage == SleepStage.DEEP }.maxOfOrNull { it.minutes } ?: 0,
            asleepMinutes = sleepMs / MS_PER_MINUTE,
        )
    }

    /**
     * Phasen einer Phase-Wake-Nacht: der Bereich des Schlafprofils ([ChartData.hypnogram]), beschränkt auf die
     * Phasen, die ganz darin liegen (frühester Beginn bis spätestes Ende). `null` ohne Schlafprofil.
     */
    fun forNight(stages: List<StageSpan>, night: NightRecord): NightStages? {
        val h = ChartData.hypnogram(stages, night) ?: return null
        val inRange = stages.filter { !it.start.isBefore(h.start) && !it.end.isAfter(h.end) }
        if (inRange.isEmpty()) return null
        return of(stages, inRange.minOf { it.start }, inRange.maxOf { it.end })
    }

    /** Phasen einer Schlafsitzung aus der Gesundheits-App über die ganze Sitzung («im Bett»). */
    fun forSession(session: HealthSession): NightStages? = of(session.stages, session.start, session.end)

    /** Bekannte Phasen nach Beginn; gleiche Phase, die spätestens am bisherigen Ende beginnt, verlängert den Block. */
    private fun blocksOf(clipped: List<StageSpan>): List<StageBlock> {
        val blocks = mutableListOf<StageBlock>()
        for (span in clipped.filter { ChartData.level(it.stage) != null }) {
            val last = blocks.lastOrNull()
            if (last != null && last.stage == span.stage && !span.start.isAfter(last.end)) {
                blocks[blocks.lastIndex] = last.copy(end = maxOf(last.end, span.end))
            } else {
                blocks += StageBlock(span.stage, span.start, span.end)
            }
        }
        return blocks
    }

    /** Summen mit Prozent nach dem Restverfahren (Summe 100; Gleichstand nach [ORDER]). */
    private fun totalsOf(ms: Map<SleepStage, Long>): List<StageTotal> {
        val total = ms.values.sum()
        val floor = ORDER.associateWith { if (total == 0L) 0L else ms.getValue(it) * PERCENT / total }
        val rest = ORDER.associateWith { if (total == 0L) 0L else ms.getValue(it) * PERCENT % total }
        val missing = if (total == 0L) 0 else (PERCENT - floor.values.sum()).toInt()
        val bonus = ORDER.sortedWith(compareByDescending<SleepStage> { rest.getValue(it) }.thenBy { ORDER.indexOf(it) }).take(missing).toSet()
        return ORDER.map { stage ->
            val percent = (floor.getValue(stage) + if (stage in bonus) 1 else 0).toInt()
            val range = TYPICAL.getValue(stage)
            val rating = when {
                percent < range.low -> StageRating.BELOW
                percent > range.high -> StageRating.ABOVE
                else -> StageRating.TYPICAL
            }
            StageTotal(stage, ms.getValue(stage) / MS_PER_MINUTE, percent, range, rating)
        }
    }

    private fun millis(span: StageSpan): Long = Duration.between(span.start, span.end).toMillis()
}
