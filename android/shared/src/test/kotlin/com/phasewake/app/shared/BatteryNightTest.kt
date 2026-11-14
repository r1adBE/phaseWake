package com.phasewake.app.shared

import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.phasewake.app.core.BatteryMark
import com.phasewake.app.core.BatteryTrack
import java.time.Duration
import java.time.Instant
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Akku je Nacht auf dem Gerät: Beginn, Laden dazwischen, Ende, Auftrag fürs Anschliessen, Protokoll. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BatteryNightTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val t0 = Instant.parse("2026-11-13T21:00:00Z")
    private val logged = mutableListOf<String>()

    private fun r(minute: Long, percent: Int, plugged: Boolean = false) = BatteryNight.Reading(t0.plus(Duration.ofMinutes(minute)), percent, plugged)

    private fun pendingJob(): Boolean = context.getSystemService(JobScheduler::class.java).getPendingJob(BatteryNight.JOB_ID) != null

    @After
    fun tearDown() {
        BatteryNight.log = {}
    }

    @Test
    fun nightWithChargingInBetween() {
        BatteryNight.log = { logged += it }
        assertTrue(BatteryNight.begin(context, r(0, 80)))
        assertTrue(pendingJob(), "Auftrag fürs Anschliessen geplant")
        // Zweiter Beginn derselben Nacht (Weckfenster): Zwischenstand, keine neue Nacht.
        assertFalse(BatteryNight.begin(context, r(30, 78)))
        BatteryNight.charged(context, r(120, 70, plugged = true))
        BatteryNight.charged(context, r(121, 70, plugged = true))
        BatteryNight.charged(context, r(240, 95))
        BatteryNight.sample(context, r(500, 92))
        val track = assertNotNull(BatteryNight.end(context, r(600, 90)))
        assertEquals(listOf(BatteryMark.START, BatteryMark.SAMPLE, BatteryMark.PLUG, BatteryMark.UNPLUG, BatteryMark.SAMPLE, BatteryMark.END), track.map { it.mark })
        assertEquals(15, BatteryTrack.used(track))
        assertFalse(pendingJob(), "nach dem Ende kein Auftrag mehr")
        assertEquals("night battery start 80% unplugged", logged.first())
        assertEquals("night battery end 90% unplugged, used 15%", logged.last())
        // Beendet: nichts mehr offen.
        assertNull(BatteryNight.end(context, r(601, 90)))
    }

    @Test
    fun withoutReadingOrNightNothingHappens() {
        assertFalse(BatteryNight.begin(context, null))
        BatteryNight.charged(context, r(1, 50, plugged = true))
        BatteryNight.sample(context, r(2, 50))
        assertNull(BatteryNight.end(context, r(3, 50)))
        // Ende ohne Akkustand: unbekannt, die Nacht ist trotzdem zu.
        BatteryNight.log = { logged += it }
        BatteryNight.begin(context, r(0, 80))
        assertNull(BatteryNight.end(context, null))
        assertEquals("night battery end unknown", logged.last())
        assertNull(BatteryNight.end(context, r(5, 80)))
    }

    @Test
    fun dropForgetsTheNight() {
        BatteryNight.log = { logged += it }
        BatteryNight.begin(context, r(0, 80))
        BatteryNight.drop(context, "test")
        assertEquals("night battery dropped: test", logged.last())
        assertFalse(pendingJob())
        assertNull(BatteryNight.end(context, r(10, 70)))
        // Ohne laufende Nacht: kein Eintrag.
        logged.clear()
        BatteryNight.drop(context, "again")
        assertTrue(logged.isEmpty())
    }

    @Test
    fun doneTrackWaitsForItsNight() {
        BatteryNight.begin(context, r(0, 50))
        val track = assertNotNull(BatteryNight.end(context, r(600, 47)))
        BatteryNight.keepDone(context, track)
        assertNull(BatteryNight.takeDone(context) { false })
        assertEquals(track, BatteryNight.takeDone(context) { true })
        assertNull(BatteryNight.takeDone(context) { true })
    }

    @Test
    @Suppress("DEPRECATION") // sendStickyBroadcast: nur so legt der Test den Akkustand des Systems fest.
    fun readsTheStickyBatteryStatus() {
        assertNull(BatteryNight.read(context))
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 37)
                .putExtra(BatteryManager.EXTRA_SCALE, 50)
                .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB),
        )
        val reading = assertNotNull(BatteryNight.read(context))
        assertEquals(74, reading.percent)
        assertTrue(reading.plugged)
        // Der Auftrag beim Anschliessen hält den Ladebeginn fest (er liest die Uhrzeit selbst, darum um «jetzt» herum).
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        BatteryNight.begin(context, BatteryNight.Reading(now.minusSeconds(60), 80, false))
        Robolectric.buildService(BatteryChargeJob::class.java).create().get().onStartJob(null)
        val track = assertNotNull(BatteryNight.end(context, BatteryNight.Reading(now.plusSeconds(600), 90, true)))
        assertEquals(BatteryMark.PLUG, track[1].mark)
        assertEquals(74, track[1].percent)
    }
}
