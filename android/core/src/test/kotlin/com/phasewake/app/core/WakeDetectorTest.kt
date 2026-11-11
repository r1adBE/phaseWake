package com.phasewake.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WakeDetectorTest {

    private val still = Epoch(motion = 0.0, heartRate = 52.0)

    private fun WakeDetector.feed(vararg epochs: Epoch): List<Decision> = epochs.map(::onEpoch)

    @Test
    fun warmupNeverWakesEvenWhenAwake() {
        val detector = WakeDetector()
        val decisions = detector.feed(Epoch(1.0, 80.0), Epoch(1.0, 80.0))
        assertEquals(listOf<Decision>(Decision.Wait, Decision.Wait), decisions)
    }

    @Test
    fun warmupMotionDoesNotCountLater() {
        val detector = WakeDetector()
        detector.feed(Epoch(1.0, 52.0), Epoch(1.0, 52.0))
        // Erste entscheidbare Epoche ist ruhig; die Aufwärmphase darf nicht als Treffer zählen.
        assertEquals(Decision.Wait, detector.onEpoch(still))
    }

    @Test
    fun awakeNeedsTwoOfThree() {
        val detector = WakeDetector()
        detector.feed(still, still, Epoch(0.2, 52.0), still)
        assertEquals(Decision.Wake(WakeReason.AWAKE), detector.onEpoch(Epoch(0.2, 52.0)))
    }

    @Test
    fun awakeHitsOutsideWindowDoNotCount() {
        val detector = WakeDetector()
        // Treffer in Epoche 2 und 5: zwischen ihnen liegen zwei ruhige Epochen, Fenster 3 erfasst nur eine.
        val decisions = detector.feed(still, still, Epoch(0.2, 52.0), still, still, Epoch(0.2, 52.0))
        assertEquals(Decision.Wait, decisions.last())
    }

    @Test
    fun lightSleepWaitsForHeartRateRise() {
        val detector = WakeDetector()
        detector.feed(still, still, Epoch(0.003, 52.0))
        assertEquals(Decision.Wait, detector.onEpoch(Epoch(0.003, 54.0)))
        // Mittel aus 54 und 56 = 55 >= 52 × 1.05 = 54.6
        assertEquals(Decision.Wake(WakeReason.LIGHT_SLEEP), detector.onEpoch(Epoch(0.003, 56.0)))
    }

    @Test
    fun missingCurrentHeartRateBlocksLightSleep() {
        val detector = WakeDetector()
        detector.feed(still, still, Epoch(0.003, null))
        // Bewegung allein behauptet keinen leichten Schlaf: ohne aktuellen Puls weiter warten.
        assertEquals(Decision.Wait, detector.onEpoch(Epoch(0.003, null)))
        // Die Wach-Regel braucht keinen Puls.
        detector.onEpoch(Epoch(0.5, null))
        assertEquals(Decision.Wake(WakeReason.AWAKE), detector.onEpoch(Epoch(0.5, null)))
    }

    @Test
    fun awakeTakesPrecedenceOverLightSleep() {
        val detector = WakeDetector()
        detector.feed(still, still, Epoch(0.5, 70.0))
        assertEquals(Decision.Wake(WakeReason.AWAKE), detector.onEpoch(Epoch(0.5, 70.0)))
    }

    @Test
    fun missingMotionNeverCounts() {
        val detector = WakeDetector()
        val gap = Epoch(null, 70.0)
        val decisions = detector.feed(still, still, gap, gap, gap, gap)
        assertEquals(Decision.Wait, decisions.last())
    }

    @Test
    fun decisionLatchesAfterWake() {
        val detector = WakeDetector()
        detector.feed(still, still, Epoch(0.5, 52.0), Epoch(0.5, 52.0))
        assertEquals(Decision.Wake(WakeReason.AWAKE), detector.onEpoch(still))
        assertEquals(Decision.Wake(WakeReason.AWAKE), detector.finish())
    }

    @Test
    fun rearmDropsTheMomentAndWaitsForANewOne() {
        val detector = WakeDetector()
        detector.feed(still, still, Epoch(0.5, 52.0), Epoch(0.5, 52.0))
        assertEquals(Decision.Wake(WakeReason.AWAKE), detector.onEpoch(still))
        detector.rearm()
        assertNull(detector.result)
        // Solange die Unruhe im Fenster der Regel liegt, meldet sie sich wieder (die Uhr übergeht sie erneut); danach
        // weckt erst neue Bewegung.
        detector.feed(still, still, still)
        detector.rearm()
        assertEquals(listOf<Decision>(Decision.Wait, Decision.Wait), detector.feed(still, Epoch(0.5, 52.0)))
        assertEquals(Decision.Wake(WakeReason.AWAKE), detector.onEpoch(Epoch(0.5, 52.0)))
    }

    @Test
    fun finishWithoutWakeIsDeadline() {
        val detector = WakeDetector()
        detector.feed(still, still, still)
        assertNull(detector.result)
        assertEquals(Decision.Wake(WakeReason.DEADLINE), detector.finish())
        assertEquals(Decision.Wake(WakeReason.DEADLINE), detector.onEpoch(Epoch(1.0, 90.0)))
    }

    @Test
    fun zeroWarmupHasNoBaselineUntilTwoPulses() {
        val detector = WakeDetector(DetectorConfig(warmupEpochs = 0))
        detector.onEpoch(Epoch(0.003, 52.0))
        // Erst ein Puls davor: kein Basispuls, die Pulsbedingung ist nicht erfüllt.
        assertEquals(Decision.Wait, detector.onEpoch(Epoch(0.003, 60.0)))
        // Ab zwei Pulsen (Vollaudit K-3): Median 56 → 58.8; 60 reicht.
        assertEquals(Decision.Wake(WakeReason.LIGHT_SLEEP), detector.onEpoch(Epoch(0.003, 60.0)))
    }

    @Test
    fun noiseFloorRaisesLightThreshold() {
        val detector = WakeDetector()
        val quiet = Epoch(0.02, 52.0)
        detector.feed(quiet, quiet, quiet, quiet)
        // Rauschen 0.02 → Leicht-Schwelle 0.05: trotz Pulsanstieg kein leichter Schlaf (ohne Rauschen: geweckt).
        assertEquals(Decision.Wait, detector.onEpoch(Epoch(0.02, 60.0)))
        assertEquals(Decision.Wait, detector.onEpoch(Epoch(0.06, 60.0)))
        assertEquals(Decision.Wake(WakeReason.LIGHT_SLEEP), detector.onEpoch(Epoch(0.06, 60.0)))
    }

    @Test
    fun noiseFloorRaisesWakeThreshold() {
        val detector = WakeDetector()
        val quiet = Epoch(0.03, 52.0)
        detector.feed(quiet, quiet, quiet, quiet, quiet)
        // Rauschen 0.03 → Wach-Schwelle 0.15: zweimal 0.12 reicht nicht mehr (ohne Rauschen: wach).
        assertEquals(listOf<Decision>(Decision.Wait, Decision.Wait), detector.feed(Epoch(0.12, 52.0), Epoch(0.12, 52.0)))
        assertEquals(Decision.Wait, detector.onEpoch(Epoch(0.2, 52.0)))
        assertEquals(Decision.Wake(WakeReason.AWAKE), detector.onEpoch(Epoch(0.2, 52.0)))
    }

    @Test
    fun restlessStartStillWakesWithCappedFloor() {
        val detector = WakeDetector()
        val restless = Epoch(0.2358, 52.0)
        detector.feed(Epoch(0.4128, 52.0), restless, restless)
        // Ohne Obergrenze: Rauschen 0.2358 → Wach-Schwelle 1.18, nie wach. Mit 0.04 → 0.2: wach.
        assertEquals(Decision.Wake(WakeReason.AWAKE), detector.onEpoch(Epoch(0.4128, 52.0)))
    }

    @Test
    fun invalidConfigIsRejected() {
        assertFailsWith<IllegalArgumentException> { DetectorConfig(lightThreshold = 0.0) }
        assertFailsWith<IllegalArgumentException> { DetectorConfig(wakeThreshold = 0.001) }
        assertFailsWith<IllegalArgumentException> { DetectorConfig(heartRateRise = -0.1) }
        assertFailsWith<IllegalArgumentException> { DetectorConfig(warmupEpochs = -1) }
        assertFailsWith<IllegalArgumentException> { DetectorConfig(awakeHits = 4) }
        assertFailsWith<IllegalArgumentException> { DetectorConfig(lightHits = 0) }
        assertFailsWith<IllegalArgumentException> { DetectorConfig(personalFloor = -0.01) }
        assertFailsWith<IllegalArgumentException> { DetectorConfig(restingHeartRate = 0.0) }
        assertFailsWith<IllegalArgumentException> { DetectorConfig(personalFloor = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { DetectorConfig(onsetFloor = -0.01) }
    }

    @Test
    fun restingHeartRateFallbackOnlyWithoutPulseAtTheStart() {
        // Die ersten 6 Epochen ohne Puls: Ersatz-Basispuls = Ruhepuls 50 × 1.10 = 55 → nötig 57.75 (Vollaudit K-2).
        val noPulse = Array(6) { Epoch(0.0, null) }
        val learned = WakeDetector(DetectorConfig(restingHeartRate = 50.0))
        learned.feed(*noPulse, Epoch(0.003, 58.0))
        assertEquals(Decision.Wake(WakeReason.LIGHT_SLEEP), learned.onEpoch(Epoch(0.003, 58.0)))
        // Ohne gelernten Ruhepuls wie bisher: kein Basispuls, kein leichter Schlaf.
        val plain = WakeDetector()
        plain.feed(*noPulse, Epoch(0.003, 58.0))
        assertEquals(Decision.Wait, plain.onEpoch(Epoch(0.003, 58.0)))
        // Mit Puls zu Beginn gilt dessen Basispuls (52 → 54.6); der Ruhepuls 40 begrenzt ihn nur nach unten.
        val own = WakeDetector(DetectorConfig(restingHeartRate = 40.0))
        own.feed(still, still, Epoch(0.003, 53.0))
        assertEquals(Decision.Wait, own.onEpoch(Epoch(0.003, 53.0)))
    }

    @Test
    fun morningPulseNearRestingIsNoRise() {
        // Vollaudit K-2: Ruhepuls 50 (10. Perzentil), morgens 56 bei leichter Bewegung, Fensterbeginn ohne Puls.
        // Früher Basispuls 50 → 52.5: Die Bewegung allein weckte. Jetzt 55 → 57.75: kein Anstieg.
        val detector = WakeDetector(DetectorConfig(restingHeartRate = 50.0))
        val decisions = detector.feed(*Array(6) { Epoch(0.0, null) }, *Array(20) { Epoch(0.003, 56.0) })
        assertTrue(decisions.all { it == Decision.Wait })
    }

    @Test
    fun baselineIsTheMedianOfTheFirstEpochs() {
        // Vollaudit K-3: Aufwärmphase erhöht (64), danach 60. Früher Basispuls 64 → 67.2, der Anstieg auf 63–64 blieb
        // unerkannt. Jetzt Median der ersten 6 Epochen (64, 64, 60, 60, 60, 60) = 60 → 63.
        val detector = WakeDetector()
        val calm = Epoch(0.0, 60.0)
        detector.feed(Epoch(0.0, 64.0), Epoch(0.0, 64.0), calm, calm, calm, calm, calm, calm)
        assertEquals(Decision.Wait, detector.onEpoch(Epoch(0.003, 63.0)))
        assertEquals(Decision.Wake(WakeReason.LIGHT_SLEEP), detector.onEpoch(Epoch(0.003, 64.0)))
    }

    @Test
    fun learnedRestingHeartRateBoundsTheBaseline() {
        // Basispuls des Fensters 45 unter dem Ruhepuls 50: gilt 50 → 52.5; 52 ist kein Anstieg (ohne Grenze 47.25).
        val low = WakeDetector(DetectorConfig(restingHeartRate = 50.0))
        low.feed(*Array(6) { Epoch(0.0, 45.0) }, Epoch(0.003, 52.0))
        assertEquals(Decision.Wait, low.onEpoch(Epoch(0.003, 52.0)))
        // Basispuls 80 über Ruhepuls × 1.3 = 65: gilt 65 → 68.25; 70 ist ein Anstieg (ohne Grenze 84 nötig).
        val high = WakeDetector(DetectorConfig(restingHeartRate = 50.0))
        high.feed(*Array(6) { Epoch(0.0, 80.0) }, Epoch(0.003, 70.0))
        assertEquals(Decision.Wake(WakeReason.LIGHT_SLEEP), high.onEpoch(Epoch(0.003, 70.0)))
    }

    @Test
    fun restlessStartIsCappedByTheLearnedFloor() {
        // Vollaudit K-1: Gelernt 0.018. Das Fenster beginnt unruhig (0.07–0.09): Ohne Obergrenze Rauschen 0.04 → Wach 0.2,
        // zweimal 0.14 bliebe still. Mit 1.5 × 0.018 = 0.027 → Wach 0.135: wach.
        val restless = listOf(0.07, 0.09, 0.08, 0.075, 0.085, 0.07, 0.09, 0.08).map { Epoch(it, 60.0) }
        val learned = WakeDetector(DetectorConfig(personalFloor = 0.018))
        learned.feed(*restless.toTypedArray(), Epoch(0.14, 60.0))
        assertEquals(Decision.Wake(WakeReason.AWAKE), learned.onEpoch(Epoch(0.14, 60.0)))
        // Ohne gelernten Wert wie bisher (Obergrenze 0.04).
        val plain = WakeDetector()
        plain.feed(*restless.toTypedArray(), Epoch(0.14, 60.0))
        assertEquals(Decision.Wait, plain.onEpoch(Epoch(0.14, 60.0)))
    }

    @Test
    fun onsetFloorOfTheNightComesBeforeTheLearnedOne() {
        // Einschlaf-Messung dieser Nacht 0.012, gelernt 0.03: Obergrenze 1.5 × 0.012 = 0.018 → Wach 0.1 (Startwert).
        val restless = Array(8) { Epoch(0.08, 60.0) }
        val config = DetectorConfig(personalFloor = 0.03).withOnsetFloor(0.012)
        assertEquals(0.018, NoiseFloor.windowFloor(restless.map { it.motion }, config)!!, 1e-12)
        val detector = WakeDetector(config)
        detector.feed(*restless, Epoch(0.12, 60.0))
        assertEquals(Decision.Wake(WakeReason.AWAKE), detector.onEpoch(Epoch(0.12, 60.0)))
        // Nur gelernt 0.03: Obergrenze 0.04 (MAX_FLOOR) → Wach 0.2, 0.12 bleibt still.
        assertEquals(0.04, NoiseFloor.windowFloor(restless.map { it.motion }, DetectorConfig(personalFloor = 0.03))!!, 1e-12)
        // Zu wenige Werte: das der Einschlaf-Messung gilt (wie das gelernte).
        assertEquals(0.012, NoiseFloor.windowFloor(listOf(null, 0.5), config)!!, 1e-12)
        // Ruhiger als das bekannte: das eigene des Fensters gilt.
        assertEquals(0.01, NoiseFloor.windowFloor(List(8) { 0.01 }, config)!!, 1e-12)
        // Ungültig gilt als keines.
        assertNull(DetectorConfig().withOnsetFloor(Double.NaN).onsetFloor)
    }

    @Test
    fun onsetFloorKnownAfterTheWindowBegan() {
        // Apple Watch: Das Grundrauschen der Einschlaf-Messung kommt erst nach Fensterbeginn; ab dann gilt es (K-1).
        val restless = Array(8) { Epoch(0.08, 60.0) }
        val detector = WakeDetector(DetectorConfig(personalFloor = 0.03))
        detector.feed(*restless, Epoch(0.12, 60.0))
        assertEquals(Decision.Wait, detector.onEpoch(Epoch(0.12, 60.0)))
        detector.useOnsetFloor(0.012)
        assertEquals(0.012, detector.config.onsetFloor)
        // Die beiden 0.12 davor liegen noch im Fenster der Wach-Regel: Ab jetzt zählen sie (Schwelle 0.1).
        assertEquals(Decision.Wake(WakeReason.AWAKE), detector.onEpoch(still))
        // Ungültig: keines.
        detector.useOnsetFloor(Double.NaN)
        assertNull(detector.config.onsetFloor)
    }

    @Test
    fun rearmNeedsNewHitsAfterTheBoundary() {
        // Vollaudit K-5: Der übergangene Moment (zweimal 0.5) liegt nach rearm() noch im Fenster der Wach-Regel. Früher
        // weckte er wieder, sobald die Sperre endete (bei Zielzeit − 10 Min); jetzt zählen nur neue Treffer.
        val detector = WakeDetector()
        detector.feed(still, still, Epoch(0.5, 52.0))
        assertEquals(Decision.Wake(WakeReason.AWAKE), detector.onEpoch(Epoch(0.5, 52.0)))
        detector.rearm()
        assertEquals(Decision.Wait, detector.onEpoch(still))
        assertEquals(Decision.Wait, detector.onEpoch(Epoch(0.5, 52.0)))
        assertEquals(Decision.Wake(WakeReason.AWAKE), detector.onEpoch(Epoch(0.5, 52.0)))
    }

    @Test
    fun invalidLearnedValuesCountAsNone() {
        // Vollaudit K-6: kaputte Werte aus einer Nachricht dürfen den Start des Fensters nicht abbrechen.
        for (floor in listOf(Double.NaN, -0.01, Double.POSITIVE_INFINITY, 5.0)) {
            assertNull(DetectorConfig().withLearned(floor, null).personalFloor, "$floor")
        }
        for (pulse in listOf(Double.NaN, 0.0, -50.0, 10.0, 400.0, Double.POSITIVE_INFINITY)) {
            assertNull(DetectorConfig().withLearned(null, pulse).restingHeartRate, "$pulse")
        }
        val ok = DetectorConfig().withLearned(0.02, 52.0)
        assertEquals(0.02, ok.personalFloor)
        assertEquals(52.0, ok.restingHeartRate)
        assertEquals(0.0, DetectorConfig().withLearned(0.0, null).personalFloor)
        assertEquals(DetectorConfig(), Learning.withLearned(DetectorConfig(), Learning.Values(Double.NaN, -1.0, null, 7)))
        // Das Grundrauschen der Einschlaf-Messung bleibt beim Übernehmen erhalten.
        assertEquals(0.01, DetectorConfig().withOnsetFloor(0.01).withLearned(0.02, 52.0).onsetFloor)
        WakeDetector(DetectorConfig().withLearned(Double.NaN, Double.NaN)).onEpoch(still)
    }

    @Test
    fun personalFloorAppliesBeforeTheWindowHasItsOwn() {
        // Sensor liefert anfangs keine Bewegung: Ohne eigenes Grundrauschen gälte 0.002, 0.03 wäre leichte Bewegung.
        val gap = Epoch(null, 52.0)
        val plain = WakeDetector()
        plain.feed(gap, gap, gap, Epoch(0.03, 57.0))
        assertEquals(Decision.Wake(WakeReason.LIGHT_SLEEP), plain.onEpoch(Epoch(0.03, 57.0)))
        // Gelernt 0.02 → Leicht-Schwelle 0.05: 0.03 ist Rauschen.
        val learned = WakeDetector(DetectorConfig(personalFloor = 0.02))
        learned.feed(gap, gap, gap, Epoch(0.03, 57.0))
        assertEquals(Decision.Wait, learned.onEpoch(Epoch(0.03, 57.0)))
    }
}
