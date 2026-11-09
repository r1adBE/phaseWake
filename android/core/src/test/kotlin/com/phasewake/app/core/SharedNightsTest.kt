package com.phasewake.app.core

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
            val detector = WakeDetector(night.config)
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

    @Test
    fun learnedNightsDependOnTheLearnedValues() {
        // Ohne gelernte Werte entscheiden die Lernphasen-Nächte anders: Das zeigt, dass die Angaben wirken.
        val byName = NightFixtures.all().associateBy { it.name }
        fun firstWake(night: NightFixture): Int? {
            val detector = WakeDetector()
            return night.epochs.indexOfFirst { detector.onEpoch(it) is Decision.Wake }.takeIf { it >= 0 }
        }
        assertEquals(5, firstWake(byName.getValue("learned_floor_sensor_start")))
        assertEquals(null, firstWake(byName.getValue("learned_resting_hr")))
    }
}
