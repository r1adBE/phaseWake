package com.phasewake.app.wear.record

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.LearnNights
import com.phasewake.app.core.LearnSkip
import com.phasewake.app.core.Learning
import com.phasewake.app.core.SleepGoal
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.LearningSnapshot
import com.phasewake.app.shared.Outbox
import com.phasewake.app.shared.QueueMessages
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.schedule.AlarmReceiver
import com.phasewake.app.wear.wearApp
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
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
import kotlin.test.assertTrue

/**
 * Hinweise der Lernphase auf der Galaxy Watch ([LearnNotice], Lern-Übersicht r1AD 13.11.2026): Warnung am Abend (Alarm
 * stellen, beim Auslösen den Akku prüfen) und die Meldung «nicht aufgezeichnet» ans Handy über die Warteschlange.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class LearnNoticeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = context.wearApp
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))
    private val zone = ZoneId.systemDefault()
    private val everyDay = DayOfWeek.entries.toSet()

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        battery(80)
        runBlocking { app.learning.setIfNewer(LearningSnapshot(true, null, null, System.currentTimeMillis())) }
    }

    /**
     * Hintergrundarbeit dieses Tests beenden und abwarten: Ein `cancel` allein liesse den Rest einer Arbeit ohne
     * Haltepunkt weiterlaufen (z. B. die Mitteilung nach der Meldung in [LearnNotice.skipped]). Die Mitteilungen der
     * Robolectric-Schatten sind prozessweit; eine verspätete landete sonst im nächsten Test (CI 14.11.2026:
     * «genug Akku» in [warningShowsOnlyWithLowBatteryWhileNotCharging] nach [learningStartBelowTheThresholdReportsTheNight]).
     */
    @After
    fun cleanUp() = runBlocking { withTimeout(10_000) { app.appScope.coroutineContext.job.cancelAndJoin() } }

    private fun battery(percent: Int, charging: Boolean = false) {
        val manager = shadowOf(context.getSystemService(BatteryManager::class.java))
        manager.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, percent)
        manager.setIsCharging(charging)
    }

    @Suppress("DEPRECATION") // ScheduledAlarm.operation: in Robolectric ohne Getter (wie GoodNightOnsetTest).
    private fun warnAlarms() = alarms.scheduledAlarms.filter { alarm -> alarm.operation?.let { shadowOf(it).savedIntent.action } == LearnNotice.ACTION_WARN }

    private fun awaitUntil(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!done()) {
            check(System.nanoTime() < until) { what }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /** Wecker, dessen Einschlaf-Messung (und damit die Aufzeichnung) in [minutes] Minuten beginnt. */
    private fun alarmStartingIn(minutes: Long): AlarmSpec {
        val target = Instant.now().plus(Duration.ofMinutes(SleepGoal.DEFAULT_MINUTES + 60L + minutes)).atZone(zone)
        return AlarmSpec(id = "w", hour = target.hour, minute = target.minute, days = everyDay)
    }

    @Test
    fun warningIsPlannedOneHourBeforeTheRecording() = runBlocking {
        val spec = AlarmSpec(id = "w", hour = 7, minute = 0, days = everyDay)
        app.alarms.replaceAll(listOf(spec))
        val now = Instant.now()
        val plan = AlarmSchedule.planAt(spec, now.plus(Duration.ofHours(20)))
        LearnNotice.update(context, plan, now)
        val start = plan.target.minus(Duration.ofMinutes(SleepGoal.DEFAULT_MINUTES + 60L))
        assertEquals(listOf(LearnNights.warnAt(start).toEpochMilli()), warnAlarms().map { it.triggerAtMs })
        // Lernphase aus: keine Warnung (aufgehoben).
        app.learning.setIfNewer(LearningSnapshot(false, null, null, System.currentTimeMillis() + 1))
        LearnNotice.update(context, plan, now)
        assertTrue(warnAlarms().isEmpty())
        // Schon vorbei: keine.
        app.learning.setIfNewer(LearningSnapshot(true, null, null, System.currentTimeMillis() + 2))
        LearnNotice.update(context, AlarmSchedule.planAt(spec, now.plus(Duration.ofHours(9))), now)
        assertTrue(warnAlarms().isEmpty())
    }

    @Test
    fun warningShowsOnlyWithLowBatteryWhileNotCharging() {
        runBlocking { app.alarms.replaceAll(listOf(alarmStartingIn(30))) }
        battery(Learning.MIN_BATTERY)
        runBlocking { LearnNotice.onWarn(context) }
        assertTrue(notifications.allNotifications.isEmpty(), "genug Akku")
        battery(Learning.MIN_BATTERY - 5, charging = true)
        runBlocking { LearnNotice.onWarn(context) }
        assertTrue(notifications.allNotifications.isEmpty(), "lädt")
        battery(Learning.MIN_BATTERY - 5)
        // Wie vom System: Alarm an den Empfänger, Arbeit im Hintergrund.
        context.sendBroadcast(Intent(context, AlarmReceiver::class.java).setAction(LearnNotice.ACTION_WARN))
        awaitUntil("Warnung gezeigt") { notifications.allNotifications.isNotEmpty() }
        val shown = notifications.allNotifications.single()
        assertEquals(LearnNotice.CHANNEL, shown.channelId)
        assertTrue(shown.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString().contains("${Learning.MIN_BATTERY - 5}"))
    }

    @Test
    fun skippedNightGoesToThePhoneAndShowsTheReason() {
        val target = Instant.ofEpochMilli(System.currentTimeMillis()).plus(Duration.ofHours(8))
        runBlocking { LearnNotice.skipped(app, LearnSkip(target, LearnSkip.Reason.BATTERY, 35)) }
        val entry = runBlocking { Outbox.forContext(context).pending() }.single { it.key == QueueMessages.KEY_LEARN_SKIP + target.toEpochMilli() }
        assertEquals(DataLayer.PATH_MSG_LEARN_SKIP, entry.path)
        assertEquals(LearnSkip(target, LearnSkip.Reason.BATTERY, 35), LearnSkip.decode(entry.body))
        assertEquals(1, notifications.allNotifications.size)
        // Eine laufende Aufzeichnung von Hand: nur die Meldung, keine Mitteilung.
        runBlocking { LearnNotice.skipped(app, LearnSkip(target.plusSeconds(60), LearnSkip.Reason.MANUAL)) }
        assertEquals(1, notifications.allNotifications.size)
    }

    @Test
    fun learningStartBelowTheThresholdReportsTheNight() {
        battery(Learning.MIN_BATTERY - 2)
        val until = Instant.ofEpochMilli(System.currentTimeMillis()).plus(Duration.ofHours(8))
        assertTrue(!RecordControl.startForLearning(context, until))
        val key = QueueMessages.KEY_LEARN_SKIP + until.toEpochMilli()
        awaitUntil("Meldung eingereiht") { runBlocking { Outbox.forContext(context).pending() }.any { it.key == key } }
        val body = runBlocking { Outbox.forContext(context).pending() }.single { it.key == key }.body
        assertEquals(LearnSkip(until, LearnSkip.Reason.BATTERY, Learning.MIN_BATTERY - 2), LearnSkip.decode(body))
        // Die Mitteilung kommt nach der Meldung ans Handy; abwarten, damit die Arbeit in diesem Test endet.
        awaitUntil("Mitteilung gezeigt") { notifications.allNotifications.isNotEmpty() }
        assertEquals(LearnNotice.CHANNEL, notifications.allNotifications.single().channelId)
    }

    @Test
    fun sensorsRefusedReportsOnlyWhileLearning() = runBlocking {
        val spec = AlarmSpec(id = "w", hour = 7, minute = 0, days = everyDay)
        app.alarms.replaceAll(listOf(spec))
        LearnNotice.sensorsRefused(app, "w")
        val target = AlarmSchedule.nextTarget(spec, Instant.now(), zone)!!
        val pending = Outbox.forContext(context).pending()
        assertEquals(LearnSkip(target, LearnSkip.Reason.SENSORS), pending.map { LearnSkip.decode(it.body) }.single { it?.reason == LearnSkip.Reason.SENSORS })
        LearnNotice.sensorsRefused(app, "unbekannt")
        assertEquals(pending.size, Outbox.forContext(context).pending().size)
    }

    @Test
    fun sensorsRefusedReportsNothingWhenLearningIsOff() = runBlocking {
        // Audit BE-1c: Lernphase aus → keine Meldung «keine Sensoren», auch für einen bekannten Wecker.
        val spec = AlarmSpec(id = "w", hour = 7, minute = 0, days = everyDay)
        app.alarms.replaceAll(listOf(spec))
        app.learning.setIfNewer(LearningSnapshot(false, null, null, System.currentTimeMillis() + 1))
        // Die Warteschlange ist prozessweit (auch frühere Tests); ein ersetzter Eintrag bekäme eine neue Kennung.
        val before = Outbox.forContext(context).pending().map { it.id }
        LearnNotice.sensorsRefused(app, "w")
        assertEquals(before, Outbox.forContext(context).pending().map { it.id })
        assertTrue(notifications.allNotifications.isEmpty(), "keine Mitteilung")
    }
}
