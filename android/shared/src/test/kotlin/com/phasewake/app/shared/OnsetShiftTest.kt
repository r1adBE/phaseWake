package com.phasewake.app.shared

import com.google.android.gms.wearable.DataMap
import com.phasewake.app.core.WakePlan
import java.time.Duration
import java.time.Instant
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Frühere Zielzeit aus dem Einschlaf-Modus für den Zweitwecker am Handy ([OnsetShift], Review K-07): Datenpaket der
 * Uhr, eigener Speicher des Handys und nur dieselbe Nacht desselben Weckers. Robolectric für DataMap und Uri.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnsetShiftTest {
    private val configured = Instant.parse("2026-11-03T06:30:00Z")
    private val earlier = configured.minus(Duration.ofMinutes(35))
    private val shift = OnsetShift("a|b", configured.toEpochMilli(), earlier.toEpochMilli(), 1_000L)
    private val plan = WakePlan("a|b", configured.minus(Duration.ofMinutes(30)), configured)
    private val grace = Duration.ofMinutes(2)

    @Test
    fun dataItemAndStoreRoundTrip() {
        // Früheres Paket der Uhr: liest nur noch die einmalige Umstellung.
        val item = DataMap().apply {
            putString(DataLayer.KEY_TEXT, shift.alarmId)
            putLong(DataLayer.KEY_ORIGINAL_TARGET, shift.original)
            putLong(DataLayer.KEY_TARGET, shift.target)
            putLong(DataLayer.KEY_UPDATED_AT, shift.at)
        }
        assertEquals(shift, OnsetShift.read(item))
        assertNull(OnsetShift.read(DataMap()), "ohne Angaben")
        assertNull(OnsetShift.read(DataMap().apply { putString(DataLayer.KEY_TEXT, "a") }), "ohne Zielzeiten")
        assertEquals(shift, OnsetShift.decode(shift.encode()))
        assertNull(OnsetShift.decode(null))
        assertNull(OnsetShift.decode("a|x|1|2"))
    }

    @Test
    fun backupMovesEarlierOnlyForThatNight() {
        val night = Instant.parse("2026-11-03T01:00:00Z")
        val moved = shift.applyTo(plan, night, grace)
        assertEquals(earlier, moved.target)
        assertEquals("a|b", moved.alarmId)
        assertTrue(!moved.windowStart.isAfter(moved.target))
        // Anderer Wecker, andere Nacht oder keine frühere Zeit: unverändert.
        assertSame(plan, shift.copy(alarmId = "c").applyTo(plan, night, grace))
        val tomorrow = plan.copy(target = configured.plus(Duration.ofDays(1)))
        assertSame(tomorrow, shift.applyTo(tomorrow, night, grace))
        assertSame(plan, shift.copy(target = configured.plus(Duration.ofMinutes(5)).toEpochMilli()).applyTo(plan, night, grace))
    }

    @Test
    fun messageWithoutEarlierTargetLeavesThePlan() {
        // Zweites Einschlafen, Zielzeit auf der eingestellten (Review H-1), oder Ausnahme gelöscht ([OnsetShift.cancel]).
        val night = Instant.parse("2026-11-03T01:00:00Z")
        assertSame(plan, shift.copy(target = configured.toEpochMilli(), at = 2_000L).applyTo(plan, night, grace))
        val cancel = OnsetShift.cancel(WakePlan("a|b", earlier.minus(Duration.ofMinutes(30)), earlier), 3_000L)
        assertEquals(OnsetShift("a|b", earlier.toEpochMilli(), earlier.toEpochMilli(), 3_000L), cancel)
        assertSame(plan, cancel.applyTo(plan, night, grace))
        assertTrue(cancel.at > shift.at, "ersetzt die gemerkte")
    }

    @Test
    fun afterEarlierTargetAndGraceTheConfiguredTimeIsTheLateBackup() {
        // In der Nachfrist der früheren Zielzeit (z. B. Prozessstart): sie gilt weiter.
        assertEquals(earlier, shift.applyTo(plan, earlier.plus(Duration.ofMinutes(1)), grace).target)
        // Danach ohne Klingeln (Handy war aus): die eingestellte Zeit als späte Sicherung.
        assertSame(plan, shift.applyTo(plan, earlier.plus(grace), grace))
        // Nach der eingestellten Zeit samt Nachfrist ist die Nacht vorbei: nicht mehr merken.
        assertFalse(shift.expired(configured.plus(Duration.ofMinutes(1)), grace))
        assertTrue(shift.expired(configured.plus(grace), grace))
    }
}
