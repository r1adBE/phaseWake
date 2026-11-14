package com.phasewake.app.ring

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.UserManager
import com.phasewake.app.QuietApp
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.phoneApp
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
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
import kotlin.test.fail

/**
 * Empfänger des Zweitweckers ([onBackupAlarm], [onBackupSystemEvent], Audit M-06): Zweitwecker, Sicherung nach dem
 * Schlummern, Weiterleitung der Uhr und Sonnenaufgang, entsperrt und vor dem ersten Entsperren; Neustart und andere
 * Systemereignisse. Zustellung wie vom System über [Context.sendBroadcast]; die Arbeit im Hintergrund wird abgewartet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class BackupReceiversTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = context.phoneApp
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val users get() = shadowOf(context.getSystemService(UserManager::class.java))
    private val target: Instant = Instant.now().truncatedTo(ChronoUnit.MINUTES)
    private val ids = mutableListOf<String>()

    @Before
    fun clean() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        BackupDirectBoot.clear(context)
        SnoozeSafety.clear(context)
    }

    @After
    fun cleanUp() {
        users.setUserUnlocked(true)
        SunriseLight.stop(context)
        // Der DataStore lebt über den Test hinaus: eigene Wecker wieder entfernen.
        runBlocking { ids.forEach { app.alarms.remove(it) } }
    }

    private fun add(spec: AlarmSpec) = runBlocking { app.alarms.upsert(spec) }.also { ids += spec.id }

    private fun send(action: String, alarmId: String?, target: Instant? = null, receiver: Class<*> = BackupAlarm.Receiver::class.java, extra: (Intent) -> Unit = {}) {
        val intent = Intent(context, receiver).setAction(action)
        if (alarmId != null) intent.putExtra(PhoneRingService.EXTRA_ALARM_ID, alarmId)
        if (target != null) intent.putExtra(PhoneRingService.EXTRA_TARGET, target.toEpochMilli())
        extra(intent)
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Wartet auf die Arbeit im Hintergrund (appScope), höchstens 5 s. */
    private fun until(what: String, check: suspend () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!runBlocking { check() }) {
            if (System.nanoTime() > end) fail(what)
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun startedRing(): Intent {
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNotNull(started, "Weckdienst gestartet")
        assertEquals(PhoneRingService.ACTION_RING, started.action)
        return started
    }

    private suspend fun enabled(id: String) = app.alarms.current().alarms.first { it.id == id }.enabled

    @Test
    fun backupRingsMarksTheNightAndTurnsOffAOneShot() {
        // Der Einmal-Wecker zur Zielzeit des Zweitweckers (Fix G-1/S-1: nur die Fassung, die geklingelt hat).
        val local = target.atZone(java.time.ZoneId.systemDefault())
        add(AlarmSpec(id = "r1", hour = local.hour, minute = local.minute))
        send(BackupAlarm.ACTION, "r1", target)
        val started = startedRing()
        assertEquals("r1", started.getStringExtra(PhoneRingService.EXTRA_ALARM_ID))
        assertEquals(target.toEpochMilli(), started.getLongExtra(PhoneRingService.EXTRA_TARGET, 0L))
        until("Nacht ausgesetzt") { app.backup.doneUntil()["r1"] != null }
        until("Einmal-Wecker aus") { !enabled("r1") }
    }

    @Test
    fun backupOfAMovedOneShotLeavesTheNewTimeOn() {
        // Fix G-1/S-1: Inzwischen (z. B. auf der Uhr) auf 30 Min später verschoben – das Klingeln der alten Zeit schaltet
        // die neue nicht aus.
        val moved = target.plus(Duration.ofMinutes(30)).atZone(java.time.ZoneId.systemDefault())
        add(AlarmSpec(id = "r5", hour = moved.hour, minute = moved.minute))
        send(BackupAlarm.ACTION, "r5", target)
        startedRing()
        until("Nacht ausgesetzt") { app.backup.doneUntil()["r5"] != null }
        Thread.sleep(200)
        assertTrue(runBlocking { enabled("r5") }, "der verschobene Einmal-Wecker bleibt eingeschaltet")
    }

    @Test
    fun snoozeSafetyRingsClearsItselfAndTurnsOffAOneShot() {
        add(AlarmSpec(id = "r2", hour = 7, minute = 0))
        SnoozeSafety.save(context, SnoozeSafety.Saved(target, "r2", 2))
        send(BackupAlarm.ACTION_SNOOZE_SAFETY, "r2") { it.putExtra(PhoneRingService.EXTRA_SNOOZES_DONE, 2) }
        assertEquals(2, startedRing().getIntExtra(PhoneRingService.EXTRA_SNOOZES_DONE, 0))
        assertNull(SnoozeSafety.load(context))
        until("Einmal-Wecker aus") { !enabled("r2") }
    }

    @Test
    fun watchRingIsForwardedWithItsRounds() {
        send(BackupAlarm.ACTION_WATCH_RING, "r3", target) { it.putExtra(PhoneRingService.EXTRA_SNOOZES_LEFT, 2) }
        val started = startedRing()
        assertTrue(started.getBooleanExtra("fromWatch", false))
        assertEquals(2, started.getIntExtra(PhoneRingService.EXTRA_SNOOZES_LEFT, 0))
        assertEquals(target.toEpochMilli(), started.getLongExtra(PhoneRingService.EXTRA_TARGET, 0L))
    }

    @Test
    fun sunriseStartsTheLightOnce() {
        val end = Instant.now().plus(Duration.ofMinutes(20))
        send(BackupAlarm.ACTION_SUNRISE, "r4") {
            it.putExtra(SunriseLight.EXTRA_START, Instant.now().toEpochMilli()).putExtra(SunriseLight.EXTRA_END, end.toEpochMilli())
        }
        until("Licht läuft") { SunriseLight.active.value != null }
        assertEquals("r4", SunriseLight.active.value?.alarmId)
        assertEquals(end.toEpochMilli(), SunriseLight.active.value?.end?.toEpochMilli())
        assertTrue(SunriseLight.progressFor("r4") in 0f..1f)
        assertEquals(0f, SunriseLight.progressFor("other"))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, "Licht ohne Weckdienst")
    }

    @Test
    fun unknownActionDoesNothing() {
        send("com.phasewake.app.action.UNKNOWN", "r5", target)
        send("com.phasewake.app.action.UNKNOWN", null, receiver = BackupAlarm.SystemReceiver::class.java)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test
    fun beforeUnlockTheNextSavedBackupIsSetAndTheNightRemembered() {
        users.setUserUnlocked(false)
        val next = target.plus(Duration.ofDays(1))
        BackupDirectBoot.save(context, listOf(BackupDirectBoot.Entry(target, "r6", target), BackupDirectBoot.Entry(next, "r6", next)))
        send(BackupAlarm.ACTION, "r6", target)
        startedRing()
        assertEquals(listOf(next), BackupDirectBoot.load(context).map { it.target })
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtMs == next.toEpochMilli() })
        // Ende des Schlummerns (ohne Zielzeit): Der abgelegte Zweitwecker bleibt, wie er ist.
        send(BackupAlarm.ACTION, "r6")
        startedRing()
        assertEquals(listOf(next), BackupDirectBoot.load(context).map { it.target })
        // Sicherung nach dem Schlummern vor dem Entsperren: klingelt, sonst nichts.
        send(BackupAlarm.ACTION_SNOOZE_SAFETY, "r6")
        startedRing()
        assertEquals(listOf("r6" to target), BackupDirectBoot.takeRang(context))
    }

    @Test
    fun bootBeforeUnlockRestoresOnlyTheSavedPlans() {
        users.setUserUnlocked(false)
        val next = Instant.now().plus(Duration.ofHours(3)).truncatedTo(ChronoUnit.MINUTES)
        BackupDirectBoot.save(context, listOf(BackupDirectBoot.Entry(next, "r7", next)))
        send(Intent.ACTION_LOCKED_BOOT_COMPLETED, null, receiver = BackupAlarm.SystemReceiver::class.java)
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtMs == next.toEpochMilli() })
    }

    @Test
    fun bootAfterUnlockCatchesUpNightsRangBeforeUnlock() {
        // Vor dem Entsperren geklungen (nur gemerkt): Nach dem Neustart setzt das Handy die Nacht aus.
        add(AlarmSpec(id = "r8", hour = 7, minute = 0, days = DayOfWeek.entries.toSet()))
        BackupDirectBoot.markRang(context, "r8", target.toEpochMilli())
        send(Intent.ACTION_BOOT_COMPLETED, null, receiver = BackupAlarm.SystemReceiver::class.java)
        until("Nacht ausgesetzt") { app.backup.doneUntil()["r8"] != null }
        assertEquals(emptyList(), BackupDirectBoot.takeRang(context))
    }
}
