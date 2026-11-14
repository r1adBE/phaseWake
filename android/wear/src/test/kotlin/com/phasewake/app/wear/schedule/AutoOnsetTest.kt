package com.phasewake.app.wear.schedule

import android.app.Application
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.WakePlan
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Beginn der automatischen Einschlaf-Messung ([AutoOnset.startAt]) und Einschlafzeit der kommenden Nacht ([OnsetStore]).
 * Robolectric, weil die Grenzen beim Weckdienst liegen; schlichte [Application], damit der Start der App nichts plant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AutoOnsetTest {
    private val target = Instant.parse("2026-11-03T06:30:00Z")
    private val plan = WakePlan("a", target.minus(Duration.ofMinutes(30)), target)
    private val spec = AlarmSpec(id = "a", hour = 6, minute = 30)

    /** Schlafziel 7½ Std.: Bettzeit 23:00, Messung ab 22:00. */
    private val goal = 450
    private val start = Instant.parse("2026-11-02T22:00:00Z")

    @Test
    fun startsOneHourBeforeBedtime() {
        assertEquals(start, AutoOnset.startAt(plan, spec, goal, Instant.parse("2026-11-02T20:00:00Z"), done = false))
        // Einschlaf-Modus: die Dauer des Weckers statt des Schlafziels.
        val withDuration = spec.copy(sleepDurationMinutes = 480)
        assertEquals(Instant.parse("2026-11-02T21:30:00Z"), AutoOnset.startAt(plan, withDuration, goal, Instant.parse("2026-11-02T20:00:00Z"), done = false))
    }

    @Test
    fun bedtimePassedStartsRightAway() {
        val now = Instant.parse("2026-11-02T23:30:00Z")
        assertEquals(now.plusSeconds(1), AutoOnset.startAt(plan, spec, goal, now, done = false))
    }

    @Test
    fun noStartWhenANightEndedSinceItsStart() {
        // Audit G-2: zweiter Wecker am selben Morgen; der erste hat um 05:00 geweckt, Beginn wäre 22:00 gewesen.
        val now = Instant.parse("2026-11-03T05:01:00Z")
        assertNull(AutoOnset.startAt(plan, spec, goal, now, done = false, lastNightEnded = Instant.parse("2026-11-03T05:00:00Z")))
        // Grenze: genau zum Beginn geendet zählt schon, 1 ms davor nicht.
        val evening = Instant.parse("2026-11-02T20:00:00Z")
        assertNull(AutoOnset.startAt(plan, spec, goal, evening, done = false, lastNightEnded = start))
        assertEquals(start, AutoOnset.startAt(plan, spec, goal, evening, done = false, lastNightEnded = start.minusMillis(1)))
        // Die Nacht davor (gestern Morgen geweckt) hindert die heutige Messung nicht.
        assertEquals(start, AutoOnset.startAt(plan, spec, goal, evening, done = false, lastNightEnded = Instant.parse("2026-11-02T06:30:00Z")))
    }

    @Test
    fun noStartWithoutStoredAlarmOrWhenDone() {
        val now = Instant.parse("2026-11-02T20:00:00Z")
        assertNull(AutoOnset.startAt(null, spec, goal, now, done = false))
        assertNull(AutoOnset.startAt(plan, null, goal, now, done = false))
        assertNull(AutoOnset.startAt(plan, spec.copy(id = "b"), goal, now, done = false))
        assertNull(AutoOnset.startAt(plan, spec, goal, now, done = true))
        // Powernap und Probewecker messen das Einschlafen nicht automatisch.
        val nap = WakePlan("nap-1", plan.windowStart, target)
        assertNull(AutoOnset.startAt(nap, spec.copy(id = "nap-1"), goal, now, done = false))
    }

    @Test
    fun noStartInsideWindowOrShortlyBeforeTarget() {
        // Im Weckfenster: dort misst schon der Weckdienst.
        assertNull(AutoOnset.startAt(plan, spec, goal, Instant.parse("2026-11-03T06:05:00Z"), done = false))
        // Fenster kürzer als 15 Min: 15 Min vor der Zielzeit ist Schluss.
        val narrow = WakePlan("a", target.minus(Duration.ofMinutes(5)), target)
        assertNull(AutoOnset.startAt(narrow, spec, goal, Instant.parse("2026-11-03T06:16:00Z"), done = false))
    }

    @Test
    fun onsetStoreKeepsOnlyThisNight() = runTest {
        val store = OnsetStore(MemoryStore())
        val onset = Instant.parse("2026-11-02T23:10:00Z")
        assertNull(store.onsetFor(plan))
        assertFalse(store.isDone("a", target))
        store.set("a", target, onset)
        assertTrue(store.isDone("a", target))
        assertEquals(onset, store.onsetFor(plan))
        // Einschlaf-Modus: Die Zielzeit liegt früher als die eingestellte Weckzeit, die Einschlafzeit gilt weiter.
        assertEquals(onset, store.onsetFor(plan.copy(target = target.minus(Duration.ofMinutes(20)))))
        // Anderer Wecker oder spätere Nacht: nicht diese.
        assertNull(store.onsetFor(plan.copy(alarmId = "b")))
        assertNull(store.onsetFor(plan.copy(target = target.plus(Duration.ofDays(1)))))
        // Messung ohne Einschlafen vorbei: erledigt, aber keine Einschlafzeit.
        store.set("a", target, null)
        assertTrue(store.isDone("a", target))
        assertNull(store.onsetFor(plan))
    }

    @Test
    fun measuredWithoutOnsetIsKeptForThisNightOnly() = runTest {
        // Fail-closed (Entscheid r1AD 10.11.2026): gemessen, aber ohne Einschlafzeit.
        val store = OnsetStore(MemoryStore())
        assertFalse(store.measuredFor(plan))
        store.set("a", target, null, measured = true)
        assertTrue(store.measuredFor(plan))
        assertNull(store.onsetFor(plan))
        assertFalse(store.measuredFor(plan.copy(alarmId = "b")))
        assertFalse(store.measuredFor(plan.copy(target = target.plus(Duration.ofDays(1)))))
        // Abgebrochen oder nicht begonnen: ohne Untergrenze; mit Einschlafzeit gilt diese.
        store.set("a", target, null)
        assertFalse(store.measuredFor(plan))
        store.set("a", target, Instant.parse("2026-11-02T23:10:00Z"), measured = true)
        assertFalse(store.measuredFor(plan))
    }

    @Test
    fun onsetTooLongBeforeTargetIsIgnored() = runTest {
        val store = OnsetStore(MemoryStore())
        store.set("a", target, target.minus(Duration.ofHours(17)))
        assertNull(store.onsetFor(plan))
        // Nach der Zielzeit eingeschlafen: keine Einschlafzeit für diese Nacht.
        store.set("a", target, target.plusSeconds(60))
        assertNull(store.onsetFor(plan))
    }
}
