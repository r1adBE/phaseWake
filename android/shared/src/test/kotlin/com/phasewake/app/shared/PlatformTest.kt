package com.phasewake.app.shared

import android.app.Application
import android.app.NotificationManager
import com.google.android.gms.wearable.DataMap
import com.phasewake.app.core.Codec
import com.phasewake.app.core.DiagDevice
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.DiagLog
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.RecorderSkip
import com.phasewake.app.core.WakeReason
import java.io.File
import java.nio.file.Files
import java.time.Instant
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
    fun readsLegacyNightForMigration() {
        // Früheres Nacht-Paket: liest nur noch die einmalige Umstellung der Uhr.
        val night = NightRecord("a b", Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(2_000), Instant.ofEpochMilli(1_500), WakeReason.DEADLINE, 0, emptyList())
        assertEquals(night, WearSync.readNight(DataMap().apply { putString(DataLayer.KEY_TEXT, Codec.encodeNight(night)) }))
        assertNull(WearSync.readNight(DataMap().apply { putString(DataLayer.KEY_TEXT, "kaputt") }))
    }

    @Test
    fun buildsDataItemRequests() {
        val status = DataMap.fromByteArray(WearSync.statusRequest(true, 55, 10_000L).data!!)
        assertTrue(WearSync.readReady(status))
        assertEquals(55, WearSync.readBattery(status, 10_500L, 1_000L))
        assertNull(WearSync.readBattery(status, 20_000L, 1_000L))
        assertEquals(10_000L, WearSync.readSeenAt(status))
        // Keine Felder je Weckerzeile mehr (kein Rückfall für ältere Uhren, 10.11.2026).
        assertFalse(status.containsKey("alarmFields"))
        val noBattery = DataMap.fromByteArray(WearSync.statusRequest(false, null, 10_000L).data!!)
        assertFalse(WearSync.readReady(noBattery))
        assertNull(WearSync.readBattery(noBattery, 10_000L, 1_000L))
        assertNull(WearSync.readSeenAt(DataMap()))
        // Verbindungs-Stand der Uhr (Review O-04): immer mitgeschickt, ohne Angabe 0 (ältere Uhr).
        assertEquals(DataLayer.PROTOCOL, WearSync.readProtocol(noBattery))
        assertEquals(0, WearSync.readProtocol(DataMap().apply { putBoolean(DataLayer.KEY_READY, true) }))
        // Pulsrechte der Uhr: nur mitgeschickt, wenn bekannt; ohne Angabe (ältere Uhr) keine Warnung.
        assertNull(WatchPulse.read(noBattery))
        assertFalse(WatchPulse.warns(WatchPulse.read(noBattery)))
        val pulse = WatchPulse(heartRate = true, background = false)
        val withPulse = DataMap.fromByteArray(WearSync.statusRequest(true, 50, 10_000L, pulse).data!!)
        assertEquals(pulse, WatchPulse.read(withPulse))
        assertTrue(WatchPulse.warns(WatchPulse.read(withPulse)))
        assertFalse(WatchPulse.warns(WatchPulse(heartRate = true, background = true)))
        // Messung vor dem Schlafen nicht gestartet (Audit F-06): leer ohne Meldung, ohne Angabe (ältere Uhr) keine.
        // Nur der Grund geht ins Paket, keine Uhrzeit (Datenpakete tragen nur technische Angaben).
        assertNull(WearSync.readRecorderSkip(noBattery, 10_000L, 1_000L))
        assertNull(WearSync.readRecorderSkip(DataMap(), 10_000L, 1_000L))
        val skipped = DataMap.fromByteArray(WearSync.statusRequest(true, 25, 10_000L, pulse, RecorderSkip.Reason.BATTERY).data!!)
        assertEquals("battery", skipped.getString(DataLayer.KEY_RECORDER_SKIP))
        assertEquals(RecorderSkip.Reason.BATTERY, WearSync.readRecorderSkip(skipped, 10_500L, 1_000L))
        assertNull(WearSync.readRecorderSkip(skipped, 20_000L, 1_000L), "zu alt")

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

    @Test
    fun olderWatchIsOutdated() {
        assertTrue(WearSync.watchOutdated(1, own = 2))
        assertTrue(WearSync.watchOutdated(WearSync.readProtocol(DataMap())), "Status ohne Angabe: ältere Uhr")
        assertFalse(WearSync.watchOutdated(DataLayer.PROTOCOL), "gleicher Stand")
        assertFalse(WearSync.watchOutdated(3, own = 2), "Uhr neuer als das Handy")
        assertFalse(WearSync.watchOutdated(null), "keine Uhr bekannt")
    }
}
