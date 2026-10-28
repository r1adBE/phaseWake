package ch.r1ad.phasewake.core

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
    fun missingCurrentHeartRateLetsMotionDecide() {
        val detector = WakeDetector()
        detector.feed(still, still, Epoch(0.003, null))
        assertEquals(Decision.Wake(WakeReason.LIGHT_SLEEP), detector.onEpoch(Epoch(0.003, null)))
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
    fun zeroWarmupHasNoBaselineAndDecidesImmediately() {
        val detector = WakeDetector(DetectorConfig(warmupEpochs = 0))
        detector.onEpoch(Epoch(0.003, 52.0))
        assertEquals(Decision.Wake(WakeReason.LIGHT_SLEEP), detector.onEpoch(Epoch(0.003, 52.0)))
    }

    @Test
    fun invalidConfigIsRejected() {
        assertFailsWith<IllegalArgumentException> { DetectorConfig(lightThreshold = 0.0) }
        assertFailsWith<IllegalArgumentException> { DetectorConfig(wakeThreshold = 0.001) }
        assertFailsWith<IllegalArgumentException> { DetectorConfig(heartRateRise = -0.1) }
        assertFailsWith<IllegalArgumentException> { DetectorConfig(warmupEpochs = -1) }
        assertFailsWith<IllegalArgumentException> { DetectorConfig(awakeHits = 4) }
        assertFailsWith<IllegalArgumentException> { DetectorConfig(lightHits = 0) }
    }
}
