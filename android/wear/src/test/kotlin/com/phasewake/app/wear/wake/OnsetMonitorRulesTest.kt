package com.phasewake.app.wear.wake

import com.phasewake.app.core.SleepOnset
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
 * Einschlaf-Messung, reine Regeln ([OnsetRules]) ohne laufende Messung: Stichproben, Takt der Alarme, Ladegerät,
 * Diagnose-Zeile, Zeitgrenze und Verlängerung. Die Messung selbst prüft [OnsetMonitorTest] (aus ihr ausgelagert,
 * Zeilengrenze).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class OnsetMonitorRulesTest {
    private val clock = Instant.parse("2026-11-03T21:00:00Z")

    // --- Regel in Stichproben ---

    @Test
    fun allMeasuredNeedsTheLongerSampledStretch() {
        // Audit c3b8b01 E-4: Stichproben verlangen 25 Min (30 + 20 Epochen) statt 15; auch wenn alle Epochen gemessen sind.
        val still = List(50) { 0.001 }
        assertEquals(0, OnsetRules.find(still, List(50) { true }, 0.002))
        assertNull(OnsetRules.find(still.take(49), List(49) { true }, 0.002))
        assertEquals(0, SleepOnset.find(still.take(30), 0.002))
    }

    /** Stichproben wie in der Messung: alle 10 Epochen 2 gemessene, dazwischen ungemessen (leer). */
    private fun sampled(quiet: (Int) -> Double?, samples: Int, offset: Int = 3): Pair<List<Double?>, List<Boolean>> {
        val size = offset + samples * 10
        val measured = List(size) { it >= offset && (it - offset) % 10 < 2 }
        return List(size) { if (measured[it]) quiet(it) else null } to measured
    }

    @Test
    fun samplesSpanningTwentyFiveMinutesAreAsleep() {
        // Sechs ruhige Stichproben: von der ersten (Epoche 3) bis zur letzten (Epoche 54) über 50 Epochen (E-4).
        val (motions, measured) = sampled({ 0.001 }, samples = 6)
        assertEquals(3, OnsetRules.find(motions, measured, 0.002))
        // Fünf reichen nicht mehr (nur 42 Epochen; früher genügten vier über 30).
        val (five, measuredFive) = sampled({ 0.001 }, samples = 5)
        assertNull(OnsetRules.find(five, measuredFive, 0.002))
    }

    @Test
    fun oneSingleTwitchIsTolerated() {
        // E-4: Ein Zucker in einer Epoche der zweiten Stichprobe (Epoche 14, Epoche 13 ruhig) unterbricht nicht.
        val (twitch, m1) = sampled({ if (it == 14) 0.01 else 0.001 }, samples = 6)
        assertEquals(3, OnsetRules.find(twitch, m1, 0.002))
        // Ein zweiter Zucker (Epoche 34) beginnt von vorn: ab der Stichprobe nach dem ersten (Epoche 23).
        val (two, m2) = sampled({ if (it == 14 || it == 34) 0.01 else 0.001 }, samples = 9)
        assertEquals(23, OnsetRules.find(two, m2, 0.002))
    }

    @Test
    fun movementEmptyEpochOrLongGapStartsOver() {
        // Bewegung in beiden Epochen der zweiten Stichprobe: Strecke beginnt bei der dritten (Epoche 23).
        val (moved, m1) = sampled({ if (it == 13 || it == 14) 0.01 else 0.001 }, samples = 8)
        assertEquals(23, OnsetRules.find(moved, m1, 0.002))
        // Gemessen, aber leer (zu wenig Werte): ebenso.
        val (empty, m2) = sampled({ if (it == 14) null else 0.001 }, samples = 8)
        assertEquals(23, OnsetRules.find(empty, m2, 0.002))
        // Zwei verpasste Alarme (Lücke 28 Epochen): kein Einschlafen über die Lücke hinweg.
        val measured = List(80) { it in 0..1 || it in 30..31 || it in 40..41 || it in 50..51 }
        val motions = List(80) { if (measured[it]) 0.001 else null }
        assertNull(OnsetRules.find(motions, measured, 0.002))
    }

    // --- Takt ---

    @Test
    fun ticksLeadAnEpochBoundaryAtLeastFiveMinutesAhead() {
        val start = 1_000_000_000L
        val epoch = OnsetMonitor.EPOCH_NANOS
        val lead = OnsetMonitor.SAMPLE_LEAD_MS * 1_000_000L
        for (now in listOf(start, start + 1, start + epoch / 2, start + 7 * epoch + 123)) {
            val tick = OnsetRules.nextTick(start, now)
            assertTrue(tick >= now + OnsetMonitor.TICK_INTERVAL.toNanos() - epoch / 2 - lead, "5 Min, höchstens eine halbe Epoche früher")
            assertTrue(tick <= now + OnsetMonitor.TICK_INTERVAL.toNanos() + epoch / 2, "höchstens eine halbe Epoche später")
            assertEquals(0L, (tick + lead - start) % epoch, "kurz vor einer Epochengrenze")
        }
        // Erster Alarm nach dem Start: Stichprobe ab Epoche 10, nicht 11.
        assertEquals(start + 10 * epoch - lead, OnsetRules.nextTick(start, start + 1_000_000L))
        assertEquals(0L, OnsetRules.firstFullEpoch(start, start))
        assertEquals(1L, OnsetRules.firstFullEpoch(start, start + 1))
        assertEquals(1L, OnsetRules.firstFullEpoch(start, start + epoch))
    }

    @Test
    fun lateAlarmsKeepTenEpochsBetweenSamples() {
        // Audit c3b8b01 E-3: Jeder Alarm kommt etwas zu spät (0 bis 1,9 s): Die Stichproben liegen trotzdem genau
        // 10 Epochen auseinander (früher 11, also alle 5½ Min, und 3 Std. ergaben 65 statt 72 gemessene Epochen).
        val start = 1_000_000_000L
        val epoch = OnsetMonitor.EPOCH_NANOS
        var now = start + 3_000_000L
        val samples = mutableListOf<Long>()
        repeat(36) { i ->
            val tick = OnsetRules.nextTick(start, now)
            now = tick + (i % 20) * 100_000_000L
            samples += OnsetRules.firstFullEpoch(start, now)
        }
        assertEquals((1..36).map { it * 10L }, samples)
        assertEquals(OnsetRules.TICK_EPOCHS, 10L)
    }

    @Test
    fun epochsOnTheChargerAreEmpty() {
        // Audit c3b8b01 E-5: Netzteil an bei 95 s, ab bei 200 s (Beginn 1 s): Epochen 3 bis 6 berühren die Zeit am Ladegerät.
        val start = 1_000_000_000L
        val second = 1_000_000_000L
        val times: List<Pair<Long, Long?>> = listOf(Pair(start + 95 * second, start + 200 * second))
        assertEquals((0L..8L).map { it in 3L..6L }, (0L..8L).map { OnsetRules.charging(it, start, times) })
        // Noch angeschlossen: alle Epochen ab dem Anschliessen.
        assertTrue(OnsetRules.charging(1_000L, start, listOf(start + 95 * second to null)))
        assertFalse(OnsetRules.charging(2L, start, listOf(start + 95 * second to null)))
        // Leer heisst: Die ruhige Strecke beginnt erst nach dem Ladegerät (wie eine leere Epoche).
        val motions = List(60) { if (it in 3..6) null else 0.001 }
        assertEquals(7, SleepOnset.find(motions, 0.002))
    }

    @Test
    fun statLineIsCompact() {
        val line = OnsetRules.statLine("auto onset", false, 1500, 6, 1, 0.0234, 0.031, 0.05, 0.02, 186, 43)
        assertEquals("auto onset stat wake=0 samples=1500 epochs=6 empty=1 motion=0.0234/0.0310 thr=0.0500 floor=0.0200 wl=186s bat=43", line)
        assertEquals(
            "onset stat wake=1 samples=0 epochs=0 empty=0 motion=-/- thr=0.0020 floor=- wl=0s bat=-",
            OnsetRules.statLine("onset", true, 0, 0, 0, null, null, 0.002, null, 0, null),
        )
    }

    // --- Zeitgrenze ---

    @Test
    fun giveUpReasonNamesTheMaxDuration() {
        val latest = clock.plus(Duration.ofHours(8))
        val max = clock.plus(OnsetMonitor.MAX_DURATION)
        assertNull(OnsetRules.giveUpReason(max.minusSeconds(1), latest, max))
        assertEquals("give up (max duration)", OnsetRules.giveUpReason(max, latest, max))
        // Weckzeit näher als 2 Std.: gewöhnliches Aufgeben 15 Min vor ihr.
        val near = clock.plus(Duration.ofMinutes(90))
        assertEquals("give up", OnsetRules.giveUpReason(near, near, max))
    }

    // --- Verlängerung bei spätem Einschlafen (Befund 09.11.2026) ---

    @Test
    fun restlessLooksAtTheLastFifteenMinutesOnly() {
        val quiet = List(40) { 0.001 }
        assertFalse(OnsetRules.restless(quiet, List(40) { true }, 0.1))
        // Bewegung vor 31 Epochen zählt nicht mehr, vor 30 schon.
        assertFalse(OnsetRules.restless(quiet.mapIndexed { i, m -> if (i == 9) 0.2 else m }, List(40) { true }, 0.1))
        assertTrue(OnsetRules.restless(quiet.mapIndexed { i, m -> if (i == 10) 0.2 else m }, List(40) { true }, 0.1))
        // Ungemessen oder leer: nicht unruhig.
        assertFalse(OnsetRules.restless(quiet.mapIndexed { i, m -> if (i == 39) 0.2 else m }, List(40) { it != 39 }, 0.1))
        assertFalse(OnsetRules.restless(quiet.mapIndexed { i, m -> if (i == 39) null else m }, List(40) { true }, 0.1))
    }

    @Test
    fun extendUntilFollowsUnrestCapAlarmAndBattery() {
        val start = clock
        val max = start.plus(OnsetMonitor.MAX_DURATION)
        val alarmLimit = start.plus(Duration.ofHours(6))
        val cap = start.plus(OnsetMonitor.MAX_EXTENDED)
        val at = max.plusSeconds(10)
        assertEquals(at.plus(OnsetMonitor.EXTEND_STEP), OnsetRules.extendUntil(at, max, alarmLimit, cap, true, 80))
        // Vor der Höchstdauer, ruhig oder wenig Akku: keine Verlängerung; unbekannter Akku hindert nicht.
        assertNull(OnsetRules.extendUntil(max.minusSeconds(1), max, alarmLimit, cap, true, 80))
        assertNull(OnsetRules.extendUntil(at, max, alarmLimit, cap, false, 80))
        assertNull(OnsetRules.extendUntil(at, max, alarmLimit, cap, true, AutoOnset.MIN_BATTERY_PERCENT - 1))
        assertEquals(at.plus(OnsetMonitor.EXTEND_STEP), OnsetRules.extendUntil(at, max, alarmLimit, cap, true, null))
        // Höchstens bis zur Grenze (4 Std. bzw. Beginn des Weckfensters), danach aufgeben.
        assertEquals(cap, OnsetRules.extendUntil(cap.minusSeconds(60), cap.minusSeconds(60), alarmLimit, cap, true, 80))
        assertNull(OnsetRules.extendUntil(cap, cap, alarmLimit, cap, true, 80))
        // Grenze vor der Weckzeit erreicht (nicht die Höchstdauer): wie bisher aufgeben.
        assertNull(OnsetRules.extendUntil(at, max, max.minusSeconds(60), cap, true, 80))
    }
}
