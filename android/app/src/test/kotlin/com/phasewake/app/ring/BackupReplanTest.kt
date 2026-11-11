package com.phasewake.app.ring

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.phasewake.app.MemoryStore
import com.phasewake.app.OfflineApp
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.phoneApp
import com.phasewake.app.shared.AlarmRepository
import com.phasewake.app.shared.WearSync
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Neuplanen des Zweitweckers (Audit A-2) und Nachholen nach einem Neustart mit entsperrtem Speicher (Audit A-5):
 * - Der bisherige Zweitwecker bleibt stehen, während das Handy rechnet (früher zuerst aufgehoben), und wird nur
 *   aufgehoben, wenn keiner mehr gilt.
 * - «Zuletzt gesehen» der Uhr hat eine Frist: Hängen die Wear-Dienste, plant das Handy trotzdem.
 * - Ein während des Neustarts fälliger Zweitwecker klingelt nach BOOT_COMPLETED gleich (auch ohne Sperre), aber nur
 *   einmal und nicht, wenn die Nacht schon erledigt ist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineApp::class)
class BackupReplanTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val zone = ZoneId.systemDefault()
    private val spec = AlarmSpec(id = "a", hour = 7, minute = 0, days = DayOfWeek.entries.toSet())
    private val configured: Instant = AlarmSchedule.nextTarget(spec, Instant.parse("2026-11-03T12:00:00Z"), zone)!!
    private val now = configured.minus(Duration.ofHours(6))
    private val repository = AlarmRepository(MemoryStore())
    private val ids = mutableListOf<String>()

    @Before
    fun clean() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        BackupDirectBoot.clear(context)
        PhoneSnooze.clear(context)
        SnoozeSafety.clear(context)
    }

    @After
    fun cleanUp() {
        runBlocking { ids.forEach { context.phoneApp.alarms.remove(it) } }
    }

    /** Alarme mit diesem Request-Code und der Aktion des Zweitweckers (0: Zweitwecker, 10: nachgeholt). */
    @Suppress("DEPRECATION") // ScheduledAlarm.operation: in Robolectric 4.14 ohne Getter.
    private fun scheduled(requestCode: Int) = alarms.scheduledAlarms.filter { alarm ->
        val operation = alarm.operation ?: return@filter false
        val shadow = shadowOf(operation)
        shadow.requestCode == requestCode && shadow.savedIntent.action == BackupAlarm.ACTION
    }

    private fun backupAt(): Long? = scheduled(0).singleOrNull()?.triggerAtMs

    @Test
    fun previousBackupStaysWhileComputingAndGoesOnlyWithoutPlan() = runBlocking {
        repository.upsert(spec)
        val seenDuringPlan = mutableListOf<Long?>()
        val backup = BackupAlarm(context, repository, MemoryStore(), WearSync(context), now = { now }, watchSeenAt = {
            // Während des Rechnens: Der bisherige Zweitwecker steht noch (früher schon aufgehoben).
            seenDuringPlan += backupAt()
            now.minus(Duration.ofHours(1)).toEpochMilli()
        })
        backup.planBackup()
        val first = assertNotNull(backupAt())
        backup.planBackup()
        assertEquals(first, seenDuringPlan.last(), "bisheriger Zweitwecker blieb beim Neuplanen stehen")
        assertEquals(first, backupAt())
        // Wecker gelöscht: kein Zweitwecker mehr.
        repository.remove("a")
        backup.planBackup()
        assertNull(backupAt())
    }

    @Test
    fun hangingWatchStatusDoesNotBlockPlanning() = runBlocking {
        repository.upsert(spec)
        var hang = false
        val backup = BackupAlarm(context, repository, MemoryStore(), WearSync(context), now = { now }, watchSeenAt = {
            if (hang) awaitCancellation()
            now.minus(Duration.ofHours(1)).toEpochMilli()
        })
        backup.planBackup()
        assertEquals(configured.plus(BackupAlarm.GRACE).toEpochMilli(), backupAt())
        // Wear-Dienste antworten nicht mehr: nach der Frist mit dem zuletzt gelesenen Stand (Uhr da, Nachfrist).
        hang = true
        withTimeout(WATCH_SEEN_TIMEOUT_MS + 10_000L) { backup.planBackup() }
        assertEquals(configured.plus(BackupAlarm.GRACE).toEpochMilli(), backupAt())
        // Ohne je gelesenen Stand: wie ohne Uhr, pünktlich (lieber zu früh als gar nicht).
        val fresh = BackupAlarm(context, repository, MemoryStore(), WearSync(context), now = { now }, watchSeenAt = { awaitCancellation() })
        withTimeout(WATCH_SEEN_TIMEOUT_MS + 10_000L) { fresh.planBackup() }
        assertEquals(configured.toEpochMilli(), backupAt())
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

    private fun boot() {
        context.sendBroadcast(Intent(context, BackupAlarm.SystemReceiver::class.java).setAction(Intent.ACTION_BOOT_COMPLETED))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun backupMissedDuringBootIsCaughtUpOnceWhenUnlocked() {
        val app = context.phoneApp
        val missed = Instant.now().minus(Duration.ofMinutes(7)).truncatedTo(ChronoUnit.MINUTES)
        runBlocking { app.alarms.upsert(AlarmSpec(id = "m1", hour = 7, minute = 0, days = DayOfWeek.entries.toSet())) }
        ids += "m1"
        BackupDirectBoot.save(context, listOf(BackupDirectBoot.Entry(missed.plus(BackupAlarm.GRACE), "m1", missed)))
        boot()
        until("nachgeholt") { scheduled(10).isNotEmpty() }
        val alarm = scheduled(10).single()
        @Suppress("DEPRECATION")
        val intent = shadowOf(alarm.operation).savedIntent
        assertEquals("m1", intent.getStringExtra(PhoneRingService.EXTRA_ALARM_ID))
        assertEquals(missed.toEpochMilli(), intent.getLongExtra(PhoneRingService.EXTRA_TARGET, 0L))
        assertTrue(alarm.triggerAtMs <= System.currentTimeMillis() + 5_000L, "gleich")
        // Nicht zweimal: Eintrag weg, Nacht bis zur Zielzeit ausgesetzt.
        until("Nacht ausgesetzt") { (app.backup.doneUntil()["m1"] ?: Instant.EPOCH) >= missed }
        assertTrue(BackupDirectBoot.load(context).none { it.target == missed })
    }

    @Test
    fun handledOrTooOldNightIsNotCaughtUp() {
        val app = context.phoneApp
        val now = Instant.now()
        val old = now.minus(BackupAlarm.MISSED_DURING_BOOT).minus(Duration.ofMinutes(5))
        val handled = now.minus(Duration.ofMinutes(4))
        runBlocking { app.backup.suppress(mapOf("h1" to handled.plus(BackupAlarm.GRACE))) }
        BackupDirectBoot.save(context, listOf(BackupDirectBoot.Entry(old, "o1", old), BackupDirectBoot.Entry(handled.plus(BackupAlarm.GRACE), "h1", handled)))
        assertEquals("h1", BackupDirectBoot.missed(context, now)?.alarmId, "zu alt: nicht mehr nachholen")
        assertNull(runBlocking { app.backup.catchUpAfterBoot(now) }, "schon erledigt")
        assertTrue(scheduled(10).isEmpty())
    }
}
