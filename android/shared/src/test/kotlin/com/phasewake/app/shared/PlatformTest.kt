package com.phasewake.app.shared

import android.app.Application
import android.app.NotificationManager
import com.google.android.gms.wearable.DataMap
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Codec
import com.phasewake.app.core.DiagDevice
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.DiagLog
import com.phasewake.app.core.HolidayList
import com.phasewake.app.core.Holidays
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.WakeReason
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executor
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Teile, die Android brauchen (Robolectric): «Nicht stören», Diagnose-Datei, empfangene Datenpakete. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlatformTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val direct = Executor { it.run() }

    @Test
    fun doNotDisturbBlocksOnlyWithoutAlarms() {
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        assertEquals(DoNotDisturb.Alarms.ALLOWED, DoNotDisturb.alarms(app))
        assertFalse(DoNotDisturb.blocksAlarms(app))
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        assertEquals(DoNotDisturb.Alarms.BLOCKED, DoNotDisturb.alarms(app))
        assertTrue(DoNotDisturb.blocksAlarms(app))
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        manager.notificationPolicy = NotificationManager.Policy(0, 0, 0)
        assertEquals(DoNotDisturb.Alarms.BLOCKED, DoNotDisturb.alarms(app))
        assertTrue(DoNotDisturb.blocksAlarms(app))
        manager.notificationPolicy = NotificationManager.Policy(NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS, 0, 0)
        assertEquals(DoNotDisturb.Alarms.ALLOWED, DoNotDisturb.alarms(app))
        assertFalse(DoNotDisturb.blocksAlarms(app))
    }

    @Test
    fun doNotDisturbOpensZenSettings() {
        DoNotDisturb.openSettings(app)
        assertEquals("android.settings.ZEN_MODE_SETTINGS", shadowOf(app).nextStartedActivity?.action)
    }

    @Test
    fun diagnosticsLogAppendsTrimsAndClears() {
        val dir: File = Files.createTempDirectory("diag").toFile()
        var now = Instant.parse("2026-10-29T06:00:00Z")
        val log = DiagnosticsLog(File(dir, "diag.log"), DiagDevice.WATCH, { now }, direct)
        assertEquals(emptyList(), log.entries())
        log.log(DiagKind.RING, "light|sleep")
        assertEquals(1, log.changes.value)
        assertEquals("light sleep", log.entries().single().detail)
        repeat(DiagLog.MAX_ENTRIES + DiagnosticsLog.SLACK) {
            now = now.plusSeconds(1)
            log.log(DiagKind.SYNC, "$it")
        }
        val kept = log.entries()
        assertEquals(DiagLog.MAX_ENTRIES, kept.size)
        assertTrue(File(dir, "diag.log").readLines().size <= DiagLog.MAX_ENTRIES + DiagnosticsLog.SLACK)
        // Eine neue Instanz liest dieselbe Datei.
        assertEquals(kept, DiagnosticsLog(File(dir, "diag.log"), DiagDevice.WATCH, { now }, direct).entries())
        log.clear()
        assertEquals(emptyList(), log.entries())
        log.clear()
    }

    @Test
    fun diagnosticsLogForContextIsOnePerProcess() {
        val first = DiagnosticsLog.forContext(app, DiagDevice.PHONE)
        assertSame(first, DiagnosticsLog.forContext(app, DiagDevice.PHONE))
    }

    @Test
    fun readsReceivedDataItems() {
        val alarms = DataMap().apply {
            putString(DataLayer.KEY_TEXT, Codec.encodeAlarms(listOf(AlarmSpec(id = "a", hour = 7, minute = 0))))
            putLong(DataLayer.KEY_UPDATED_AT, 42L)
        }
        assertEquals(42L, WearSync.readAlarms(alarms)?.updatedAt)
        assertNull(WearSync.readAlarms(DataMap().apply { putString(DataLayer.KEY_TEXT, "kaputt") }))
        assertNull(WearSync.readNight(DataMap().apply { putString(DataLayer.KEY_TEXT, "kaputt") }))
        val list = HolidayList("CH-BE", setOf(LocalDate.of(2026, 12, 26)))
        val holidays = DataMap().apply {
            putString(DataLayer.KEY_TEXT, Holidays.encode(list))
            putLong(DataLayer.KEY_UPDATED_AT, 7L)
        }
        assertEquals(list to 7L, WearSync.readHolidays(holidays))
        assertEquals(HolidayList("", emptySet()) to 0L, WearSync.readHolidays(DataMap()))
    }

    @Test
    fun buildsDataItemRequests() {
        val spec = AlarmSpec(id = "a", hour = 7, minute = 0)
        val alarms = WearSync.alarmsRequest(AlarmSnapshot(listOf(spec), 5L))
        assertEquals(DataLayer.PATH_ALARMS, alarms.uri.path)
        assertTrue(alarms.isUrgent)
        assertEquals(AlarmSnapshot(listOf(spec), 5L), WearSync.readAlarms(DataMap.fromByteArray(alarms.data!!)))

        val night = NightRecord("a b", Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(2_000), Instant.ofEpochMilli(1_500), WakeReason.DEADLINE, 0, emptyList())
        val nightItem = WearSync.nightRequest(night)
        assertEquals(DataLayer.PATH_NIGHT_PREFIX + "1000-a%20b", nightItem.uri.path)
        assertEquals(night, WearSync.readNight(DataMap.fromByteArray(nightItem.data!!)))

        val status = DataMap.fromByteArray(WearSync.statusRequest(true, 55, 10_000L).data!!)
        assertTrue(WearSync.readReady(status))
        assertEquals(55, WearSync.readBattery(status, 10_500L, 1_000L))
        assertNull(WearSync.readBattery(status, 20_000L, 1_000L))
        assertEquals(10_000L, WearSync.readSeenAt(status))
        assertEquals(Codec.ALARM_FIELDS, WearSync.readAlarmFields(status))
        // Status einer älteren Uhr ohne Angabe: 0, also ohne Überspringen und Feiertage.
        assertEquals(0, WearSync.readAlarmFields(DataMap().apply { putBoolean(DataLayer.KEY_READY, true) }))
        assertFalse(Codec.watchKnowsSkip(WearSync.readAlarmFields(DataMap())))
        val noBattery = DataMap.fromByteArray(WearSync.statusRequest(false, null, 10_000L).data!!)
        assertFalse(WearSync.readReady(noBattery))
        assertNull(WearSync.readBattery(noBattery, 10_000L, 1_000L))
        assertNull(WearSync.readSeenAt(DataMap()))

        val list = HolidayList("DE", setOf(LocalDate.of(2026, 10, 3)))
        assertEquals(list to 9L, WearSync.readHolidays(DataMap.fromByteArray(WearSync.holidaysRequest(list, 9L).data!!)))
        val cancel = WearSync.transientCancelRequest("nap-1", 5L)
        assertEquals(DataLayer.PATH_TRANSIENT_CANCELLED, cancel.uri.path)
        assertTrue(cancel.isUrgent)
        assertEquals("nap-1", WearSync.readTransientCancel(DataMap.fromByteArray(cancel.data!!)))
        assertNull(WearSync.readTransientCancel(DataMap()))
        assertNull(WearSync.readTransientCancel(DataMap().apply { putString(DataLayer.KEY_TEXT, "") }))
        val clear = WearSync.clearRequest(77L)
        assertEquals(DataLayer.PATH_CLEAR_HISTORY, clear.uri.path)
        assertEquals(77L, DataMap.fromByteArray(clear.data!!).getLong(DataLayer.KEY_UPDATED_AT))
    }
}
