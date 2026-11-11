package com.phasewake.app.wear.wake

import com.phasewake.app.core.DetectorConfig
import com.phasewake.app.core.Decision
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.WakeDetector
import com.phasewake.app.core.WakePlan
import com.phasewake.app.core.WakeReason
import com.phasewake.app.wear.schedule.MemoryStore
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Grundrauschen der Einschlaf-Messung je Nacht als Vorwissen im Weckfenster (Vollaudit K-1). */
class OnsetFloorStoreTest {
    private val latest = Instant.parse("2026-11-11T06:30:00Z")
    private val plan = WakePlan("a", latest.minus(Duration.ofMinutes(30)), latest)

    @Test
    fun floorBelongsToItsNight() = runTest {
        val store = OnsetFloorStore(MemoryStore())
        assertNull(store.floorFor(plan))
        store.set("a", latest, 0.012)
        assertEquals(0.012, store.floorFor(plan))
        // Einschlaf-Modus: Zielzeit früher als die eingestellte Weckzeit, gleiche Nacht.
        assertEquals(0.012, store.floorFor(plan.copy(target = latest.minus(Duration.ofHours(1)))))
        // Anderer Wecker, Weckzeit vor der Zielzeit oder eine ältere Nacht: keines.
        assertNull(store.floorFor(plan.copy(alarmId = "b")))
        assertNull(store.floorFor(plan.copy(target = latest.plus(Duration.ofMinutes(1)))))
        assertNull(store.floorFor(plan.copy(target = latest.minus(Duration.ofHours(17)))))
        // Ungültig oder keines: der alte Wert gilt nicht weiter.
        store.set("a", latest, Double.NaN)
        assertNull(store.floorFor(plan))
        store.set("a", latest, 0.012)
        store.set("a", latest, null)
        assertNull(store.floorFor(plan))
    }

    @Test
    fun storedFloorCapsTheWindow() = runTest {
        // Gemessen 0.012 beim Einschlafen, nichts gelernt; das Fenster beginnt unruhig (0.08): Obergrenze 0.018 → Wach 0.1.
        // (Mit einem höheren gelernten Wert gälte das grössere, Fix KN-2: `NoiseFloor.priorFloor`.)
        val store = OnsetFloorStore(MemoryStore())
        store.set("a", latest, 0.012)
        val restless = Array(8) { Epoch(0.08, 60.0) }
        val config = DetectorConfig()
        val withOnset = WakeDetector(config.withOnsetFloor(store.floorFor(plan)))
        restless.forEach { withOnset.onEpoch(it) }
        withOnset.onEpoch(Epoch(0.12, 60.0))
        assertEquals(Decision.Wake(WakeReason.AWAKE), withOnset.onEpoch(Epoch(0.12, 60.0)))
        // Ohne gespeichertes Grundrauschen (andere Nacht) wie bisher: Wach 0.2, 0.12 bleibt still.
        val without = WakeDetector(config.withOnsetFloor(store.floorFor(plan.copy(alarmId = "b"))))
        restless.forEach { without.onEpoch(it) }
        without.onEpoch(Epoch(0.12, 60.0))
        assertEquals(Decision.Wait, without.onEpoch(Epoch(0.12, 60.0)))
    }
}
