package com.phasewake.app.ring

import android.app.AlarmManager
import android.content.Context
import com.phasewake.app.MemoryStore
import com.phasewake.app.QuietApp
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.shared.AlarmRepository
import com.phasewake.app.shared.OnsetShift
import com.phasewake.app.shared.RingMessage
import com.phasewake.app.shared.WearSync
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Before
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
 * Zusammenspiel beim Planen des Zweitweckers ([planBackup], Review M-2): Nachfrist je nach Uhr (Review N-5), frühere
 * Zielzeit des Einschlaf-Modus und ihre Aufhebung (Review H-1), Liste für vor dem ersten Entsperren, Schlummern am
 * Handy daneben ([PhoneSnooze.restore]) und ein veraltetes «klingeln» (Review LATE). Feste Uhrzeit, [QuietApp].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class BackupPlanTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val zone = ZoneId.systemDefault()
    private val spec = AlarmSpec(id = "a", hour = 7, minute = 0, days = DayOfWeek.entries.toSet(), sleepDurationMinutes = 450)
    private val second = AlarmSpec(id = "b", hour = 7, minute = 30, days = DayOfWeek.entries.toSet())

    /** Zielzeit von «a» in der Nacht des Tests; «jetzt» sechs Stunden davor (in jeder Zeitzone). */
    private val configured: Instant = AlarmSchedule.nextTarget(spec, Instant.parse("2026-11-03T12:00:00Z"), zone)!!
    private val now = configured.minus(Duration.ofHours(6))
    private val grace = BackupAlarm.GRACE
    private val repository = AlarmRepository(MemoryStore())

    /** «Zuletzt gesehen» der Uhr; `null` = nie eine gemeldet. */
    private var seenAt: Long? = now.minus(Duration.ofHours(1)).toEpochMilli()
    private val backup by lazy {
        BackupAlarm(context, repository, MemoryStore(), WearSync(context), now = { now }, watchSeenAt = { seenAt })
    }

    @Before
    fun clean() {
        BackupDirectBoot.clear(context)
        PhoneSnooze.clear(context)
        SnoozeSafety.clear(context)
    }

    /** Alarme mit diesem Request-Code und dieser Aktion (Zweitwecker 0, Schlummern am Handy 3). */
    @Suppress("DEPRECATION") // ScheduledAlarm.operation: in Robolectric 4.14 ohne Getter.
    private fun scheduled(requestCode: Int) = alarms.scheduledAlarms.filter { alarm ->
        val operation = alarm.operation ?: return@filter false
        val shadow = shadowOf(operation)
        shadow.requestCode == requestCode && shadow.savedIntent.action == BackupAlarm.ACTION
    }

    private fun backupAt(): Long? = scheduled(0).singleOrNull()?.triggerAtMs

    @Test
    fun graceOnlyWhileAWatchReports() = runBlocking {
        repository.upsert(spec)
        backup.planBackup()
        assertEquals(configured.plus(grace).toEpochMilli(), backupAt(), "Uhr meldet sich: Nachfrist")
        seenAt = now.minus(RingRules.WATCH_GONE).minusSeconds(1).toEpochMilli()
        backup.planBackup()
        assertEquals(configured.toEpochMilli(), backupAt(), "seit einer Woche keine Uhr: pünktlich")
        seenAt = null
        backup.planBackup()
        assertEquals(configured.toEpochMilli(), backupAt(), "nie eine Uhr: pünktlich")
    }

    @Test
    fun secondOnsetWithoutEarlierTargetRestoresConfiguredTime() = runBlocking {
        repository.upsert(spec)
        val earlier = configured.minus(Duration.ofMinutes(60))
        // Erstes Einschlafen: frühere Zielzeit, auch in der Liste für vor dem ersten Entsperren.
        backup.onsetShiftReceived(OnsetShift("a", configured.toEpochMilli(), earlier.toEpochMilli(), 1L))
        assertEquals(earlier.plus(grace).toEpochMilli(), backupAt())
        assertEquals(earlier, BackupDirectBoot.load(context).first().target)
        // Wieder wach, zweites «Gute Nacht», Zielzeit auf der eingestellten (Review H-1): das Handy folgt.
        backup.onsetShiftReceived(OnsetShift("a", configured.toEpochMilli(), configured.toEpochMilli(), 2L))
        assertEquals(configured.plus(grace).toEpochMilli(), backupAt())
        assertEquals(configured, BackupDirectBoot.load(context).first().target)
        // Eine ältere Meldung kommt später an: ändert nichts.
        backup.onsetShiftReceived(OnsetShift("a", configured.toEpochMilli(), earlier.toEpochMilli(), 1L))
        assertEquals(configured.plus(grace).toEpochMilli(), backupAt())
    }

    @Test
    fun directBootListHoldsFollowingAlarms() = runBlocking {
        repository.upsert(spec)
        repository.upsert(second)
        backup.planBackup()
        val list = BackupDirectBoot.load(context)
        val secondTarget = AlarmSchedule.nextTarget(second, now, zone)!!
        assertEquals(listOf("a" to configured, "b" to secondTarget), list.take(2).map { it.alarmId to it.target })
        assertTrue(list.all { it.at == it.target.plus(grace) })
        assertEquals(configured.plus(grace).toEpochMilli(), backupAt())
    }

    @Test
    fun phoneSnoozeIsRestoredBesideTheBackup() = runBlocking {
        repository.upsert(spec)
        val snoozeEnd = now.plus(Duration.ofMinutes(5))
        PhoneSnooze.save(context, PhoneSnooze.Saved(snoozeEnd, "a", 1))
        backup.planBackup()
        assertEquals(listOf(snoozeEnd.toEpochMilli()), scheduled(3).map { it.triggerAtMs })
        assertEquals(configured.plus(grace).toEpochMilli(), backupAt())
        // Ein Schlummer-Ende von vor Stunden (Handy lange aus) wird nicht mehr nachgeholt.
        PhoneSnooze.save(context, PhoneSnooze.Saved(now.minus(Duration.ofHours(3)), "a", 1))
        backup.planBackup()
        assertNull(PhoneSnooze.load(context))
    }

    @Test
    fun staleRingKeepsTheBackup() = runBlocking {
        repository.upsert(spec)
        val sent = now.minusMillis(RingMessage.MAX_AGE_MS + 1)
        val stale = RingMessage("a", configured.toEpochMilli(), sent.toEpochMilli())
        assertTrue(!RingRules.ringPausesBackup(stale, now.toEpochMilli()))
        assertTrue(RingRules.ringPausesBackup(stale.copy(sentAt = now.toEpochMilli()), now.toEpochMilli()))
        backup.watchRang(stale)
        assertNull(backup.doneUntil()["a"], "veraltet: Zweitwecker dieser Nacht bleibt")
    }
}
