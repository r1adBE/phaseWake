package ch.r1ad.phasewake.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prüft alle gemeinsamen Testnächte; dieselben Nächte prüft die Swift-Umsetzung. */
class SharedNightsTest {

    @Test
    fun allNightsMatchExpectation() {
        val nights = NightFixtures.all()
        assertTrue(nights.size >= 9, "Testnächte nicht gefunden in ${NightFixtures.directory().absolutePath}")

        for (night in nights) {
            val detector = WakeDetector()
            var wakeIndex: Int? = null
            var wakeReason: WakeReason? = null
            for ((index, epoch) in night.epochs.withIndex()) {
                val decision = detector.onEpoch(epoch)
                if (decision is Decision.Wake) {
                    wakeIndex = index
                    wakeReason = decision.reason
                    break
                }
            }
            assertEquals(night.expectedIndex, wakeIndex, "${night.name}: Weck-Epoche")
            assertEquals(night.expectedReason, wakeReason, "${night.name}: Grund")
        }
    }
}
