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
     * Startwerte oder kalibrierte Schwellen; das Grundrauschen ([NoiseFloor]) hebt sie je Epoche höchstens an. Ändert
     * sich nur über [useOnsetFloor].
     */
    var config: DetectorConfig = config
        private set

    /** Alle Bewegungen dieses Fensters inkl. Aufwärmphase, fürs Grundrauschen ([NoiseFloor.windowFloor]). */
    private val motions = mutableListOf<Double?>()

    /** Die letzten entscheidbaren Epochen, höchstens [DetectorConfig.maxWindow]. */
    private val recent = ArrayDeque<Epoch>(config.maxWindow)

    /**
     * Je Epoche in [recent] (gleiche Stelle): ob ihre Bewegung «Auf und Ab» zeigt ([MotionVariation.varies], mit dem
     * Grundrauschen ihrer Epoche; ohne Grundrauschen `true`). Zählt nur für Regel S5 und die Leicht-Regel.
     */
    private val recentVaries = ArrayDeque<Boolean>(config.maxWindow)

    /**
     * Die letzten [AWAKE_BLOCK_WINDOW] entscheidbaren Epochen, auch über übergangene Momente ([rearm]) hinweg, für
     * [awakeDuringBlock]: je Epoche, ob ihre Bewegung stark war (≥ die wirksame Wach-Schwelle, die für diese Epoche
     * galt); `null` = leere Bewegung.
     */
    private val blockStrong = ArrayDeque<Boolean?>(AWAKE_BLOCK_WINDOW)

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
        if (index < maxOf(DetectorConfig.BASELINE_EPOCHS, config.warmupEpochs)) {
            epoch.heartRate?.let { baselineHeartRates += it }
        }
        if (index < config.warmupEpochs) return Decision.Wait

        // Schwellen nach dem Grundrauschen bis und mit dieser Epoche; sie gelten für alle Epochen im Fenster der Regel.
        // Solange die Messung zu wenige Werte hat, gilt das bekannte (Einschlaf-Messung, sonst gelernt); es begrenzt
        // auch das des Fensters ([NoiseFloor.windowFloor]).
        val floor = NoiseFloor.windowFloor(motions, config)
        val wakeThreshold = NoiseFloor.wake(config, floor)
        // «Auf und Ab» gegen die Bewegungen der laufenden Messung vor dieser Epoche (bleiben über [rearm] hinweg).
        val motion = epoch.motion
        val varies = floor == null || motion == null ||
            MotionVariation.varies(motions.subList(0, motions.size - 1), motion, floor)
        if (recent.size == config.maxWindow) {
            recent.removeFirst()
            recentVaries.removeFirst()
        }
        recent.addLast(epoch)
        recentVaries.addLast(varies)
        // «Wach geht vor» bewertet jede Epoche mit der Wach-Schwelle ihrer Zeit (Audit 23.11.2026).
        if (blockStrong.size == AWAKE_BLOCK_WINDOW) blockStrong.removeFirst()
        blockStrong.addLast(motion?.let { it >= wakeThreshold })
        // Die Wach-Regel zählt jede Bewegung, auch ohne «Auf und Ab».
        if (hits(config.awakeWindow, wakeThreshold, needsVariation = false) >= config.awakeHits) {
            return wake(WakeReason.AWAKE)
        }
        // Leichter Schlaf erst mit Grundrauschen (4 Werte oder Vorwert): Ohne zählte das Rauschen einer
        // Uhr wie der Galaxy Watch (ruhig 0.02) gegen die Startschwelle 0.002 als Bewegung, und es entschied allein der
        // Puls. Regel S5 («Bewegung zählt»): Eine starke Bewegung (≥ wirksame Wach-Schwelle) in den letzten
        // [STRONG_WINDOW] Epochen weckt ohne Pulsbedingung. Die Leicht-Regel (2 leichte Bewegungen) braucht den
        // Pulsanstieg, sonst weckte sie schon auf Rauschen. Die Leicht-Schwelle liegt nie über der des gelernten
        // Grundrauschens ([NoiseFloor.windowLight]). Beide zählen nur Bewegungen mit «Auf und Ab» ([MotionVariation]).
        if (floor == null) return Decision.Wait
        val strong = hits(STRONG_WINDOW, wakeThreshold, needsVariation = true) >= 1
        val lightThreshold = NoiseFloor.windowLight(config, floor)
        val light = hits(config.lightWindow, lightThreshold, needsVariation = true) >= config.lightHits
        if (strong || (light && heartRateRisen(baseline))) {
            return wake(WakeReason.LIGHT_SLEEP)
        }
        return Decision.Wait
    }

    /** Zielzeit erreicht: liefert die bestehende Entscheidung oder weckt mit [WakeReason.DEADLINE]. */
    fun finish(): Decision.Wake = result ?: wake(WakeReason.DEADLINE)

    /**
     * Zählt unter den letzten [window] Epochen jene mit Bewegung ≥ [threshold], mit [needsVariation] nur solche mit
     * «Auf und Ab» ([recentVaries]). Leere Bewegung zählt nie.
     */
    private fun hits(window: Int, threshold: Double, needsVariation: Boolean): Int {
        var count = 0
        for (i in maxOf(0, recent.size - window) until recent.size) {
            val motion = recent[i].motion
            if (motion != null && motion >= threshold && (!needsVariation || recentVaries[i])) count++
        }
        return count
    }

    /**
     * Basispuls: Median der Pulse aus den ersten [DetectorConfig.BASELINE_EPOCHS] Epochen des Fensters, sobald es
     * mindestens [DetectorConfig.MIN_BASELINE_HEART_RATES] sind; mit gelerntem Ruhepuls
     * ([DetectorConfig.restingHeartRate]) begrenzt auf Ruhepuls bis [DetectorConfig.RESTING_CLAMP_FACTOR] × Ruhepuls.
     * Sonst Ruhepuls × [DetectorConfig.RESTING_FALLBACK_FACTOR], ohne Ruhepuls `null`.
     */
    private fun baselineHeartRate(): Double? {
        val resting = config.restingHeartRate
        if (baselineHeartRates.size < DetectorConfig.MIN_BASELINE_HEART_RATES) {
            return resting?.let { it * DetectorConfig.RESTING_FALLBACK_FACTOR }
        }
        val sorted = baselineHeartRates.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        return if (resting == null) median else minOf(
            maxOf(median, resting),
            resting * DetectorConfig.RESTING_CLAMP_FACTOR,
        )
    }

    /**
     * Pulsbedingung: aktueller Puls (Mittel der letzten zwei Epochen) liegt mindestens um
     * [DetectorConfig.heartRateRise] über dem Basispuls [baseline] ([baselineHeartRate]). Fehlt einer der beiden Werte,
     * ist sie nicht erfüllt: Leichte Bewegung allein reicht für [WakeReason.LIGHT_SLEEP] nicht («Phase nicht
     * behaupten»).
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
     * weiter und kann einen neuen Moment melden. Die Treffer des übergangenen Moments zählen nicht mehr: Die Regeln
     * sehen nur Epochen nach dieser Grenze, ein neuer Moment braucht neue Treffer (sonst weckte der alte, sobald die
     * Sperre endet). Grundrauschen, Basispuls und die Bewegungen für «Auf und Ab» ([MotionVariation]) bleiben.
     * Identisch zu `WakeDetector.rearm` (Swift).
     */
    fun rearm() {
        result = null
        recent.clear()
        recentVaries.clear()
    }

    /**
     * «Wach geht vor» während der Sperre der kurzen Nacht (`docs/PHASENERKENNUNG.md` Abschnitt 8): mindestens
     * [AWAKE_BLOCK_EPISODES] getrennte starke Bewegungen (≥ wirksame Wach-Schwelle, die für diese Epoche galt) in den
     * letzten [AWAKE_BLOCK_WINDOW] Epochen, auch über übergangene Momente hinweg. Getrennt heisst: mindestens eine
     * Epoche darunter dazwischen; eine leere Epoche trennt nicht. Mehrere starke Epochen nacheinander (eine Drehung
     * über eine Epochengrenze) zählen einmal; die Wach-Regel (2 von 3) allein genügt während der Sperre darum nicht.
     * Sinkt die Schwelle im Fenster, zählen frühere Bewegungen nicht nachträglich als stark (Audit 23.11.2026).
     */
    fun awakeDuringBlock(): Boolean {
        var episodes = 0
        var strong = false
        for (flag in blockStrong) {
            val now = flag ?: continue
            if (now && !strong) episodes++
            strong = now
        }
        return episodes >= AWAKE_BLOCK_EPISODES
    }

    /**
     * Moment während der Sperre der kurzen Nacht ([ShortNight.blocksEarly]): Ist die Person wach ([awakeDuringBlock]),
     * gilt er mit [WakeReason.AWAKE]; sonst wird er übergangen ([rearm]) und das Ergebnis ist `null`. Identisch zu
     * `WakeDetector.onBlocked` (Swift).
     */
    fun onBlocked(): Decision.Wake? {
        if (awakeDuringBlock()) return wake(WakeReason.AWAKE)
        rearm()
        return null
    }

    /**
     * Grundrauschen der Einschlaf-Messung dieser Nacht, das erst nach Fensterbeginn bekannt wird (Apple Watch rechnet
     * es zu Beginn der Sitzung nach): gilt ab der nächsten Epoche wie [DetectorConfig.withOnsetFloor]. Identisch zu
     * Swift.
     */
    fun useOnsetFloor(floor: Double?) {
        config = config.withOnsetFloor(floor)
    }

    private fun wake(reason: WakeReason): Decision.Wake = Decision.Wake(reason).also { result = it }

    private companion object {
        const val CURRENT_HEART_RATE_EPOCHS = 2

        /** Regel S5: Fenster (letzte Epochen inkl. aktueller), in dem eine Bewegung ≥ wirksame Wach-Schwelle genügt. */
        const val STRONG_WINDOW = 2

        /** «Wach geht vor» ([awakeDuringBlock]): Fenster in Epochen (5 Min). */
        const val AWAKE_BLOCK_WINDOW = 10

        /** «Wach geht vor»: nötige getrennte starke Bewegungen im [AWAKE_BLOCK_WINDOW]. */
        const val AWAKE_BLOCK_EPISODES = 3
    }
}
