package com.phasewake.app.wear.schedule

import com.phasewake.app.core.WakePlan
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Plan aus dem Einschlaf-Modus ([OnsetOverride], Audit A-6): gilt nur für die Nacht, zu der er gehört. Wurde der Wecker
 * übersprungen oder verschoben (nächster Plan desselben Weckers weiter weg als die längste Schlafdauer), wird er
 * verworfen, statt in der übersprungenen Nacht zu wecken.
 */
class OnsetOverrideTest {
    private val now = Instant.parse("2026-11-11T22:00:00Z")
    private val target = Instant.parse("2026-11-12T05:30:00Z")
    private val override = WakePlan("a", target.minus(Duration.ofMinutes(30)), target)

    /** Plan von «a» mit Zielzeit [at] (Fenster 30 Min.). */
    private fun plan(at: Instant, id: String = "a") = WakePlan(id, at.minus(Duration.ofMinutes(30)), at)

    @Test
    fun appliesOnlyToTheSameNight() {
        val sameNight = plan(Instant.parse("2026-11-12T06:30:00Z"))
        assertEquals(OnsetOverride.Use.OVERRIDE, OnsetOverride.decide(override, sameNight, now))
        // «Nächstes Mal überspringen»: der nächste Plan ist erst morgen.
        val skipped = plan(Instant.parse("2026-11-13T06:30:00Z"))
        assertEquals(OnsetOverride.Use.CLEAR, OnsetOverride.decide(override, skipped, now))
        // Grenze: genau die längste Schlafdauer nach der Ausnahme gilt noch.
        assertEquals(OnsetOverride.Use.OVERRIDE, OnsetOverride.decide(override, plan(target.plus(Duration.ofHours(12))), now))
        assertEquals(OnsetOverride.Use.CLEAR, OnsetOverride.decide(override, plan(target.plus(Duration.ofHours(12)).plusMillis(1)), now))
        // Anderer Wecker oder keiner: Plan, die Ausnahme bleibt für später.
        assertEquals(OnsetOverride.Use.PLAN, OnsetOverride.decide(override, plan(sameNight.target, id = "b"), now))
        assertEquals(OnsetOverride.Use.PLAN, OnsetOverride.decide(override, null, now))
        // Vorbei: weg.
        assertEquals(OnsetOverride.Use.CLEAR, OnsetOverride.decide(override, sameNight, target))
    }

    @Test
    fun skippedNightClearsTheStoredOverride() = runTest {
        val store = OnsetOverride(MemoryStore())
        store.set(override)
        val sameNight = plan(Instant.parse("2026-11-12T06:30:00Z"))
        assertEquals(override, store.apply(sameNight, now))
        val skipped = plan(Instant.parse("2026-11-13T06:30:00Z"))
        assertEquals(skipped, store.apply(skipped, now), "übersprungen: der normale Plan der nächsten Nacht")
        assertNull(store.get(), "Ausnahme verworfen")
        // Wird das Überspringen zurückgenommen, gilt die eingestellte Zeit (die Ausnahme kommt nicht zurück).
        assertEquals(sameNight, store.apply(sameNight, now))
    }
}
