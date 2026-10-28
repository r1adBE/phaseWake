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
class WakeDetector(private val config: DetectorConfig = DetectorConfig()) {

    /** Die letzten entscheidbaren Epochen, höchstens [DetectorConfig.maxWindow]. */
    private val recent = ArrayDeque<Epoch>(config.maxWindow)

    private var epochIndex = 0
    private var warmupHeartRateSum = 0.0
    private var warmupHeartRateCount = 0
    private var baselineHeartRate: Double? = null

    /** Weck-Entscheidung, sobald gefallen; sonst `null`. */
    var result: Decision.Wake? = null
        private set

    /** Wertet die nächste Epoche aus. */
    fun onEpoch(epoch: Epoch): Decision {
        result?.let { return it }

        val index = epochIndex++
        if (index < config.warmupEpochs) {
            epoch.heartRate?.let {
                warmupHeartRateSum += it
                warmupHeartRateCount++
            }
            return Decision.Wait
        }
        if (index == config.warmupEpochs && warmupHeartRateCount > 0) {
            baselineHeartRate = warmupHeartRateSum / warmupHeartRateCount
        }

        if (recent.size == config.maxWindow) recent.removeFirst()
        recent.addLast(epoch)

        if (hits(config.awakeWindow, config.wakeThreshold) >= config.awakeHits) {
            return wake(WakeReason.AWAKE)
        }
        if (hits(config.lightWindow, config.lightThreshold) >= config.lightHits && heartRateRisen()) {
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
     * Pulsbedingung: aktueller Puls (Mittel der letzten zwei Epochen) liegt mindestens um
     * [DetectorConfig.heartRateRise] über dem Basispuls. Fehlt einer der beiden Werte, ist sie erfüllt.
     */
    private fun heartRateRisen(): Boolean {
        val baseline = baselineHeartRate ?: return true
        var sum = 0.0
        var count = 0
        for (i in maxOf(0, recent.size - CURRENT_HEART_RATE_EPOCHS) until recent.size) {
            recent[i].heartRate?.let {
                sum += it
                count++
            }
        }
        if (count == 0) return true
        return sum / count >= baseline * (1.0 + config.heartRateRise)
    }

    private fun wake(reason: WakeReason): Decision.Wake = Decision.Wake(reason).also { result = it }

    private companion object {
        const val CURRENT_HEART_RATE_EPOCHS = 2
    }
}
