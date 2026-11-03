package com.phasewake.app.ring

import android.content.Context
import android.content.Intent
import android.os.Looper
import com.phasewake.app.QuietApp
import com.phasewake.app.core.Snooze
import java.time.Duration
import java.time.Instant
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ablauf im [PhoneRingService] mit fester Uhrzeit (Review M-2): Beim Klingeln steht der Wiederaufnahme-Alarm
 * ([PhoneSnooze]), ein zweiter Wecker löst ab und stellt ihn für sich neu, die Zeitgrenze schlummert, solange Runden
 * bleiben. Android 13: dort fragt der Weckbildschirm das Vollbild-Recht über die Berechtigung ab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = QuietApp::class)
class PhoneRingServiceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-11-03T05:40:00Z")
    private var controller: ServiceController<PhoneRingService>? = null

    @Before
    fun fixedClock() {
        PhoneRingService.clock = { now.toEpochMilli() }
        PhoneSnooze.clear(context)
    }

    @After
    fun cleanUp() {
        // Zustand liegt im Begleitobjekt: beenden, damit der nächste Test still beginnt.
        controller?.destroy()
        PhoneRingService.clock = System::currentTimeMillis
    }

    private fun ringIntent(alarmId: String, target: Instant) = Intent(context, PhoneRingService::class.java)
        .setAction(PhoneRingService.ACTION_RING)
        .putExtra(PhoneRingService.EXTRA_ALARM_ID, alarmId)
        .putExtra(PhoneRingService.EXTRA_TARGET, target.toEpochMilli())

    @Test
    fun ringArmsResumeTakeOverRearmsTimeoutSnoozes() {
        val resumeAt = Instant.ofEpochMilli(now.toEpochMilli() + RingRules.TIMEOUT_MS + RingRules.RESUME_MARGIN_MS)
        // Zweitwecker «a» klingelt (das Handy führt): alle Runden, Wiederaufnahme-Alarm steht.
        val service = Robolectric.buildService(PhoneRingService::class.java, ringIntent("a", now)).create().startCommand(0, 1)
        controller = service
        assertEquals(PhoneRingService.State.Ringing(fromWatch = false, snoozesLeft = Snooze.MAX_COUNT), plain())
        assertEquals(PhoneSnooze.Saved(resumeAt, "a", 0), PhoneSnooze.load(context))
        // Zweiter Wecker «b» löst ab: aufgehoben und für «b» neu gestellt, wieder alle Runden.
        // Zweiter Start desselben Dienstes mit neuem Auftrag (ohne das veraltete `withIntent`, Review K-14).
        service.get().onStartCommand(ringIntent("b", now.plus(Duration.ofMinutes(30))), 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(PhoneRingService.State.Ringing(fromWatch = false, snoozesLeft = Snooze.MAX_COUNT), plain())
        assertEquals(PhoneSnooze.Saved(resumeAt, "b", 0), PhoneSnooze.load(context))
        // Niemand reagiert: Zeitgrenze → selbst schlummern, Schlummer-Ende ersetzt die Wiederaufnahme.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RingRules.TIMEOUT_MS + 1_000))
        assertEquals(PhoneRingService.State.Snoozed::class, PhoneRingService.ringState.value::class)
        assertEquals(Snooze.MAX_COUNT - 1, (PhoneRingService.ringState.value as PhoneRingService.State.Snoozed).snoozesLeft)
        assertEquals(PhoneSnooze.Saved(Snooze.nextRing(now), "b", 1), PhoneSnooze.load(context))
    }

    /** Klingelzustand ohne die Angaben, die der Weckbildschirm später ergänzt (Licht, Aufgabe). */
    private fun plain(): PhoneRingService.State {
        val state = PhoneRingService.ringState.value
        return if (state is PhoneRingService.State.Ringing) PhoneRingService.State.Ringing(state.fromWatch, state.snoozesLeft) else state
    }
}
