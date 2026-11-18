package com.phasewake.app.ring

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import com.phasewake.app.MemoryStore
import com.phasewake.app.QuietApp
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DiagKind
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
 * Weckmeldungen der Uhr am Handy ([BackupAlarm.watchRang]): Weiterleitung über einen Wecker-Alarm, ohne
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
    private val backup by lazy {
        BackupAlarm(
            context, repository, MemoryStore(), WearSync(context), now = { now }, watchSeenAt = { now.toEpochMilli() },
        )
    }

    @Before
    fun clean() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        SnoozeSafety.clear(context)
    }

    @After
    fun cleanUp() = SunriseLight.stop(context)

    private fun ring(id: String? = "a") =
        RingMessage(id, configured.toEpochMilli(), now.toEpochMilli(), snoozesLeft = 2)

    @Suppress("DEPRECATION") // ScheduledAlarm.operation: in Robolectric 4.14 ohne Getter.
    private fun scheduled(action: String) =
        alarms.scheduledAlarms.filter { shadowOf(it.operation).savedIntent.action == action }

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

    /** Zweitwecker mit fester Zeit [at] und Speicher [store] (Uhr ohne Kennung meldet zu dieser Zeit). */
    private fun backupAt(at: Instant, store: MemoryStore = MemoryStore()) = BackupAlarm(
        context, repository, store, WearSync(context), now = { at }, watchSeenAt = { at.toEpochMilli() },
    )

    /** Täglicher Wecker [id] um 7 Uhr + [minutes] mit Fenster [window]. */
    private fun at7(id: String, minutes: Int, window: Int) =
        spec.copy(id = id, hour = 7 + minutes / 60, minute = minutes % 60, windowMinutes = window)

    private fun minutesAfter7(minutes: Long): Instant = configured.plus(Duration.ofMinutes(minutes))

    @Test
    fun withoutIdOnlyAlarmsWhoseOwnWindowBeganArePaused() = runBlocking {
        val exact = AlarmSpec.EXACT_WINDOW
        listOf(
            at7("late", 45, 30), at7("long", 30, 60), at7("mid", 20, 30), at7("short", 15, 10),
            at7("exact", 25, exact), at7("exactLate", 35, exact),
        ).forEach { repository.upsert(it) }
        // Die Uhr klingelt um 07:00 ohne Kennung.
        val pw = backupAt(configured)
        pw.watchHandled(null)
        val done = pw.doneUntil()
        assertNull(done["late"], "07:45 mit 30 Min: Fenster ab 07:15, noch nicht begonnen")
        assertEquals(minutesAfter7(30).plus(BackupAlarm.GRACE), done["long"], "07:30 mit 60 Min: ab 06:30")
        assertEquals(minutesAfter7(20).plus(BackupAlarm.GRACE), done["mid"], "07:20 mit 30 Min: ab 06:50")
        assertNull(done["short"], "07:15 mit 10 Min: ab 07:05")
        assertEquals(minutesAfter7(25).plus(BackupAlarm.GRACE), done["exact"], "«Genau» 07:25 misst ab 06:55")
        assertNull(done["exactLate"], "«Genau» 07:35 misst ab 07:05")
    }

    @Test
    fun withoutIdTheLookbackFollowsTheOwnWindow() = runBlocking {
        // Nacht im Schlummern: 07:00 geklingelt, um 07:36 meldet die Uhr ohne Kennung (gestoppt).
        repository.upsert(at7("w30", 0, 30))
        repository.upsert(at7("w10", 0, 10))
        val pw = backupAt(minutesAfter7(36))
        pw.watchHandled(null)
        val done = pw.doneUntil()
        assertEquals(configured.plus(BackupAlarm.GRACE), done["w30"], "Rückblick 30 + 15 + 10 = 55 Min")
        assertNull(done["w10"], "Rückblick 10 + 15 + 10 = 35 Min: Nacht schon vorbei, morgen noch nicht im Fenster")
        val pw2 = backupAt(minutesAfter7(34))
        pw2.watchHandled(null)
        assertEquals(configured.plus(BackupAlarm.GRACE), pw2.doneUntil()["w10"], "innerhalb von 35 Min")
    }

    @Test
    fun withoutIdTwoAlarmsOnTheSameMorningStaySeparate() = runBlocking {
        repository.upsert(spec)
        val second = at7("b", 45, 30)
        repository.upsert(second)
        val store = MemoryStore()
        backupAt(configured, store).watchHandled(null)
        assertEquals(configured.plus(BackupAlarm.GRACE), backupAt(configured, store).doneUntil()["a"])
        assertNull(backupAt(configured, store).doneUntil()["b"])
        // Der zweite Wecker bleibt gesichert.
        val planned = scheduled(BackupAlarm.ACTION).single()
        assertEquals("b", intentOf(planned).getStringExtra(PhoneRingService.EXTRA_ALARM_ID))
        assertEquals(minutesAfter7(45).plus(BackupAlarm.GRACE).toEpochMilli(), planned.triggerAtMs)
        // 07:20 (Fenster von «b» seit 07:15): jetzt auch «b», «a» bleibt bei seiner Nacht.
        val later = backupAt(minutesAfter7(20), store)
        later.watchHandled(null)
        assertEquals(minutesAfter7(45).plus(BackupAlarm.GRACE), later.doneUntil()["b"])
        assertEquals(configured.plus(BackupAlarm.GRACE), later.doneUntil()["a"])
    }

    @Test
    fun sunriseOfLongWindowStartsThirtyMinutesBeforeTarget() = runBlocking {
        // 60-Min-Fenster zwei Stunden nach jetzt: Licht erst 30 Min vor der Zielzeit, nicht mit dem Fenster.
        val long = spec.copy(id = "long", hour = spec.hour + 2, windowMinutes = 60, sunrise = Sunrise.WINDOW)
        repository.upsert(long)
        backup.reschedule()
        val target = AlarmSchedule.nextTarget(long, now, zone)!!
        val sunrise = scheduled(BackupAlarm.ACTION_SUNRISE).single()
        assertEquals(target.minus(Duration.ofMinutes(30)).toEpochMilli(), sunrise.triggerAtMs)
        assertEquals(sunrise.triggerAtMs, intentOf(sunrise).getLongExtra(SunriseLight.EXTRA_START, 0L))
        assertEquals(target.toEpochMilli(), intentOf(sunrise).getLongExtra(SunriseLight.EXTRA_END, 0L))
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
        assertEquals(
            configured.plus(BackupAlarm.GRACE).toEpochMilli(), scheduled(BackupAlarm.ACTION).single().triggerAtMs,
        )
        val sunrise = scheduled(BackupAlarm.ACTION_SUNRISE).single()
        val windowStart = AlarmSchedule.planAt(light, configured).windowStart
        assertEquals(maxOf(windowStart, now).toEpochMilli(), sunrise.triggerAtMs)
        assertEquals(configured.toEpochMilli(), intentOf(sunrise).getLongExtra(SunriseLight.EXTRA_END, 0L))
        // Plan im Diagnose-Protokoll, einmal (erneutes Neuplanen schreibt denselben Plan nicht noch einmal).
        val diag = context.phoneApp.diag
        val planned = "phone sunrise $windowStart – $configured"
        backup.rescheduleSunrise()
        assertEquals(1, diag.entries().count { it.kind == DiagKind.SCHEDULED && it.detail == planned })
        // Das Licht beginnt: gemerkt bis zur Zielzeit, danach nicht noch einmal für dieselbe Nacht.
        backup.startSunrise(intentOf(sunrise))
        assertEquals("a", SunriseLight.active.value?.alarmId)
        assertTrue(diag.entries().any { it.kind == DiagKind.RING && it.detail == "phone sunrise start" })
        SunriseLight.stop(context)
        assertTrue(diag.entries().any { it.kind == DiagKind.STOP && it.detail == "phone sunrise end" })
        backup.rescheduleSunrise()
        assertTrue(
            scheduled(BackupAlarm.ACTION_SUNRISE)
                .none { intentOf(it).getLongExtra(SunriseLight.EXTRA_END, 0L) == configured.toEpochMilli() }
        )
    }

    @Test
    fun sunriseAfterItsEndIsNotShown() = runBlocking {
        val past = Intent().putExtra(PhoneRingService.EXTRA_ALARM_ID, "a")
            .putExtra(SunriseLight.EXTRA_START, now.minus(Duration.ofMinutes(40)).toEpochMilli())
            .putExtra(SunriseLight.EXTRA_END, now.minus(Duration.ofMinutes(10)).toEpochMilli())
        backup.startSunrise(past)
        assertNull(SunriseLight.active.value)
        // Nach einem Neustart des Prozesses: ein laufendes Licht aus der Mitteilung wieder aufnehmen, nur wenn keins
        // läuft.
        val running = SunriseLight.Active("a", now, now.plus(Duration.ofMinutes(5)))
        SunriseLight.resume(running)
        SunriseLight.resume(running.copy(alarmId = "b"))
        assertEquals("a", SunriseLight.active.value?.alarmId)
    }
}
