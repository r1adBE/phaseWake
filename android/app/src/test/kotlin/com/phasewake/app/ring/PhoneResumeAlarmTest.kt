package com.phasewake.app.ring

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import com.phasewake.app.MemoryStore
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Snooze
import com.phasewake.app.shared.AlarmRepository
import com.phasewake.app.shared.WearSync
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
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
 * Alarme des Klingelns am Handy über [BackupAlarm]: Wiederaufnahme-Alarm beim Klingeln gestellt (übersteht ein
 * Prozessende), beim Schlummern ersetzt und beim Beenden aufgehoben; dazu das Aussetzen nach einem Zweitwecker zur
 * früheren Zielzeit des Einschlaf-Modus (Review K-07). Feste Uhrzeit, schlichte [Application].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneResumeAlarmTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val now = Instant.parse("2026-11-03T05:40:00Z")
    private val repository = AlarmRepository(MemoryStore())
    private val backup by lazy { BackupAlarm(context, repository, MemoryStore(), WearSync(context), now = { now }) }

    private fun triggers() = alarms.scheduledAlarms.map { it.triggerAtMs }

    @Test
    fun resumeArmedOnRingReplacedBySnoozeClearedOnStop() {
        // Klingeln (Handy führt): Alarm kurz nach der Zeitgrenze, abgelegt mit den bisherigen Runden.
        val resume = Instant.ofEpochMilli(RingRules.resumeAt(fromWatch = false, nowMs = now.toEpochMilli())!!)
        backup.snoozeUntil(resume, "a", 0)
        assertEquals(listOf(resume.toEpochMilli()), triggers())
        assertEquals(PhoneSnooze.Saved(resume, "a", 0), PhoneSnooze.load(context))
        // Schlummern ersetzt ihn (gleicher Alarm): nur noch das Schlummer-Ende steht.
        val snoozeEnd = Snooze.nextRing(now)
        backup.snoozeUntil(snoozeEnd, "a", 1)
        assertEquals(listOf(snoozeEnd.toEpochMilli()), triggers())
        assertEquals(1, PhoneSnooze.load(context)?.snoozesDone)
        // Beenden hebt Alarm und Abgelegtes auf.
        backup.cancelSnooze()
        assertTrue(triggers().isEmpty())
        assertNull(PhoneSnooze.load(context))
    }

    @Test
    fun earlierOnsetTargetAlsoCoversConfiguredTime() = runBlocking {
        val zone = ZoneId.systemDefault()
        val spec = AlarmSpec(id = "a", hour = 7, minute = 0, days = DayOfWeek.entries.toSet(), sleepDurationMinutes = 450)
        repository.upsert(spec)
        val configured = AlarmSchedule.nextTarget(spec, now.minus(Duration.ofHours(12)), zone)!!
        val earlier = configured.minus(Duration.ofMinutes(40))
        // Der Zweitwecker klingelte zur früheren Zielzeit: bis nach der eingestellten Zeit derselben Nacht ausgesetzt.
        backup.backupRang("a", earlier)
        assertEquals(configured.plus(BackupAlarm.GRACE), backup.doneUntil()["a"])
        // Ohne Verschiebung wie bisher: bis kurz nach der eigenen Zielzeit.
        val next = AlarmSchedule.nextTarget(spec, configured, zone)!!
        backup.backupRang("a", next)
        assertEquals(next.plus(BackupAlarm.GRACE), backup.doneUntil()["a"])
    }
}
