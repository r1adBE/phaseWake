package com.phasewake.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prüft alle gemeinsamen Testnächte; dieselben Nächte prüft die Swift-Umsetzung. */
class SharedNightsTest {

    @Test
    fun allNightsMatchExpectation() {
        val nights = NightFixtures.all()
        // Genau die Nächte laut MANIFEST: Eine fehlende Datei fiele sonst nicht auf.
        assertEquals(
            NightFixtures.manifest().count { it.endsWith(".night") },
            nights.size,
            "Testnächte in ${NightFixtures.directory().absolutePath}",
        )

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
        // Die Leicht-Regel wartet ohne Grundrauschen: Ohne gelernten Wert zählt das Rauschen zu
        // Beginn nicht mehr (früher Epoche 5), es weckt wie mit gelerntem Wert erst die echte Bewegung.
        assertEquals(32, firstWake(byName.getValue("learned_floor_sensor_start")))
        assertEquals(null, firstWake(byName.getValue("learned_resting_hr")))
        // Ohne Obergrenze aus dem gelernten Grundrauschen bleibt der unruhige Beginn still.
        assertEquals(null, firstWake(byName.getValue("restless_start_learned_floor")))
        // Ohne Übergehen weckt der erste Moment (Regel S5: die erste starke Bewegung).
        assertEquals(38, firstWake(byName.getValue("rearm_new_hits")))
        // Ohne gelerntes Grundrauschen hebt das Fenster die Leicht-Schwelle wie früher: erst zur Zielzeit.
        assertEquals(null, firstWake(byName.getValue("real_window_7")))
    }

    @Test
    fun awakeDuringBlockDecidesTheRealNight() {
        // Echte Nacht 19.11.2026: Nur übergehen (ohne «Wach geht vor») weckte erst Epoche 40 mit LIGHT_SLEEP.
        val night = NightFixtures.all().first { it.name == "real_window_8" }
        val detector = WakeDetector(night.config)
        var first: Pair<Int, WakeReason>? = null
        for ((index, epoch) in night.epochs.withIndex()) {
            val decision = detector.onEpoch(epoch) as? Decision.Wake ?: continue
            if (index < 40) {
                // Bei 29 und 34 erst eine bzw. zwei getrennte Bewegungen: nicht wach.
                assertTrue(index == 38 || !detector.awakeDuringBlock(), "Epoche $index")
                detector.rearm()
                continue
            }
            first = index to decision.reason
            break
        }
        assertEquals(40 to WakeReason.LIGHT_SLEEP, first)
        assertEquals(38 to WakeReason.AWAKE, night.firstWake())
    }
}
