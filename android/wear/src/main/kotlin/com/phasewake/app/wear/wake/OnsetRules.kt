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
     * Längste Lücke ungemessener Epochen (10 Min), die eine ruhige Strecke nicht unterbricht. Regulär liegen 8 dazwischen
     * (Takt 10 Epochen, [nextTick]); so übersteht eine Strecke einen verspäteten Alarm, nicht aber eine längere Pause.
     */
    const val MAX_GAP_EPOCHS = 20

    /**
     * Stichproben: Die ruhige Strecke muss so viele Epochen über [SleepOnset.STILL_EPOCHS] hinaus umfassen (10 Min, zwei
     * Stichproben mehr; Audit c3b8b01 E-4). Die Stichproben sehen nur 2 von 10 Epochen; ohne diese Bestätigung meldete je nach
     * Lage der Stichproben schon eine zufällig ruhige Viertelstunde das Einschlafen (an einer echten Nacht bis 1 Std. zu früh,
     * obwohl die Person danach wieder aktiv war).
     */
    const val SAMPLED_CONFIRM_EPOCHS = 20

    /** Stichproben: höchstens so viele einzelne Zucker je Strecke (bewegte Epoche, die andere der Stichprobe ruhig). */
    const val SAMPLED_MAX_TWITCHES = 1

    /**
     * Einschlafen in Stichproben: wie [SleepOnset.find], aber ungemessene Epochen ([measured] `false`) unterbrechen die
     * Strecke nicht und zählen nicht als ruhig. Die Strecke gemessener Epochen beginnt und endet mit einer ruhigen
     * (Bewegung vorhanden und unter [threshold]) und muss von der ersten bis zur letzten mindestens
     * [SleepOnset.STILL_EPOCHS] + [SAMPLED_CONFIRM_EPOCHS] Epochen (25 Min) umfassen. Dazwischen darf höchstens
     * [SAMPLED_MAX_TWITCHES] einzelner Zucker liegen (bewegte Epoche, deren gemessene Nachbarn ruhig sind; wie
     * [SleepOnset.MAX_TWITCHES]). Eine gemessene leere Epoche, zwei bewegte nacheinander, ein weiterer Zucker oder eine
     * Lücke über [MAX_GAP_EPOCHS] beginnt von vorn.
     * @return Index der ersten Epoche der Strecke, oder `null`.
     */
    fun find(motions: List<Double?>, measured: List<Boolean>, threshold: Double): Int? {
        fun isMeasured(index: Int): Boolean = index in motions.indices && measured.getOrElse(index) { false }
        fun quiet(index: Int): Boolean = motions[index].let { it != null && it < threshold }
        val indices = motions.indices.filter(::isMeasured)
        for ((position, start) in indices.withIndex()) {
            if (!quiet(start)) continue
            var last = start
            var twitches = 0
            for (index in indices.subList(position + 1, indices.size)) {
                if (index - last - 1 > MAX_GAP_EPOCHS) break
                last = index
                if (quiet(index)) {
                    if (index - start + 1 >= SleepOnset.STILL_EPOCHS + SAMPLED_CONFIRM_EPOCHS) return start
                    continue
                }
                twitches++
                val neighbourMoved = (index - 1 >= start && isMeasured(index - 1) && !quiet(index - 1)) ||
                    (isMeasured(index + 1) && !quiet(index + 1))
                if (motions[index] == null || neighbourMoved || twitches > SAMPLED_MAX_TWITCHES) break
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

    /** Epochen je Takt ([OnsetMonitor.TICK_INTERVAL], 5 Min = 10 Epochen). */
    val TICK_EPOCHS: Long = OnsetMonitor.TICK_INTERVAL.toNanos() / OnsetMonitor.EPOCH_NANOS

    /**
     * Nächster Alarm (elapsedRealtime in ns): [OnsetMonitor.SAMPLE_LEAD_MS] vor der Epochengrenze, die [TICK_EPOCHS]
     * Epochen nach der Grenze liegt, die [nowNanos] (plus Vorlauf) am nächsten ist. So bleibt der Takt bei genau
     * 10 Epochen, auch wenn ein Alarm einige Sekunden zu spät kommt (Audit c3b8b01 E-3: «jetzt + 5 Min» aufgerundet ergab
     * bei jeder Verspätung 11 Epochen, Stichproben alle 5½ Min). Abstand zu [nowNanos]: 5 Min ± eine halbe Epoche.
     */
    fun nextTick(startNanos: Long, nowNanos: Long): Long {
        val lead = OnsetMonitor.SAMPLE_LEAD_MS * 1_000_000L
        val elapsed = (nowNanos + lead - startNanos).coerceAtLeast(0L)
        val nearest = (elapsed + OnsetMonitor.EPOCH_NANOS / 2) / OnsetMonitor.EPOCH_NANOS
        return startNanos + (nearest + TICK_EPOCHS) * OnsetMonitor.EPOCH_NANOS - lead
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

    /**
     * Ob Epoche [index] (ab [startNanos], Länge [OnsetMonitor.EPOCH_NANOS]) eine Zeit berührt, in der die Uhr nicht getragen
     * wird (Audit c3b8b01 E-5, Entscheide r1AD 14.11.2026): am Ladegerät (Netzteil an bis ab) oder laut Off-Body-Sensor nicht
     * am Handgelenk. [charging]: diese Zeiten (elapsedRealtime in ns; Ende `null` = dauert an). Solche Epochen gelten als
     * leer: Die abgelegte Uhr misst nur Rauschen, das sonst als Einschlafen zählte; die Messung wartet, bis sie wieder
     * getragen wird.
     */
    fun charging(index: Long, startNanos: Long, charging: List<Pair<Long, Long?>>): Boolean {
        val from = startNanos + index * OnsetMonitor.EPOCH_NANOS
        val until = from + OnsetMonitor.EPOCH_NANOS
        return charging.any { (on, off) -> on < until && (off == null || off > from) }
    }

    /**
     * Wert des Off-Body-Sensors (`Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT`): 0 = nicht am Handgelenk (`true`), 1 = getragen
     * (`false`), sonst unbekannt (`null`, ändert nichts).
     */
    fun offBody(value: Float?): Boolean? = when (value) {
        0f -> true
        1f -> false
        else -> null
    }

    /** Spielraum für [startedLate]: Nach «Gute Nacht» beginnt die Messung Sekundenbruchteile nach dem Tippen. */
    val LATE_START_TOLERANCE: Duration = Duration.ofSeconds(60)

    /**
     * Beginn der Einschlafdauer für [startedLate]: automatisch die Bettzeit (Weckzeit [latest] minus Schlaf
     * [sleepMinutes]), auch wenn die Messung erst danach beginnt; nach «Gute Nacht» das Tippen [since]. Wie
     * `RecorderOnset.bedAt` (Swift) mit dem planmässigen Beginn 60 Min vor der Bettzeit.
     */
    fun plannedBedAt(since: Instant, automatic: Boolean, latest: Instant, sleepMinutes: Int): Instant =
        if (automatic) latest.minus(Duration.ofMinutes(sleepMinutes.toLong())) else since

    /**
     * Ob die Messung erst nach dem Beginn der Einschlafdauer [bedAt] ([plannedBedAt]) beginnt, um mehr als
     * [LATE_START_TOLERANCE] (Tiefenaudit AP3-2, wie `RecorderOnset.startedLate` auf der Apple Watch): automatisch nach
     * der Bettzeit (Neustart der Uhr, späte Änderung, Schalter spät ein), nach «Gute Nacht» beim Wiederaufnehmen nach einem
     * Prozessende. Was dann gilt, sagt [lateStart].
     */
    fun startedLate(measureFrom: Instant, bedAt: Instant): Boolean = measureFrom.isAfter(bedAt.plus(LATE_START_TOLERANCE))

    /** Beginn der Messung gegenüber dem Beginn der Einschlafdauer (Entscheid r1AD 14.11.2026, Prüfrunde 2 HG-2). */
    enum class LateStart {
        /** Rechtzeitig: Die Messung gilt; ein früheres Einschlafen zählt erst ab der Bettzeit ([onsetAt]). */
        ON_TIME,

        /**
         * Zu spät, aber bei sichtbarer App gestartet: Die Person war da wach. Gemessen wird ab jetzt, und jetzt ist auch der
         * Beginn der Einschlafdauer.
         */
        FOREGROUND,

        /**
         * Zu spät im Hintergrund gestartet: Ruhe ab diesem Beginn hiesse nur «schlief schon». Die Nacht gilt als nicht
         * gemessen, ohne Untergrenze kurzer Nächte (vorher fail-closed: Wer spät öffnete, wurde schlechter geweckt).
         */
        BACKGROUND,
    }

    /** Ob die Messung ab [measureFrom] rechtzeitig zu [bedAt] beginnt, sonst ob bei sichtbarer App ([foreground]). Wie `RecorderOnset.lateStart`. */
    fun lateStart(measureFrom: Instant, bedAt: Instant, foreground: Boolean): LateStart = when {
        !startedLate(measureFrom, bedAt) -> LateStart.ON_TIME
        foreground -> LateStart.FOREGROUND
        else -> LateStart.BACKGROUND
    }

    /**
     * Zeitgrenze, verschoben um die Zeit ohne Tragen [away] (am Ladegerät oder abgelegt, Prüfrunde 2 HG-3, wie
     * `RecorderOnset.awayUntil`): [baseEnd] (Beginn plus [OnsetMonitor.MAX_DURATION]) plus [away], höchstens [cap] und
     * [latestGiveUp]; `null` ohne solche Zeit oder wenn die Grenze vor der Weckzeit ohnehin früher gilt. Ohne Verschiebung
     * gab die Messung nach dem Laden auf, bevor eine ruhige Strecke vollständig sein konnte.
     */
    fun awayUntil(baseEnd: Instant, away: Duration, latestGiveUp: Instant, cap: Instant): Instant? {
        if (away <= Duration.ZERO || baseEnd.isAfter(latestGiveUp)) return null
        return minOf(baseEnd.plus(away), cap, latestGiveUp)
    }

    /** Dauer in ns, die die Zeiten [times] (wie bei [charging], Ende `null` = dauert an) zwischen [from] und [until] abdecken, Überlappungen einmal. */
    fun awayNanos(times: List<Pair<Long, Long?>>, from: Long, until: Long): Long {
        var total = 0L
        var reached = from
        for ((on, off) in times.sortedBy { it.first }) {
            val begin = maxOf(on, reached)
            val end = minOf(off ?: until, until)
            if (end > begin) {
                total += end - begin
                reached = end
            }
        }
        return total
    }

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
