package com.phasewake.app.wear.ui

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * «Halten zum Stoppen» und der 5-s-Notausgang bei der Weckaufgabe messen an der Uhr (Audit UI-1): Mit «Animationen
 * entfernen» lief die Füllung sofort durch, und ein Tipp beendete den Wecker. Die Zeit ist hier eine Testuhr.
 */
class HoldTimerTest {

    /** Testuhr: [pause] rückt sie vor; [releaseAt] = Loslassen (bricht ab wie das Abbrechen der Coroutine). */
    private class FakeClock(private val releaseAt: Long? = null) {
        var now = 1_000L
        val progress = mutableListOf<Float>()
        val start = now

        suspend fun pause(ms: Long) {
            now += ms
            if (releaseAt != null && now - start >= releaseAt) throw CancellationException("losgelassen")
        }
    }

    private fun hold(clock: FakeClock, millis: Int = HOLD_MILLIS): Boolean = runBlocking {
        try {
            awaitHold(millis, onProgress = { clock.progress += it }, now = { clock.now }, pause = clock::pause)
            true
        } catch (_: CancellationException) {
            false
        }
    }

    @Test
    fun tapDoesNotStop() {
        // Ein Tipp (100 ms): keine Rückkehr, also kein Stopp – auch ohne Animationen.
        val clock = FakeClock(releaseAt = 100)
        assertFalse(hold(clock))
        assertTrue(clock.progress.last() < 0.1f)
    }

    @Test
    fun releaseJustBeforeEndDoesNotStop() {
        assertFalse(hold(FakeClock(releaseAt = HOLD_MILLIS - 1L)))
    }

    @Test
    fun fullHoldStopsAfterHoldMillis() {
        val clock = FakeClock()
        assertTrue(hold(clock))
        assertEquals(HOLD_MILLIS.toLong(), clock.now - clock.start)
        assertEquals(1f, clock.progress.last())
        // Fortschritt nur steigend, beginnt bei 0.
        assertEquals(0f, clock.progress.first())
        assertEquals(clock.progress.sorted(), clock.progress)
    }

    @Test
    fun emergencyExitNeedsFiveSeconds() {
        assertFalse(hold(FakeClock(releaseAt = 4_999), EMERGENCY_HOLD_MILLIS))
        assertTrue(hold(FakeClock(), EMERGENCY_HOLD_MILLIS))
    }

    @Test
    fun progressFollowsWallClockNotFrames() {
        // Lange Pausen (z. B. ruckelndes Display): Stopp trotzdem genau nach der Haltezeit, nicht früher.
        val clock = FakeClock()
        runBlocking {
            awaitHold(1000, onProgress = { clock.progress += it }, now = { clock.now }, pause = { clock.now += 400 })
        }
        assertEquals(1200L, clock.now - clock.start)
        assertEquals(listOf(0f, 0.4f, 0.8f, 1f), clock.progress)
    }
}
