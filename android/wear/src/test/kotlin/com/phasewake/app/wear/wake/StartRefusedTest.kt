package com.phasewake.app.wear.wake

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.schedule.AlarmReceiver
import com.phasewake.app.wear.ui.RingActivity
import java.time.Duration
import java.time.Instant
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verweigerter Vordergrund-Start des Weckdienstes (Audit N-05): Zur Zielzeit und am Ende des Schlummerns erscheint die
 * Weck-Mitteilung mit Vollbild direkt; beim Fensterbeginn und bei «Gute Nacht» bleibt es bei der Diagnose. Der
 * Weckbildschirm holt den Dienst über die Mitteilung nach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class StartRefusedTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val now = Instant.ofEpochMilli(System.currentTimeMillis())
    private val plan = WakePlan("a", now.minus(Duration.ofMinutes(30)), now)

    /** Kontext, der jeden Vordergrund-Start ablehnt wie Android 12+ aus dem Hintergrund. */
    private class Refusing(base: Context) : ContextWrapper(base) {
        var attempts = 0
        override fun startForegroundService(service: Intent): ComponentName? {
            attempts++
            throw ForegroundServiceStartNotAllowedException("test")
        }
    }

    @After
    fun cleanUp() = app.getSystemService(NotificationManager::class.java).cancelAll()

    @Test
    fun refusedDeadlinePostsRingNotificationWithFullScreen() {
        val context = Refusing(app)
        WakeService.command(context, WakeService.ACTION_DEADLINE, plan)
        assertEquals(1, context.attempts)
        val posted = manager.getNotification(Notifications.ID_RING)
        assertNotNull(posted)
        assertNotNull(posted.fullScreenIntent)
        assertEquals(Notifications.CHANNEL_ALARM, posted.channelId)
        val screen = shadowOf(posted.fullScreenIntent).savedIntent
        assertEquals(RingActivity.VIA_RESCUE, screen.getStringExtra(RingActivity.EXTRA_VIA))
        assertEquals(WakeService.ACTION_DEADLINE, screen.getStringExtra(StartRefused.EXTRA_ACTION))
        assertEquals(plan, AlarmReceiver.planFrom(screen))
    }

    @Test
    fun refusedSnoozeEndAlsoRings() {
        WakeService.command(Refusing(app), WakeService.ACTION_SNOOZE_END, plan)
        assertNotNull(manager.getNotification(Notifications.ID_RING))
    }

    @Test
    fun refusedWindowStartAndGoodNightOnlyLog() {
        val context = Refusing(app)
        WakeService.start(context, plan)
        WakeService.goodNight(context, "a")
        WakeService.autoOnset(context, "a")
        WakeService.resumeGoodNight(context)
        assertEquals(4, context.attempts)
        assertNull(manager.getNotification(Notifications.ID_RING))
        assertTrue(manager.allNotifications.isEmpty())
    }

    @Test
    fun ringScreenResumesOnlyFromRescueIntent() {
        val rescue = shadowOf(StartRefused.notification(app, WakeService.ACTION_DEADLINE, plan).fullScreenIntent).savedIntent
        val context = Refusing(app)
        assertTrue(StartRefused.resume(context, rescue))
        // Der Weckbildschirm startet den Dienst mit demselben Auftrag (hier wieder abgelehnt, also erneut die Mitteilung).
        assertEquals(1, context.attempts)
        assertFalse(StartRefused.resume(context, Intent(app, RingActivity::class.java).putExtra(RingActivity.EXTRA_VIA, RingActivity.VIA_TAP)))
        assertFalse(StartRefused.resume(context, null))
        val foreign = Intent(rescue).putExtra(StartRefused.EXTRA_ACTION, WakeService.ACTION_CANCEL)
        assertFalse(StartRefused.resume(context, foreign))
        assertEquals(1, context.attempts)
    }
}
