package com.phasewake.app.core

import java.time.Instant

/** Schlafphase aus einer Gesundheits-App (Samsung Health, Apple Health u. a.), nachträglich gelesen. */
enum class SleepStage { AWAKE, LIGHT, DEEP, REM, UNKNOWN }

/** Zeitspanne einer Schlafphase. */
data class StageSpan(val start: Instant, val end: Instant, val stage: SleepStage)

/**
 * Abgleich der eigenen Weckentscheidung mit den Schlafphasen der Gesundheits-App.
 *
 * Gut geweckt heisst: im leichten Schlaf, im REM-Schlaf oder schon wach – nicht im Tiefschlaf.
 */
object StageCheck {

    /** Phase zum Zeitpunkt [at]; `null`, wenn keine Spanne ihn abdeckt. */
    fun stageAt(spans: List<StageSpan>, at: Instant): SleepStage? =
        spans.lastOrNull { !at.isBefore(it.start) && at.isBefore(it.end) }?.stage

    /**
     * Phase beim Wecken. Gesundheits-Apps beenden die Schlafsitzung oft genau beim Aufwachen;
     * daher zählt auch die Spanne, die bis zu [toleranceSeconds] vorher endete.
     */
    fun stageAtWake(spans: List<StageSpan>, wokeAt: Instant, toleranceSeconds: Long = 120): SleepStage? =
        stageAt(spans, wokeAt) ?: stageAt(spans, wokeAt.minusSeconds(toleranceSeconds))

    /** `true` bei gutem Moment, `false` bei Tiefschlaf, `null` ohne verwertbare Daten. */
    fun wokeWell(spans: List<StageSpan>, wokeAt: Instant): Boolean? = when (stageAtWake(spans, wokeAt)) {
        SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE -> true
        SleepStage.DEEP -> false
        SleepStage.UNKNOWN, null -> null
    }

    /** Spannen auf das Weckfenster zugeschnitten, für die Darstellung. */
    fun clip(spans: List<StageSpan>, from: Instant, to: Instant): List<StageSpan> = spans
        .filter { it.end.isAfter(from) && it.start.isBefore(to) }
        .map { StageSpan(maxOf(it.start, from), minOf(it.end, to), it.stage) }
        .sortedBy { it.start }
}
