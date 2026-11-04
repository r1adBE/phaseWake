package com.phasewake.app.wear.schedule

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.wake.WakeService
import java.time.Duration
import java.time.Instant
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Empfänger der Uhr ([AlarmReceiver], [SystemEventReceiver], Audit M-06): Fensterbeginn, Zielzeit und Schlummer-Ende
 * gehen mit ihrem Plan an den Weckdienst; der Prüf-Alarm von «Gute Nacht» und die automatische Einschlaf-Messung nur
 * nach dem Entsperren; vor dem Entsperren stellt ein Neustart nur den abgelegten Wecker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class ScheduleReceiversTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val users get() = shadowOf(context.getSystemService(UserManager::class.java))
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    /** Auf Millisekunden gerundet wie im Intent. */
    private val now = Instant.ofEpochMilli(System.currentTimeMillis())
    private val plan = WakePlan("a", now.plus(Duration.ofHours(7)), now.plus(Duration.ofHours(8)))

    @Before
    fun clean() = DirectBoot.clear(context)

    @After
    fun unlock() = users.setUserUnlocked(true)

    private fun receive(action: String, withPlan: Boolean = true, extra: (Intent) -> Unit = {}) {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(action)
        if (withPlan) AlarmReceiver.putPlan(intent, plan)
        extra(intent)
        AlarmReceiver().onReceive(context, intent)
    }

    private fun started(): Intent? = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService

    @Test
    fun alarmsGoToTheWakeServiceWithTheirPlan() {
        receive(AlarmReceiver.ACTION_WINDOW_START)
        val start = assertNotNull(started())
        assertEquals(WakeService.ACTION_START, start.action)
        assertEquals(plan, AlarmReceiver.planFrom(start))
        receive(AlarmReceiver.ACTION_DEADLINE)
        assertEquals(WakeService.ACTION_DEADLINE, started()?.action)
        receive(AlarmReceiver.ACTION_SNOOZE_END)
        assertEquals(WakeService.ACTION_SNOOZE_END, started()?.action)
        // Ohne Plan (veralteter Alarm) nichts.
        receive(AlarmReceiver.ACTION_DEADLINE, withPlan = false)
        assertNull(started())
    }

    @Test
    fun goodNightAndAutoOnsetOnlyAfterUnlock() {
        users.setUserUnlocked(false)
        receive(AlarmReceiver.ACTION_GOOD_NIGHT_CHECK, withPlan = false)
        receive(AutoOnset.ACTION, withPlan = false) { it.putExtra("autoOnsetAlarmId", "a") }
        assertNull(started())
        users.setUserUnlocked(true)
        receive(AlarmReceiver.ACTION_GOOD_NIGHT_CHECK, withPlan = false)
        assertEquals("com.phasewake.app.wake.GOOD_NIGHT_RESUME", started()?.action)
        receive(AutoOnset.ACTION, withPlan = false) { it.putExtra("autoOnsetAlarmId", "a") }
        assertEquals("com.phasewake.app.wake.AUTO_ONSET", started()?.action)
    }

    @Test
    fun bootBeforeUnlockRestoresOnlyTheSavedAlarm() {
        users.setUserUnlocked(false)
        DirectBoot.save(context, plan, null, null, null)
        SystemEventReceiver().onReceive(context, Intent("com.phasewake.app.action.UNKNOWN"))
        assertTrue(alarms.scheduledAlarms.isEmpty(), "fremde Aktion: nichts")
        SystemEventReceiver().onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtMs == plan.target.toEpochMilli() })
        assertNull(started())
    }
}
