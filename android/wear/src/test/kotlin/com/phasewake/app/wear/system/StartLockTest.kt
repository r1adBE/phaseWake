package com.phasewake.app.wear.system

import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.record.RecordControl
import com.phasewake.app.wear.record.RecordReceiver
import com.phasewake.app.wear.schedule.AlarmReceiver
import com.phasewake.app.wear.wake.WakeService
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowPowerManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Start-Wakelock zwischen Alarm und Dienst ([StartLock], Befund 13.11.2026): Die Empfänger nehmen ihn vor dem Start,
 * mit Zeitlimit und ohne Zählung; der Weckdienst löst ihn, sobald er seinen eigenen Wakelock hält; ein verweigerter
 * Start löst ihn sofort.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class StartLockTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var controller: ServiceController<WakeService>? = null

    /** Auf Millisekunden gerundet wie im Intent; Fenster hat eben begonnen. */
    private val now = Instant.ofEpochMilli(System.currentTimeMillis())
    private val plan = WakePlan("a", now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(29)))

    /** Kontext, der jeden Vordergrund-Start ablehnt wie Android 12+ aus dem Hintergrund. */
    private class Refusing(base: Context) : ContextWrapper(base) {
        override fun startForegroundService(service: Intent): ComponentName? = throw ForegroundServiceStartNotAllowedException("test")
    }

    @Before
    fun setUp() {
        StartLock.resetForTest()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun cleanUp() {
        controller?.destroy()
        context.wearApp.appScope.cancel()
        StartLock.resetForTest()
    }

    private fun window(): Intent =
        AlarmReceiver.putPlan(Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_WINDOW_START), plan)

    @Test
    fun receiverHoldsItWithTimeoutAndWithoutCounting() {
        AlarmReceiver().onReceive(context, window())
        assertTrue(StartLock.held)
        val lock = assertNotNull(ShadowPowerManager.getLatestWakeLock())
        assertEquals(StartLock.TAG, shadowOf(lock).tag)
        assertFalse(shadowOf(lock).isReferenceCounted)
        // Ein zweiter Alarm verlängert nur; ein Lösen genügt.
        AlarmReceiver().onReceive(context, window())
        StartLock.release()
        assertFalse(StartLock.held)
        // Zeitlimit: Ohne Dienst, der ihn löst, endet er nach TIMEOUT_MS.
        AlarmReceiver().onReceive(context, window())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StartLock.TIMEOUT_MS + 1))
        assertFalse(StartLock.held)
    }

    @Test
    fun wakeServiceReleasesItOnceItHoldsItsOwn() {
        AlarmReceiver().onReceive(context, window())
        assertTrue(StartLock.held)
        val start = assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertEquals(WakeService.ACTION_START, start.action)
        assertTrue(start.getLongExtra(WakeService.EXTRA_RECEIVED_AT, 0L) > 0L, "Empfang für die Verzögerung")
        val built = Robolectric.buildService(WakeService::class.java, start)
        controller = built
        val service = built.create().startCommand(0, 1).get()
        assertTrue(service.wakeLock?.isHeld == true, "eigener Wakelock")
        assertFalse(StartLock.held)
    }

    @Test
    fun otherAlarmsAndRecordingAlsoTakeIt() {
        val deadline = AlarmReceiver.putPlan(Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_DEADLINE), plan)
        AlarmReceiver().onReceive(context, deadline)
        assertTrue(StartLock.held)
        StartLock.release()
        AlarmReceiver().onReceive(context, Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_GOOD_NIGHT_CHECK))
        assertTrue(StartLock.held)
        StartLock.release()
        RecordReceiver().onReceive(context, Intent(context, RecordReceiver::class.java).setAction(RecordControl.ACTION_CHECK))
        assertTrue(StartLock.held)
        StartLock.release()
        // Ohne Plan (veralteter Alarm) und fremde Aktion: kein Start, kein Wakelock.
        AlarmReceiver().onReceive(context, Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_DEADLINE))
        RecordReceiver().onReceive(context, Intent(context, RecordReceiver::class.java).setAction("com.phasewake.app.record.UNKNOWN"))
        assertFalse(StartLock.held)
    }

    @Test
    fun refusedStartReleasesIt() {
        StartLock.acquire(context)
        WakeService.command(Refusing(context), WakeService.ACTION_DEADLINE, plan)
        assertFalse(StartLock.held)
        StartLock.acquire(context)
        assertFalse(RecordControl.startService(Refusing(context), "com.phasewake.app.record.RESUME"))
        assertFalse(StartLock.held)
    }
}
