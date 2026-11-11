package com.phasewake.app.wear.wake

import com.phasewake.app.core.DetectorConfig
import com.phasewake.app.core.NoiseFloor
import com.phasewake.app.core.SleepOnset
import com.phasewake.app.wear.schedule.AutoOnset
import java.time.Duration
import java.time.Instant

/** Reine Regeln der Einschlaf-Messung in Stichproben, ohne Android (Tests: `OnsetRulesTest`). */
internal object OnsetRules {
    /**
     * Längste Lücke ungemessener Epochen (10 Min), die eine ruhige Strecke nicht unterbricht. Regulär liegen 8 dazwischen;
     * so übersteht eine Strecke einen verspäteten Alarm, nicht aber eine längere Pause.
     */
    const val MAX_GAP_EPOCHS = 20

    /**
     * Einschlafen in Stichproben: wie [SleepOnset.find], aber ungemessene Epochen ([measured] `false`) unterbrechen die
     * Strecke nicht und zählen nicht als ruhig. Die Strecke ruhiger gemessener Epochen (Bewegung vorhanden und unter
     * [threshold]) muss mindestens [SleepOnset.STILL_EPOCHS] Epochen (15 Min) umfassen, von der ersten bis zur letzten.
     * Eine gemessene leere oder bewegte Epoche oder eine Lücke über [MAX_GAP_EPOCHS] beginnt von vorn. Sind alle Epochen
     * gemessen, gilt genau [SleepOnset.find].
     * @return Index der ersten Epoche der Strecke, oder `null`.
     */
    fun find(motions: List<Double?>, measured: List<Boolean>, threshold: Double): Int? {
        var runStart = -1
        var lastMeasured = -1
        for ((index, motion) in motions.withIndex()) {
            if (!measured.getOrElse(index) { false }) continue
            if (runStart >= 0 && index - lastMeasured - 1 > MAX_GAP_EPOCHS) runStart = -1
            lastMeasured = index
            if (motion != null && motion < threshold) {
                if (runStart < 0) runStart = index
                if (index - runStart + 1 >= SleepOnset.STILL_EPOCHS) return runStart
            } else {
                runStart = -1
            }
        }
        return null
    }

    /**
     * Wertet die neuen Epochen ab Index [from] einzeln aus, als kämen sie eine nach der anderen (Fix KN-3, wie
     * `RecorderOnset.measure` auf der Apple Watch): je Epoche das Grundrauschen der gemessenen ([measured]) bis dahin
     * ([NoiseFloor.floor], solange zu wenige: [personalFloor]) und die Regel mit der wirksamen Leicht-Schwelle
     * ([SleepOnset.find] bei [continuous], sonst [find]). So zählen Epochen nach der erkennenden nicht, auch wenn sie im
     * selben Bündel kamen.
     * @param floor Grundrauschen vor diesen Epochen (bleibt ohne neue).
     * @return Beginn der ruhigen Strecke (oder `null`) und das Grundrauschen bis zur erkennenden bzw. letzten Epoche.
     */
    fun scan(
        motions: List<Double?>, measured: List<Boolean>, from: Int, threshold: Double, personalFloor: Double?, continuous: Boolean,
        floor: Double?,
    ): Pair<Int?, Double?> {
        var current = floor
        for (end in from + 1..motions.size) {
            val seen = motions.subList(0, end)
            val seenMeasured = measured.subList(0, end)
            current = NoiseFloor.floor(seen.filterIndexed { i, _ -> seenMeasured[i] }, personalFloor)
            val quiet = NoiseFloor.light(threshold, current)
            val index = if (continuous) SleepOnset.find(seen, quiet) else find(seen, seenMeasured, quiet)
            if (index != null) return index to current
        }
        return null to current
    }

    /** Erste Epoche, die bei [nowNanos] noch ganz vor uns liegt (Beginn ≥ jetzt). */
    fun firstFullEpoch(startNanos: Long, nowNanos: Long): Long {
        val elapsed = (nowNanos - startNanos).coerceAtLeast(0L)
        return (elapsed + OnsetMonitor.EPOCH_NANOS - 1) / OnsetMonitor.EPOCH_NANOS
    }

    /**
     * Nächster Alarm (elapsedRealtime in ns): frühestens [OnsetMonitor.TICK_INTERVAL] nach [nowNanos], und zwar
     * [OnsetMonitor.SAMPLE_LEAD_MS] vor einer Epochengrenze, damit die Stichprobe eine volle Epoche früh beginnt.
     */
    fun nextTick(startNanos: Long, nowNanos: Long): Long {
        val lead = OnsetMonitor.SAMPLE_LEAD_MS * 1_000_000L
        val boundary = firstFullEpoch(startNanos, nowNanos + OnsetMonitor.TICK_INTERVAL.toNanos() + lead)
        return startNanos + boundary * OnsetMonitor.EPOCH_NANOS - lead
    }

    /**
     * Grund, warum «Gute Nacht» bzw. die automatische Messung ([automatic]) nicht beginnt, fürs Diagnose-Protokoll;
     * `null`, wenn sie beginnen darf. [since]: Beginn, [latest]: eingestellte Weckzeit dieser Nacht (`null` = keine).
     */
    fun notStartedReason(
        alarmFound: Boolean,
        hasDuration: Boolean,
        automatic: Boolean,
        since: Instant,
        latest: Instant?,
        windowBegun: Boolean,
        alreadyDone: Boolean,
    ): String? = when {
        !alarmFound -> "no alarm"
        !hasDuration && !automatic -> "no sleep duration"
        latest == null -> "no alarm time"
        Duration.between(since, latest) > WakeRules.GOOD_NIGHT_MAX_AHEAD -> "too far ahead"
        automatic && windowBegun -> "window begun"
        automatic && alreadyDone -> "already done"
        else -> null
    }

