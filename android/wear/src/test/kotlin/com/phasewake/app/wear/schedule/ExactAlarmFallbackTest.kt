package com.phasewake.app.wear.schedule

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import com.phasewake.app.core.WakePlan
import java.time.Duration
import java.time.Instant
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Alarme der Uhr ohne Recht für exakte Alarme ([AlarmScheduler]): ungenau statt gar nicht, sonst bräche das Neuplanen
 * ab und Fenster und Zielzeit wären weg. Mit dem Recht als Wecker (zeigt das System an, weckt aus dem Ruhezustand).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExactAlarmFallbackTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val target = Instant.parse("2026-11-03T06:30:00Z")
    private val plan = WakePlan("a", target.minus(Duration.ofMinutes(30)), target)

    @Test
    fun withoutExactPermissionStillSchedules() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        AlarmScheduler.setDeadline(context, plan)
        val alarm = assertNotNull(alarms.peekNextScheduledAlarm())
        assertEquals(target.toEpochMilli(), alarm.triggerAtMs)
        assertNull(alarm.alarmClockInfo, "ungenau, nicht als Wecker")
    }

    @Test
    fun withExactPermissionAsAlarmClock() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        AlarmScheduler.setDeadline(context, plan)
        val alarm = assertNotNull(alarms.peekNextScheduledAlarm())
        assertEquals(target.toEpochMilli(), alarm.triggerAtMs)
        assertNotNull(alarm.alarmClockInfo)
        // Schlummer-Ende und Zielzeit sind eigene Alarme; das Ende der Nacht hebt beide auf.
        AlarmScheduler.setSnoozeEnd(context, plan, target.plus(Duration.ofMinutes(5)))
        assertEquals(2, alarms.scheduledAlarms.size)
        AlarmScheduler.cancelDeadlineAndSnooze(context)
        assertEquals(0, alarms.scheduledAlarms.size)
    }
}
