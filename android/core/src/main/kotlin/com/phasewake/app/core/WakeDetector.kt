package com.phasewake.app.core

/**
 * Entscheidet Epoche für Epoche, ob jetzt ein guter Moment zum Wecken ist.
 *
 * Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 3. Ein Detektor gilt für genau ein Weckfenster;
 * für das nächste Fenster wird ein neuer erzeugt. Sobald er einmal geweckt hat, bleibt die
 * Entscheidung bestehen.
 *
 * Reine Logik ohne Plattformabhängigkeit, damit sie mit aufgezeichneten Nächten testbar ist.
 * Die Swift-Umsetzung (`ios/PhaseWakeCore`) muss sich identisch verhalten.
 */
class WakeDetector(config: DetectorConfig = DetectorConfig()) {

    /**
     * Startwerte oder kalibrierte Schwellen; das Grundrauschen ([NoiseFloor]) hebt sie je Epoche höchstens an. Ändert sich
     * nur über [useOnsetFloor].
     */
    var config: DetectorConfig = config
        private set

    /** Alle Bewegungen dieses Fensters inkl. Aufwärmphase, fürs Grundrauschen ([NoiseFloor.windowFloor]). */
    private val motions = mutableListOf<Double?>()

    /** Die letzten entscheidbaren Epochen, höchstens [DetectorConfig.maxWindow]. */
    private val recent = ArrayDeque<Epoch>(config.maxWindow)

    private var epochIndex = 0

    /** Pulse der ersten [DetectorConfig.BASELINE_EPOCHS] Epochen (mindestens der Aufwärmphase), für den Basispuls. */
    private val baselineHeartRates = mutableListOf<Double>()

    /** Weck-Entscheidung, sobald gefallen; sonst `null`. */
    var result: Decision.Wake? = null
        private set

    /** Wertet die nächste Epoche aus. */
    fun onEpoch(epoch: Epoch): Decision {
        result?.let { return it }

        val index = epochIndex++
        motions += epoch.motion
        // Basispuls aus den Epochen vor dieser; danach zählt der Puls dieser Epoche für die folgenden.
        val baseline = baselineHeartRate()
        if (index < maxOf(DetectorConfig.BASELINE_EPOCHS, config.warmupEpochs)) epoch.heartRate?.let { baselineHeartRates += it }
        if (index < config.warmupEpochs) return Decision.Wait

        if (recent.size == config.maxWindow) recent.removeFirst()
        recent.addLast(epoch)

        // Schwellen nach dem Grundrauschen bis und mit dieser Epoche; sie gelten für alle Epochen im Fenster der Regel.
        // Solange die Messung zu wenige Werte hat, gilt das bekannte (Einschlaf-Messung, sonst gelernt); es begrenzt auch
        // das des Fensters ([NoiseFloor.windowFloor]).
        val floor = NoiseFloor.windowFloor(motions, config)
        if (hits(config.awakeWindow, NoiseFloor.wake(config, floor)) >= config.awakeHits) {
            return wake(WakeReason.AWAKE)
        }
        if (hits(config.lightWindow, NoiseFloor.light(config, floor)) >= config.lightHits && heartRateRisen(baseline)) {
            return wake(WakeReason.LIGHT_SLEEP)
        }
        return Decision.Wait
    }

    /** Zielzeit erreicht: liefert die bestehende Entscheidung oder weckt mit [WakeReason.DEADLINE]. */
    fun finish(): Decision.Wake = result ?: wake(WakeReason.DEADLINE)

    /** Zählt unter den letzten [window] Epochen jene mit Bewegung ≥ [threshold]. Leere Bewegung zählt nie. */
    private fun hits(window: Int, threshold: Double): Int {
        var count = 0
        for (i in maxOf(0, recent.size - window) until recent.size) {
            val motion = recent[i].motion
            if (motion != null && motion >= threshold) count++
        }
        return count
    }

    /**
     * Basispuls: Median der Pulse aus den ersten [DetectorConfig.BASELINE_EPOCHS] Epochen des Fensters, sobald es
     * mindestens [DetectorConfig.MIN_BASELINE_HEART_RATES] sind; mit gelerntem Ruhepuls ([DetectorConfig.restingHeartRate])
     * begrenzt auf Ruhepuls bis [DetectorConfig.RESTING_CLAMP_FACTOR] × Ruhepuls. Sonst Ruhepuls ×
     * [DetectorConfig.RESTING_FALLBACK_FACTOR], ohne Ruhepuls `null`.
     */
    private fun baselineHeartRate(): Double? {
        val resting = config.restingHeartRate
        if (baselineHeartRates.size < DetectorConfig.MIN_BASELINE_HEART_RATES) {
            return resting?.let { it * DetectorConfig.RESTING_FALLBACK_FACTOR }
        }
        val sorted = baselineHeartRates.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        return if (resting == null) median else minOf(maxOf(median, resting), resting * DetectorConfig.RESTING_CLAMP_FACTOR)
    }

    /**
     * Pulsbedingung: aktueller Puls (Mittel der letzten zwei Epochen) liegt mindestens um
     * [DetectorConfig.heartRateRise] über dem Basispuls [baseline] ([baselineHeartRate]). Fehlt einer der beiden Werte,
     * ist sie nicht erfüllt: Bewegung allein reicht für [WakeReason.LIGHT_SLEEP] nicht («Phase nicht behaupten»).
     */
    private fun heartRateRisen(baseline: Double?): Boolean {
        if (baseline == null) return false
        var sum = 0.0
        var count = 0
        for (i in maxOf(0, recent.size - CURRENT_HEART_RATE_EPOCHS) until recent.size) {
            recent[i].heartRate?.let {
                sum += it
                count++
            }
        }
        if (count == 0) return false
        return sum / count >= baseline * (1.0 + config.heartRateRise)
    }

    /**
     * Übergangener Moment (kurze Nacht, [ShortNight.blocksEarly]): Die Entscheidung wird verworfen, die Erkennung läuft
     * weiter und kann einen neuen Moment melden. Die Treffer des übergangenen Moments zählen nicht mehr: Die Regeln sehen
     * nur Epochen nach dieser Grenze, ein neuer Moment braucht neue Treffer (sonst weckte der alte, sobald die Sperre
     * endet). Grundrauschen und Basispuls bleiben. Identisch zu `WakeDetector.rearm` (Swift).
     */
    fun rearm() {
        result = null
        recent.clear()
    }

    /**
     * Grundrauschen der Einschlaf-Messung dieser Nacht, das erst nach Fensterbeginn bekannt wird (Apple Watch rechnet es
     * zu Beginn der Sitzung nach): gilt ab der nächsten Epoche wie [DetectorConfig.withOnsetFloor]. Identisch zu Swift.
     */
    fun useOnsetFloor(floor: Double?) {
        config = config.withOnsetFloor(floor)
    }

    private fun wake(reason: WakeReason): Decision.Wake = Decision.Wake(reason).also { result = it }

    private companion object {
        const val CURRENT_HEART_RATE_EPOCHS = 2
    }
}
