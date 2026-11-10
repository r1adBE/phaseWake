package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Lernphase (Entscheid r1AD 09.11.2026): Die Galaxy Watch zeichnet [NIGHTS] Nächte auf, danach passt Phase Wake den Wecker
 * an: eigenes Grundrauschen ([NoiseFloor], [DetectorConfig.personalFloor]), eigener Ruhepuls
 * ([DetectorConfig.restingHeartRate]) und die Einschlafdauer für die Empfehlung der Schlafenszeit ([bedtimeRange]).
 * Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 10. Reine Logik; identisch zu `Learning` im Swift-Kern (dort zusätzlich die
 * Apple-Fälle: Puls aus Apple Health, Einschlafdauer aus «Im Bett»).
 */
object Learning {
    /** So viele gezählte Nächte braucht die Lernphase (keine Kalendertage). */
    const val NIGHTS: Int = 7

    /**
     * Eine Nacht zählt ab so vielen Epochen mit Bewegung (36 Min). Die Uhr misst in der Lernphase in Stichproben (60 s alle
     * 5 Min, Entscheid r1AD 10.11.2026): 3 Std. ergeben 36 Stichproben = 72 Epochen.
     */
    const val MIN_MEASURED_EPOCHS: Int = 72

    /** … und die Epochen mit Bewegung müssen mindestens so viele Epochen der Nacht überspannen (3 Std., erste bis letzte). */
    const val MIN_SPAN_EPOCHS: Int = 360

    /** Die Uhr zeichnet in der Lernphase nur ab diesem Akkustand (Prozent) automatisch auf; der Wecker hat Vorrang. */
    const val MIN_BATTERY: Int = 50

    /**
     * Ein Ruhepuls je Nacht erst ab so vielen Epochen mit Puls (30 Min, in Stichproben rund 2½ Std. der Nacht); sonst
     * wäre das 10. Perzentil zu zufällig.
     */
    const val MIN_HEART_RATE_EPOCHS: Int = 60

    /** Ruhepuls und Einschlafdauer erst aus so vielen Nächten mit Werten; sonst bleiben sie ungelernt. */
    const val MIN_VALUE_NIGHTS: Int = 3

    /** Einschlafdauer einer Nacht höchstens so lang (Minuten): ein spätes Zubettgehen soll die Empfehlung nicht verschieben. */
    const val MAX_LATENCY_MINUTES: Int = 60

    /** Breite der empfohlenen Schlafenszeit in Minuten. */
    const val BEDTIME_RANGE_MINUTES: Int = 30

    /** Die empfohlene Schlafenszeit endet auf ganzen so vielen Minuten (abgerundet). */
    const val ROUND_MINUTES: Int = 5

    /**
     * Eine aufgezeichnete Nacht: ab [start] je 30-s-Epoche, dazu, falls die Einschlaf-Messung dieser Nacht das Einschlafen
     * erkannt hat, Beginn der Einschlafdauer [bedAt] und Einschlafzeit [asleepAt].
     */
    data class Night(
        val start: Instant,
        val epochs: List<Epoch>,
        val bedAt: Instant? = null,
        val asleepAt: Instant? = null,
    )

    /**
     * Gelernte Werte: Grundrauschen [floor] (m/s²), Ruhepuls [restingHeartRate], Einschlafdauer [latencyMinutes], je
     * `null`, wenn zu wenige Werte da waren; [nights] = gezählte Nächte.
     */
    data class Values(
        val floor: Double?,
        val restingHeartRate: Double?,
        val latencyMinutes: Int?,
        val nights: Int,
    )

    /** Epochen mit Bewegung. */
    fun measuredEpochs(night: Night): Int = night.epochs.count { it.motion != null }

    /**
     * Ob [night] zählt: mindestens [MIN_MEASURED_EPOCHS] Epochen mit Bewegung, und von der ersten bis zur letzten von ihnen
     * mindestens [MIN_SPAN_EPOCHS] Epochen (gilt für Stichproben wie für durchgehende Aufzeichnungen).
     */
    fun counts(night: Night): Boolean {
        val first = night.epochs.indexOfFirst { it.motion != null }
        if (first < 0) return false
        val last = night.epochs.indexOfLast { it.motion != null }
        return measuredEpochs(night) >= MIN_MEASURED_EPOCHS && last - first + 1 >= MIN_SPAN_EPOCHS
    }

    /**
     * Gezählte Nächte der Lernphase ab [since]: Beginn nicht vor [since], [counts], je Nachtdatum ([SleepNotes.nightDate]
     * des Beginns) nur eine, die mit den meisten Epochen mit Bewegung. Älteste zuerst.
     */
    fun counted(nights: List<Night>, since: Instant, zone: ZoneId): List<Night> =
        nights.filter { !it.start.isBefore(since) && counts(it) }
            .groupBy { SleepNotes.nightDate(it.start, zone) }
            .values.map { same -> same.maxBy(::measuredEpochs) }
            .sortedBy { it.start }

