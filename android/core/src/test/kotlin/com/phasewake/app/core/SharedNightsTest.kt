package com.phasewake.app.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Prüft alle gemeinsamen Testnächte; dieselben Nächte prüft die Swift-Umsetzung. */
class SharedNightsTest {

    @Test
    fun allNightsMatchExpectation() {
        val nights = NightFixtures.all()
        // Genau die Nächte laut MANIFEST (Vollaudit K-8): Eine fehlende Datei fiele sonst nicht auf.
        assertEquals(NightFixtures.manifest().count { it.endsWith(".night") }, nights.size, "Testnächte in ${NightFixtures.directory().absolutePath}")

        for (night in nights) {
            val wake = night.firstWake()
            assertEquals(night.expectedIndex, wake?.first, "${night.name}: Weck-Epoche")
            assertEquals(night.expectedReason, wake?.second, "${night.name}: Grund")
        }
    }

    @Test
    fun folderMatchesTheManifest() {
        val manifest = NightFixtures.manifest()
        assertEquals(manifest.sorted(), NightFixtures.files(), "Dateien in shared/testdata und MANIFEST")
        assertEquals(manifest.size, manifest.toSet().size, "MANIFEST ohne doppelte Einträge")
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
        // Ohne Obergrenze aus dem gelernten Grundrauschen bleibt der unruhige Beginn still (Vollaudit K-1).
        assertEquals(null, firstWake(byName.getValue("restless_start_learned_floor")))
        // Ohne Übergehen weckt der erste Moment (Vollaudit K-5).
        assertEquals(39, firstWake(byName.getValue("rearm_new_hits")))
    }
}