    /** Epochen der letzten 15 Min ([OnsetMonitor.STAT_INTERVAL]) für [restless]. */
    val RECENT_EPOCHS = (OnsetMonitor.STAT_INTERVAL.seconds / DetectorConfig.EPOCH_SECONDS).toInt()

    /**
     * Unruhig: In den letzten [recent] Epochen hat mindestens eine gemessene Epoche Bewegung an oder über [threshold]
     * (wirksame Leicht-Schwelle). Leere und ungemessene Epochen zählen nicht.
     */
    fun restless(motions: List<Double?>, measured: List<Boolean>, threshold: Double, recent: Int = RECENT_EPOCHS): Boolean {
        val from = (motions.size - recent).coerceAtLeast(0)
        return (from until motions.size).any { i ->
            val motion = motions[i]
            measured.getOrElse(i) { false } && motion != null && motion >= threshold
        }
    }

    /**
     * Neues Ende der Einschlaf-Messung bei spätem Einschlafen, oder `null` (aufgeben): nur, wenn bei [at] die Höchstdauer
     * [maxEnd] erreicht ist (nicht die Grenze [latestGiveUp] vor der Weckzeit), die letzten 15 Min unruhig waren
     * ([restless]) und der Akku nicht unter [AutoOnset.MIN_BATTERY_PERCENT] liegt (unbekannt hindert nicht). Dann
     * [at] + [OnsetMonitor.EXTEND_STEP], höchstens [cap] und [latestGiveUp].
     */
    fun extendUntil(at: Instant, maxEnd: Instant, latestGiveUp: Instant, cap: Instant, restless: Boolean, battery: Int?): Instant? {
        if (at.isBefore(maxEnd) || maxEnd.isAfter(latestGiveUp) || !restless) return null
        if (AutoOnset.skipForBattery(battery)) return null
        val until = minOf(at.plus(OnsetMonitor.EXTEND_STEP), cap, latestGiveUp)
        return until.takeIf { it.isAfter(at) }
    }

    /**
     * Lernphase: Beginn der Einschlafdauer. Nach «Gute Nacht» ([automatic] `false`) der Beginn [since] (die Person liegt
     * im Bett). Die automatische Messung beginnt 60 Min vor der Bettzeit: dann die Bettzeit (Weckzeit [latest] minus
     * Schlaf [sleepMinutes], wie [com.phasewake.app.core.Bedtime.bedtime]), frühestens [since].
     */
    fun bedAt(since: Instant, automatic: Boolean, latest: Instant, sleepMinutes: Int): Instant =
        if (automatic) maxOf(since, latest.minus(Duration.ofMinutes(sleepMinutes.toLong()))) else since

    /**
     * Einschlafzeit aus der erkannten ruhigen Strecke ab [found]: nie vor dem Beginn der Einschlafdauer [bedAt] (Ergebnis
     * von `bedAt(…)`, automatisch die Bettzeit). Die automatische Messung beginnt 60 Min vor der Bettzeit; wer dann schon
     * still liegt (Sofa, Lesen), ist nicht vor der Bettzeit eingeschlafen – sonst rückte die Zielzeit vor und die
     * Untergrenze kurzer Nächte fiele zu früh weg. Nach «Gute Nacht» ist [bedAt] der Beginn der Messung; dann gilt [found] unverändert. Identisch zu
     * `RecorderOnset.onsetAt` (Swift).
     */
    fun onsetAt(found: Instant, bedAt: Instant): Instant = maxOf(found, bedAt)

    /** «give up (max duration)» bzw. «give up», wenn [at] die frühere der beiden Grenzen erreicht hat; sonst `null`. */
    fun giveUpReason(at: Instant, latestGiveUp: Instant, maxEnd: Instant): String? = when {
        at.isBefore(minOf(latestGiveUp, maxEnd)) -> null
        !maxEnd.isAfter(latestGiveUp) -> "give up (max duration)"
        else -> "give up"
    }

    /**
     * Eine Zeile je 15 Min fürs Diagnose-Protokoll (Ursachensuche, Akku-Befund 03.11.2026): aufweckender Sensor (1/0),
     * erhaltene Werte, gemessene Epochen, davon leer, mittlere/grösste Bewegung, wirksame Schwelle, Grundrauschen
     * ([NoiseFloor]), Wakelock in Sekunden, Akku.
     */
    fun statLine(
        label: String,
        wakeUp: Boolean,
        samples: Long,
        epochs: Int,
        empty: Int,
        mean: Double?,
        max: Double?,
        threshold: Double,
        floor: Double?,
        wakeLockSeconds: Long,
        battery: Int?,
    ): String {
        val f = NoiseFloor::format
        return "$label stat wake=${if (wakeUp) 1 else 0} samples=$samples epochs=$epochs empty=$empty " +
            "motion=${f(mean)}/${f(max)} thr=${f(threshold)} floor=${f(floor)} wl=${wakeLockSeconds}s bat=${battery ?: "-"}"
    }
}
