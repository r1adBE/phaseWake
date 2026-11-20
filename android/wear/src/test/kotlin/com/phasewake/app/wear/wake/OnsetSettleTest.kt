package com.phasewake.app.wear.wake

import com.phasewake.app.core.DetectorConfig
import com.phasewake.app.core.NoiseFloor
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.schedule.AutoOnset
import java.time.Duration
import java.time.Instant
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Einschlaf-Messung an der Zeitgrenze: Ist es ruhig geworden, die Strecke aber noch zu kurz, verlängert sie sich, bis
 * die Strecke lang genug sein kann ([OnsetRules.settling], [OnsetRules.extendUntil]); Fall 20.11.2026.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class OnsetSettleTest {
    /** Beginn wie am 20.11.2026 («app open: from now», 04:05:33 in Zürich). */
    private val started = Instant.parse("2026-11-20T03:05:33Z")
    private val max = started.plus(OnsetMonitor.MAX_DURATION)
    private val cap = started.plus(OnsetMonitor.MAX_EXTENDED)
    private val alarmLimit = Instant.parse("2026-11-20T09:15:00Z")

    /** Stichproben wie in der Messung (ab Epoche 3 alle 10 Epochen 2 gemessene) bis vor Epoche [until]. */
    private fun sampled(until: Int, motion: (Int) -> Double?): Pair<List<Double?>, List<Boolean>> {
        val measured = List(until) { it >= 3 && (it - 3) % 10 < 2 }
        return List(until) { if (measured[it]) motion(it) else null } to measured
    }

    /** Wirksame Leicht-Schwelle mit dem Grundrauschen der gemessenen Epochen (wie [OnsetMonitor]). */
    private fun light(motions: List<Double?>, measured: List<Boolean>): Double =
        NoiseFloor.light(
            DetectorConfig.DEFAULT_LIGHT_THRESHOLD,
            NoiseFloor.floor(motions.filterIndexed { i, _ -> measured[i] }),
        )

    /** Unruhig bis 05:50 (Epoche 210), danach ruhig wie am 20.11. (0.0236/0.0249). */
    private val lateQuiet: (Int) -> Double = { if (it < 210) 0.3 else if (it % 2 == 0) 0.0236 else 0.0249 }

    @Test
    fun quietAtTheMaxDurationIsFoundAfterTheExtension() {
        // An der Höchstdauer (Epoche 240): seit 05:50 ruhig, die Strecke aber erst 21 statt 50 Epochen.
        val (motions, measured) = sampled(240, lateQuiet)
        val light = light(motions, measured)
        assertNull(OnsetRules.find(motions, measured, light))
        assertFalse(OnsetRules.restless(motions, measured, light))
        // Bisher: keine Unruhe in den letzten 15 Min, also aufgegeben.
        assertNull(OnsetRules.extendUntil(max, max, alarmLimit, cap, false, 80))
        // Jetzt: Ruhe seit der Stichprobe in Epoche 213; verlängert bis Epoche 263 plus ein Takt.
        val quiet = OnsetRules.settling(motions, measured, light, continuous = false)
        assertEquals(213, quiet)
        val settleBy = OnsetRules.settleUntil(started, 213, continuous = false)
        assertEquals(OnsetRules.epochAt(started, 263).plus(OnsetMonitor.TICK_INTERVAL), settleBy)
        val until = OnsetRules.extendUntil(max, max, alarmLimit, cap, false, 80, settleBy)
        assertEquals(settleBy, until)
        assertTrue(Duration.between(max, until) <= OnsetRules.SETTLE_MAX)
        // Die Stichproben bis dahin finden das Einschlafen: Beginn der Ruhe (05:52:03), wie die Regel ihn bestimmt.
        val (later, laterMeasured) = sampled(273, lateQuiet)
        val threshold = DetectorConfig.DEFAULT_LIGHT_THRESHOLD
        val (found, _) = OnsetRules.scan(later, laterMeasured, 240, threshold, null, false, null)
        assertEquals(213, found)
        assertEquals(Instant.parse("2026-11-20T04:52:03Z"), OnsetRules.epochAt(started, 213))
    }

    @Test
    fun restlessUntilTheEndExtendsAsBefore() {
        // Unruhig bis zum Ende: keine begonnene Ruhe; Verlängerung wie bisher in Schritten von 15 Min bis zur Grenze.
        val (motions, measured) = sampled(240) { 0.3 }
        val light = light(motions, measured)
        assertTrue(OnsetRules.restless(motions, measured, light))
        assertNull(OnsetRules.settling(motions, measured, light, continuous = false))
        assertEquals(max.plus(OnsetMonitor.EXTEND_STEP), OnsetRules.extendUntil(max, max, alarmLimit, cap, true, 80))
        assertNull(OnsetRules.extendUntil(cap, cap, alarmLimit, cap, true, 80, null))
        // Ruhig, aber die letzte Stichprobe bewegt, leer oder mehr als 20 Epochen zurück: keine begonnene Ruhe.
        val (moved, m1) = sampled(240) { if (it >= 233) 0.3 else lateQuiet(it) }
        assertNull(OnsetRules.settling(moved, m1, light(moved, m1), continuous = false))
        val (empty, m2) = sampled(240) { if (it >= 233) null else lateQuiet(it) }
        assertNull(OnsetRules.settling(empty, m2, light(empty, m2), continuous = false))
        val (gap, m3) = sampled(214, lateQuiet)
        val padded = gap + List(22) { null }
        val paddedMeasured = m3 + List(22) { false }
        assertNull(OnsetRules.settlingSampled(padded, paddedMeasured, light(gap, m3)))
        // Schon lang genug (bestätigt): nichts mehr zu warten.
        val (done, m4) = sampled(273, lateQuiet)
        assertNull(OnsetRules.settlingSampled(done, m4, light(done, m4)))
    }

    @Test
    fun extendUntilBoundsTheWaitForQuiet() {
        val settleBy = max.plus(Duration.ofMinutes(16))
        assertEquals(settleBy, OnsetRules.extendUntil(max, max, alarmLimit, cap, false, 80, settleBy))
        // Höchstens 30 Min, nie über die Grenze (4 Std. bzw. Weckfenster) oder 15 Min vor der Weckzeit hinaus.
        val far = max.plus(Duration.ofMinutes(45))
        assertEquals(max.plus(OnsetRules.SETTLE_MAX), OnsetRules.extendUntil(max, max, alarmLimit, cap, false, 80, far))
        val window = max.plus(Duration.ofMinutes(10))
        assertEquals(window, OnsetRules.extendUntil(max, max, alarmLimit, window, false, 80, settleBy))
        assertNull(OnsetRules.extendUntil(max, max, max.minusSeconds(60), cap, false, 80, settleBy))
        // Vor der Höchstdauer, schon vorbei oder wenig Akku: nicht.
        assertNull(OnsetRules.extendUntil(max.minusSeconds(1), max, alarmLimit, cap, false, 80, settleBy))
        assertNull(OnsetRules.extendUntil(max, max, alarmLimit, cap, false, 80, max))
        val low = AutoOnset.MIN_BATTERY_PERCENT - 1
        assertNull(OnsetRules.extendUntil(max, max, alarmLimit, cap, false, low, settleBy))
        // Unruhe geht vor: Schritt von 15 Min.
        val step = max.plus(OnsetMonitor.EXTEND_STEP)
        assertEquals(step, OnsetRules.extendUntil(max, max, alarmLimit, cap, true, 80, far))
        // Durchgehende Messung: 30 Epochen ab dem Beginn der Ruhe plus ein Takt.
        assertEquals(
            OnsetRules.epochAt(started, 40).plus(OnsetMonitor.TICK_INTERVAL),
            OnsetRules.settleUntil(started, 10, continuous = true),
        )
    }
}
