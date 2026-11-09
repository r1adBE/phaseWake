package com.phasewake.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
    fun finishWithoutWakeIsDeadline() {
        val detector = WakeDetector()
        detector.feed(still, still, still)
        assertNull(detector.result)
        assertEquals(Decision.Wake(WakeReason.DEADLINE), detector.finish())
        assertEquals(Decision.Wake(WakeReason.DEADLINE), detector.onEpoch(Epoch(1.0, 90.0)))
    }

    @Test
    fun zeroWarmupHasNoBaselineSoNoLightSleep() {
        val detector = WakeDetector(DetectorConfig(warmupEpochs = 0))
        detector.onEpoch(Epoch(0.003, 52.0))
        // Ohne Basispuls ist die Pulsbedingung nicht erfüllt.
        assertEquals(Decision.Wait, detector.onEpoch(Epoch(0.003, 60.0)))
        detector.onEpoch(Epoch(0.5, 60.0))
        assertEquals(Decision.Wake(WakeReason.AWAKE), detector.onEpoch(Epoch(0.5, 60.0)))
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
    }

    @Test
    fun restingHeartRateIsBaselineOnlyWithoutWarmupPulse() {
        // Aufwärmphase ohne Puls: gelernter Ruhepuls 50 ist Basispuls, 53 >= 52.5 → leichter Schlaf.
        val learned = WakeDetector(DetectorConfig(restingHeartRate = 50.0))
        learned.feed(Epoch(0.0, null), Epoch(0.0, null), Epoch(0.003, 53.0))
        assertEquals(Decision.Wake(WakeReason.LIGHT_SLEEP), learned.onEpoch(Epoch(0.003, 53.0)))
        // Ohne gelernten Ruhepuls wie bisher: kein Basispuls, kein leichter Schlaf.
        val plain = WakeDetector()
        plain.feed(Epoch(0.0, null), Epoch(0.0, null), Epoch(0.003, 53.0))
        assertEquals(Decision.Wait, plain.onEpoch(Epoch(0.003, 53.0)))
        // Mit Puls in der Aufwärmphase gilt dessen Basispuls (52 → 54.6), der Ruhepuls 40 ändert nichts.
        val own = WakeDetector(DetectorConfig(restingHeartRate = 40.0))
        own.feed(still, still, Epoch(0.003, 53.0))
        assertEquals(Decision.Wait, own.onEpoch(Epoch(0.003, 53.0)))
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
