package com.phasewake.app.ring

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import java.time.Duration
import java.time.Instant
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Schlummern am Handy im geräteverschlüsselten Speicher ([PhoneSnooze]): übersteht Prozessende und Neustart und wird
 * neu gestellt. Schlichte [Application], damit der Start der App (Kanäle, Neuplanen) den Test nicht berührt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneSnoozeTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Test
    fun savedSnoozeSurvivesAndIsCleared() {
        val at = Instant.parse("2026-10-28T06:05:00Z")
        PhoneSnooze.save(context, PhoneSnooze.Saved(at, "a", 2))
        assertEquals(PhoneSnooze.Saved(at, "a", 2), PhoneSnooze.load(context))
        PhoneSnooze.clear(context)
        assertNull(PhoneSnooze.load(context))
    }

    @Test
    fun restoreSetsAlarmAgain() {
        val at = Instant.parse("2026-10-28T06:05:00Z")
        PhoneSnooze.save(context, PhoneSnooze.Saved(at, "a", 1))
        // Neustart vor dem Schlummer-Ende: der Alarm steht wieder, das Abgelegte bleibt.
        PhoneSnooze.restore(context, now = at.minus(Duration.ofMinutes(2)))
        assertTrue(alarms.scheduledAlarms.isNotEmpty())
        assertEquals("a", PhoneSnooze.load(context)?.alarmId)
    }

    @Test
    fun missedDuringBootIsCaughtUpOnlyBriefly() {
        val at = Instant.parse("2026-10-28T06:05:00Z")
        PhoneSnooze.save(context, PhoneSnooze.Saved(at, "a", 1))
        // Während des Neustarts verpasst: gleich nachholen.
        PhoneSnooze.restore(context, now = at.plus(Duration.ofMinutes(5)))
        assertTrue(alarms.scheduledAlarms.isNotEmpty())
        // Stunden später nicht mehr: aufräumen, kein Alarm.
        val other = Instant.parse("2026-10-29T06:05:00Z")
        PhoneSnooze.save(context, PhoneSnooze.Saved(other, "b", 1))
        PhoneSnooze.restore(context, now = other.plus(Duration.ofHours(3)))
        assertNull(PhoneSnooze.load(context))
    }
}
