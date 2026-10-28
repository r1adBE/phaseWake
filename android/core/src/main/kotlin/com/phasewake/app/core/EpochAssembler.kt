package com.phasewake.app.core

/**
 * Ordnet Sensorwerte anhand ihres Zeitstempels den 30-s-Epochen zu.
 *
 * Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 7. Weil die Zuordnung über den Zeitstempel des
 * Sensors läuft und nicht über die Ankunftszeit, darf der Sensor Werte gesammelt (gebündelt)
 * liefern; das spart Strom, ohne die Epochen zu verfälschen.
 *
 * @param startNanos Fensterbeginn auf derselben monotonen Uhr wie die Sensor-Zeitstempel.
 */
class EpochAssembler(
    private val startNanos: Long,
    private val epochNanos: Long = DetectorConfig.EPOCH_SECONDS * 1_000_000_000L,
    private val motion: MotionEnergyAccumulator = MotionEnergyAccumulator(),
    private val heartRate: HeartRateAccumulator = HeartRateAccumulator(),
) {
    init {
        require(epochNanos > 0) { "epochNanos muss positiv sein" }
    }

    /** Index der Epoche, in die gerade Werte fliessen. */
    var currentIndex: Long = 0
        private set

    /** Nimmt einen Beschleunigungswert auf. @return Epochen, die dadurch abgeschlossen wurden. */
    fun addMotion(timestampNanos: Long, x: Double, y: Double, z: Double): List<Epoch> {
        val index = indexOf(timestampNanos) ?: return emptyList()
        if (index < currentIndex) return emptyList() // verspätet: Epoche ist schon abgeschlossen
        val closed = closeUntil(index)
        motion.add(timestampNanos, x, y, z)
        return closed
    }

    /** Nimmt einen Pulswert auf. @return Epochen, die dadurch abgeschlossen wurden. */
    fun addHeartRate(timestampNanos: Long, bpm: Double): List<Epoch> {
        val index = indexOf(timestampNanos) ?: return emptyList()
        if (index < currentIndex) return emptyList()
        val closed = closeUntil(index)
        heartRate.add(bpm)
        return closed
    }

    /**
     * Schliesst alle Epochen ab, die vor [nowNanos] − [graceNanos] enden. Für den Fall, dass der
     * Sensor länger nichts liefert; die Nachfrist deckt die Bündelungs-Verzögerung ab.
     */
    fun advance(nowNanos: Long, graceNanos: Long): List<Epoch> {
        val index = indexOf(nowNanos - graceNanos) ?: return emptyList()
        return closeUntil(index)
    }

    private fun indexOf(timestampNanos: Long): Long? =
        if (timestampNanos < startNanos) null else (timestampNanos - startNanos) / epochNanos

    private fun closeUntil(index: Long): List<Epoch> {
        if (index <= currentIndex) return emptyList()
        val closed = ArrayList<Epoch>((index - currentIndex).coerceAtMost(MAX_BATCH).toInt())
        while (currentIndex < index) {
            closed += Epoch(motion.closeEpoch(), heartRate.closeEpoch())
            currentIndex++
        }
        return closed
    }

    private companion object {
        const val MAX_BATCH = 1024L
    }
}
