package com.phasewake.app.ring

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import com.phasewake.app.MemoryStore
import com.phasewake.app.QuietApp
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.PhoneAlert
import com.phasewake.app.core.Sunrise
import com.phasewake.app.phoneApp
import com.phasewake.app.shared.AlarmRepository
import com.phasewake.app.shared.RingMessage
import com.phasewake.app.shared.WearSync
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
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
import kotlin.test.assertTrue

/**
 * Weckmeldungen der Uhr am Handy ([BackupAlarm.watchRang], Audit M-06): Weiterleitung über einen Wecker-Alarm, ohne
 * exakte Alarme direkt; keine zweite Runde, wenn der Zweitwecker die Nacht schon geweckt hat; Weckart «Aus» bleibt
 * still. Dazu «Uhr hat übernommen» ohne Kennung, Sicherung nach dem Schlummern, Neuplanen und der Sonnenaufgang ab
 * Weckfenster. Feste Uhrzeit, [QuietApp].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class BackupWatchTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val zone = ZoneId.systemDefault()
    private val spec = AlarmSpec(id = "a", hour = 7, minute = 0, days = DayOfWeek.entries.toSet())
    private val configured: Instant = AlarmSchedule.nextTarget(spec, Instant.parse("2026-11-03T12:00:00Z"), zone)!!
    private val now = configured.minus(Duration.ofMinutes(5))
    private val repository = AlarmRepository(MemoryStore())
    private val backup by lazy { BackupAlarm(context, repository, MemoryStore(), WearSync(context), now = { now }, watchSeenAt = { now.toEpochMilli() }) }

    @Before
    fun clean() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        SnoozeSafety.clear(context)
    }

    @After
    fun cleanUp() = SunriseLight.stop(context)

    private fun ring(id: String? = "a") = RingMessage(id, configured.toEpochMilli(), now.toEpochMilli(), snoozesLeft = 2)

    @Suppress("DEPRECATION") // ScheduledAlarm.operation: in Robolectric 4.14 ohne Getter.
    private fun scheduled(action: String) = alarms.scheduledAlarms.filter { shadowOf(it.operation).savedIntent.action == action }

    @Suppress("DEPRECATION")
    private fun intentOf(alarm: ShadowAlarmManager.ScheduledAlarm): Intent = shadowOf(alarm.operation).savedIntent

    private fun started(): Intent? = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService

    @Test
    fun watchRingIsForwardedThroughAnAlarmClock() = runBlocking {
        repository.upsert(spec)
        backup.watchRang(ring())
        val forward = scheduled(BackupAlarm.ACTION_WATCH_RING).single()
        assertEquals(now.plus(BackupAlarm.FORWARD_DELAY).toEpochMilli(), forward.triggerAtMs)
        assertEquals("a", intentOf(forward).getStringExtra(PhoneRingService.EXTRA_ALARM_ID))
        assertEquals(2, intentOf(forward).getIntExtra(PhoneRingService.EXTRA_SNOOZES_LEFT, 0))
        assertNull(started(), "nicht direkt aus dem Hintergrund")
        // Die Uhr hat die Nacht übernommen: Zweitwecker bis kurz nach der Zielzeit ausgesetzt.
        assertEquals(configured.plus(BackupAlarm.GRACE), backup.doneUntil()["a"])
    }

    @Test
    fun withoutExactAlarmsTheRingStartsDirectly() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        repository.upsert(spec)
        backup.watchRang(ring())
        assertTrue(scheduled(BackupAlarm.ACTION_WATCH_RING).isEmpty())
        val direct = assertNotNull(started())
        assertEquals(PhoneRingService.ACTION_RING, direct.action)
        assertTrue(direct.getBooleanExtra("fromWatch", false))
    }

    @Test
    fun nightTheBackupAlreadyWokeDoesNotRingTwice() = runBlocking {
        repository.upsert(spec)
        SnoozeSafety.save(context, SnoozeSafety.Saved(now, "a", 1))
        backup.backupRang("a", configured)
        backup.watchRang(ring())
        assertTrue(scheduled(BackupAlarm.ACTION_WATCH_RING).isEmpty())
        assertNull(started())
        assertNull(SnoozeSafety.load(context), "Sicherung nach dem Schlummern aufgehoben")
    }

    @Test
    fun phoneAlertOffWithoutLightStaysSilent() = runBlocking<Unit> {
        // Die Weckart liest das Handy aus seinen Weckern (phoneApp), nicht aus der Planung.
        val off = spec.copy(id = "off", phone = PhoneAlert.OFF)
        repository.upsert(off)
        context.phoneApp.alarms.upsert(off)
        try {
            backup.watchRang(ring("off"))
            assertTrue(scheduled(BackupAlarm.ACTION_WATCH_RING).isEmpty())
            assertNull(started())
            assertNotNull(backup.doneUntil()["off"], "trotzdem ausgesetzt: Die Uhr weckt")
        } finally {
            context.phoneApp.alarms.remove("off")
        }
    }

    @Test
    fun watchHandledWithoutIdSuppressesEveryRunningNight() = runBlocking {
        repository.upsert(spec)
        repository.upsert(spec.copy(id = "far", hour = (spec.hour + 6) % 24))
        backup.watchHandled(null)
        assertEquals(configured.plus(BackupAlarm.GRACE), backup.doneUntil()["a"])
        assertNull(backup.doneUntil()["far"], "Nacht noch nicht im Fenster")
    }

    @Test
    fun snoozeSafetyIsOnlyCancelledForItsAlarm() {
        SnoozeSafety.save(context, SnoozeSafety.Saved(now, "a", 1))
        backup.cancelSnoozeSafety("b")
        assertEquals("a", SnoozeSafety.load(context)?.alarmId)
        backup.cancelSnoozeSafety("a")
        assertNull(SnoozeSafety.load(context))
    }

    @Test
    fun rescheduleSetsBackupAndSunriseFromTheWindow() = runBlocking {
        val light = spec.copy(sunrise = Sunrise.WINDOW)
        repository.upsert(light)
        backup.reschedule()
        assertEquals(configured.plus(BackupAlarm.GRACE).toEpochMilli(), scheduled(BackupAlarm.ACTION).single().triggerAtMs)
        val sunrise = scheduled(BackupAlarm.ACTION_SUNRISE).single()
        val windowStart = AlarmSchedule.planAt(light, configured).windowStart
        assertEquals(maxOf(windowStart, now).toEpochMilli(), sunrise.triggerAtMs)
        assertEquals(configured.toEpochMilli(), intentOf(sunrise).getLongExtra(SunriseLight.EXTRA_END, 0L))
        // Das Licht beginnt: gemerkt bis zur Zielzeit, danach nicht noch einmal für dieselbe Nacht.
        backup.startSunrise(intentOf(sunrise))
        assertEquals("a", SunriseLight.active.value?.alarmId)
        backup.rescheduleSunrise()
        assertTrue(scheduled(BackupAlarm.ACTION_SUNRISE).none { intentOf(it).getLongExtra(SunriseLight.EXTRA_END, 0L) == configured.toEpochMilli() })
    }

    @Test
    fun sunriseAfterItsEndIsNotShown() = runBlocking {
        val past = Intent().putExtra(PhoneRingService.EXTRA_ALARM_ID, "a")
            .putExtra(SunriseLight.EXTRA_START, now.minus(Duration.ofMinutes(40)).toEpochMilli())
            .putExtra(SunriseLight.EXTRA_END, now.minus(Duration.ofMinutes(10)).toEpochMilli())
        backup.startSunrise(past)
        assertNull(SunriseLight.active.value)
        // Nach einem Neustart des Prozesses: ein laufendes Licht aus der Mitteilung wieder aufnehmen, nur wenn keins läuft.
        val running = SunriseLight.Active("a", now, now.plus(Duration.ofMinutes(5)))
        SunriseLight.resume(running)
        SunriseLight.resume(running.copy(alarmId = "b"))
        assertEquals("a", SunriseLight.active.value?.alarmId)
    }
}
