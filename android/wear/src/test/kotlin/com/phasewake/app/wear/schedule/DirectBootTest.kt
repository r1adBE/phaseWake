package com.phasewake.app.wear.schedule

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.wake.WakeRules
import java.time.Duration
import java.time.Instant
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ablage für vor dem ersten Entsperren ([DirectBoot]): Plan, Plan danach, Schlummer-Ende und Wiederaufnahme; dazu die
 * Alarme ohne Speicher aus [AlarmScheduler] (Zielzeit, Schlummer-Ende bzw. Wiederaufnahme beim Klingeln, Aufheben).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DirectBootTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    /** Auf Millisekunden gerundet wie im Speicher. */
    private val now = Instant.ofEpochMilli(System.currentTimeMillis())
    private val target = now.plus(Duration.ofHours(8))
    private val plan = WakePlan("a", target.minus(Duration.ofMinutes(30)), target)
    private val next = WakePlan("b", target.plus(Duration.ofHours(23)), target.plus(Duration.ofHours(24)), exact = true)

    @Before
    fun clean() = DirectBoot.clear(context)

    @Test
    fun savesPlanWithSoundAndLabel() {
        DirectBoot.save(context, plan, AlarmSpec(id = "a", hour = 6, minute = 30, watchSound = true, label = "Früh"), next, null)
        assertEquals(DirectBoot.Saved(plan, target, true, "Früh"), DirectBoot.load(context))
        DirectBoot.clear(context)
        assertNull(DirectBoot.load(context))
    }

    @Test
    fun promoteNextAfterNightBeforeUnlock() {
        DirectBoot.save(context, plan, null, next, AlarmSpec(id = "b", hour = 6, minute = 30, label = "Zwei"))
        assertEquals(DirectBoot.Saved(next, next.target, false, "Zwei"), DirectBoot.promoteNext(context))
        assertEquals(next, DirectBoot.load(context)?.plan)
        // Kein Plan danach mehr: alles gelöscht.
        assertNull(DirectBoot.promoteNext(context))
        assertNull(DirectBoot.load(context))
    }

    @Test
    fun ringAtKeepsSoundAndLabel() {
        DirectBoot.save(context, plan, AlarmSpec(id = "a", hour = 6, minute = 30, watchSound = true, label = "Früh"), null, null)
        val at = target.plus(Duration.ofMinutes(10))
        DirectBoot.saveRingAt(context, plan, at)
        assertEquals(DirectBoot.Saved(plan, at, true, "Früh"), DirectBoot.load(context))
    }

    @Test
    fun restoreSetsAlarmAtTarget() {
        DirectBoot.save(context, plan, null, next, null)
        DirectBoot.restore(context)
        assertEquals(listOf(target.toEpochMilli()), alarms.scheduledAlarms.map { it.triggerAtMs })
    }

    @Test
    fun restoreLongPastPromotesNext() {
        val past = now.minus(Duration.ofHours(5))
        DirectBoot.save(context, WakePlan("a", past.minus(Duration.ofMinutes(30)), past), null, next, null)
        DirectBoot.restore(context)
        assertEquals(next, DirectBoot.load(context)?.plan)
        assertEquals(listOf(next.target.toEpochMilli()), alarms.scheduledAlarms.map { it.triggerAtMs })
    }

    @Test
    fun catchesUpOnlyShortlyAfterBoot() {
        val saved = DirectBoot.Saved(plan, target, false, "")
        assertTrue(DirectBoot.catchesUp(saved, target.plus(Duration.ofMinutes(5))))
        assertFalse(DirectBoot.catchesUp(saved, target.plus(Duration.ofHours(3))))
        // Probewecker: nur 60 s.
        val test = saved.copy(plan = plan.copy(alarmId = "test-1"))
        assertFalse(DirectBoot.catchesUp(test, target.plus(Duration.ofMinutes(5))))
    }

    @Test
    fun resumeAlarmWhileRingingAndCancel() {
        // Wie beim Klingeln (RingFlow.armResume): Alarm kurz nach der Zeitgrenze, auch für einen Neustart abgelegt.
        val resume = WakeRules.resumeAt(now)
        assertEquals(now.plus(Duration.ofMinutes(10)).plusSeconds(30), resume)
        AlarmScheduler.setSnoozeEnd(context, plan, resume)
        DirectBoot.saveRingAt(context, plan, resume)
        assertEquals(listOf(resume.toEpochMilli()), alarms.scheduledAlarms.map { it.triggerAtMs })
        assertEquals(resume, DirectBoot.load(context)?.at)
        // Schlummern ersetzt ihn (gleicher Alarm).
        val snoozeEnd = resume.plus(Duration.ofMinutes(2))
        AlarmScheduler.setSnoozeEnd(context, plan, snoozeEnd)
        assertEquals(listOf(snoozeEnd.toEpochMilli()), alarms.scheduledAlarms.map { it.triggerAtMs })
        // Ende der Nacht: Zielzeit und Schlummer-Ende weg.
        AlarmScheduler.setDeadline(context, plan)
        AlarmScheduler.cancelDeadlineAndSnooze(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }
}