    /** «Nacht N von 7» bei [counted] gezählten Nächten: die laufende Nacht, höchstens [NIGHTS]. */
    fun progress(counted: Int): Int = (counted + 1).coerceIn(1, NIGHTS)

    /** Ob die Lernphase mit [counted] gezählten Nächten fertig ist. */
    fun finished(counted: Int): Boolean = counted >= NIGHTS

    /** Grundrauschen einer Nacht nach [NoiseFloor.floor] aus allen ihren Epochen. */
    fun nightFloor(night: Night): Double? = NoiseFloor.floor(night.epochs.map { it.motion })

    /**
     * Ruhepuls einer Nacht: 10. Perzentil der vorhandenen Pulse (Rangmethode, aufsteigend, Position ⌈0.1 · n⌉, gezählt
     * ab 1); `null` unter [MIN_HEART_RATE_EPOCHS] Epochen mit Puls.
     */
    fun nightRestingHeartRate(night: Night): Double? {
        val values = night.epochs.mapNotNull { it.heartRate }.sorted()
        if (values.size < MIN_HEART_RATE_EPOCHS) return null
        return values[(values.size + 9) / 10 - 1]
    }

    /**
     * Einschlafdauer einer Nacht in ganzen Minuten: von [Night.bedAt] bis [Night.asleepAt], höchstens
     * [MAX_LATENCY_MINUTES]. `null` ohne beide Zeiten oder wenn die Einschlafzeit davor liegt (dann ist unbekannt, wann
     * die Person ins Bett ging).
     */
    fun nightLatencyMinutes(night: Night): Int? {
        val bed = night.bedAt ?: return null
        val asleep = night.asleepAt ?: return null
        if (asleep.isBefore(bed)) return null
        return Duration.between(bed, asleep).toMinutes().coerceAtMost(MAX_LATENCY_MINUTES.toLong()).toInt()
    }

    /**
     * Gelernte Werte aus den gezählten Nächten [counted]: Median der Grundrauschen, Median der Ruhepulse (ab
     * [MIN_VALUE_NIGHTS] Nächten mit Ruhepuls), Median der Einschlafdauern (ab [MIN_VALUE_NIGHTS] Nächten mit Wert,
     * gerundet).
     */
    fun learn(counted: List<Night>): Values {
        val floors = counted.mapNotNull(::nightFloor)
        val pulses = counted.mapNotNull(::nightRestingHeartRate)
        val latencies = counted.mapNotNull(::nightLatencyMinutes)
        return Values(
            floor = median(floors),
            restingHeartRate = pulses.takeIf { it.size >= MIN_VALUE_NIGHTS }?.let(::median),
            latencyMinutes = latencies.takeIf { it.size >= MIN_VALUE_NIGHTS }?.let { median(it.map(Int::toDouble)) }?.let { Math.round(it).toInt() },
            nights = counted.size,
        )
    }

    /** Median: mittlerer Wert, bei gerader Anzahl das Mittel der beiden mittleren; `null` ohne Werte. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** [config] mit den gelernten Werten [values] (ohne gelernte Werte unverändert). */
    fun withLearned(config: DetectorConfig, values: Values?): DetectorConfig =
        if (values == null) config else config.copy(personalFloor = values.floor, restingHeartRate = values.restingHeartRate)

    /** Ob das gelernte Grundrauschen [floor] die Leicht-Schwelle von [config] anhebt (für die Anzeige in Worten). */
    fun raisesThresholds(floor: Double?, config: DetectorConfig = DetectorConfig()): Boolean =
        NoiseFloor.light(config, floor?.let { minOf(it, NoiseFloor.MAX_FLOOR) }) > config.lightThreshold

    /**
     * Empfohlene Schlafenszeit vor der Zielzeit [target]: spätestens Zielzeit − Schlaf [sleepMinutes] (Schlafziel bzw.
     * Schlafdauer des Weckers) − Einschlafdauer [latencyMinutes] (ungelernt 0), abgerundet auf [ROUND_MINUTES]; frühestens
     * [BEDTIME_RANGE_MINUTES] davor. Feste Dauern wie [Bedtime.bedtime].
     * @return (frühestens, spätestens).
     */
    fun bedtimeRange(target: Instant, sleepMinutes: Int, latencyMinutes: Int?): Pair<Instant, Instant> {
        val latest = target.minus(Duration.ofMinutes(sleepMinutes.toLong() + (latencyMinutes ?: 0)))
        val step = ROUND_MINUTES * 60_000L
        val rounded = Instant.ofEpochMilli(Math.floorDiv(latest.toEpochMilli(), step) * step)
        return rounded.minus(Duration.ofMinutes(BEDTIME_RANGE_MINUTES.toLong())) to rounded
    }
}
