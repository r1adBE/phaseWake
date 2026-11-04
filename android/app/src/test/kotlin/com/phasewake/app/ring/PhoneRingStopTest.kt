package com.phasewake.app.ring

import android.content.Context
import android.content.Intent
import android.os.Looper
import com.phasewake.app.QuietApp
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.phoneApp
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Beenden des Klingelns am Handy ([PhoneRingService], Audit M-06): «Stopp» am Handy hebt Wiederaufnahme und Sicherung
 * auf, der stille Stopp ebenso die Wiederaufnahme; führt die Uhr, stellt die Zeitgrenze die Sicherung nach ihrem
 * Schlummer-Ende. Dazu Weckton mit Einblenden und leiser beim Tippen, und Befehle nur an einen laufenden Dienst.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class PhoneRingStopTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-11-03T05:40:00Z")
    private var controller: ServiceController<PhoneRingService>? = null

    @Before
    fun fixedClock() {
        PhoneRingService.clock = { now.toEpochMilli() }
        PhoneSnooze.clear(context)
        SnoozeSafety.clear(context)
    }

    @After
    fun cleanUp() {
        controller?.destroy()
        PhoneRingService.clock = System::currentTimeMillis
        runBlocking { context.phoneApp.alarms.remove("tone") }
    }

    private fun ringIntent(alarmId: String, fromWatch: Boolean = false) = Intent(context, PhoneRingService::class.java)
        .setAction(PhoneRingService.ACTION_RING)
        .putExtra(PhoneRingService.EXTRA_ALARM_ID, alarmId)
        .putExtra(PhoneRingService.EXTRA_TARGET, now.toEpochMilli())
        .putExtra("fromWatch", fromWatch)
        .apply { if (fromWatch) putExtra(PhoneRingService.EXTRA_SNOOZES_LEFT, 2) }

    private fun start(intent: Intent): PhoneRingService {
        val service = Robolectric.buildService(PhoneRingService::class.java, intent).create().startCommand(0, 1)
        controller = service
        shadowOf(Looper.getMainLooper()).idle()
        return service.get()
    }

    private fun send(service: PhoneRingService, action: String, startId: Int) {
        service.onStartCommand(Intent(context, PhoneRingService::class.java).setAction(action), 0, startId)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Wartet auf die Arbeit im Hintergrund (appScope), höchstens 5 s. */
    private fun until(what: String, check: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!check()) {
            if (System.nanoTime() > end) fail(what)
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun stopOnPhoneClearsResumeAndSafety() {
        val service = start(ringIntent("a"))
        assertNotNull(PhoneSnooze.load(context))
        SnoozeSafety.save(context, SnoozeSafety.Saved(now, "a", 1))
        send(service, PhoneRingService.ACTION_STOP, 2)
        assertEquals(PhoneRingService.State.Idle, PhoneRingService.ringState.value)
        assertNull(PhoneSnooze.load(context))
        assertNull(SnoozeSafety.load(context))
        // Danach geht ein Befehl an keinen Dienst mehr.
        shadowOf(RuntimeEnvironment.getApplication()).clearStartedServices()
        PhoneRingService.command(context, PhoneRingService.ACTION_STOP)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test
    fun silentStopKeepsSafetyButEndsResume() {
        val service = start(ringIntent("a", fromWatch = true))
        SnoozeSafety.save(context, SnoozeSafety.Saved(now, "a", 1))
        shadowOf(RuntimeEnvironment.getApplication()).clearStartedServices()
        PhoneRingService.command(context, PhoneRingService.ACTION_STOP_SILENT)
        assertEquals(PhoneRingService.ACTION_STOP_SILENT, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService?.action)
        send(service, PhoneRingService.ACTION_STOP_SILENT, 2)
        assertEquals(PhoneRingService.State.Idle, PhoneRingService.ringState.value)
        assertNull(PhoneSnooze.load(context))
        assertEquals("a", SnoozeSafety.load(context)?.alarmId, "stiller Stopp kann ein Schlummern der Uhr sein")
    }

    @Test
    fun watchLedTimeoutArmsSafetyAfterItsSnooze() {
        start(ringIntent("a", fromWatch = true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RingRules.TIMEOUT_MS + 1_000))
        assertEquals(PhoneRingService.State.Idle, PhoneRingService.ringState.value)
        until("Sicherung gestellt") { SnoozeSafety.load(context) != null }
        assertEquals("a", SnoozeSafety.load(context)?.alarmId)
        assertTrue(SnoozeSafety.load(context)!!.at.isAfter(now))
    }

    @Test
    fun ownToneFadesInAndDucksWhileTyping() {
        val harp = WakeTones.ALL.first { it.key == "raw:wake_harp" }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, WakeTones.uri(context, harp)), ShadowMediaPlayer.MediaInfo(28_000, 0))
        runBlocking { context.phoneApp.alarms.upsert(AlarmSpec(id = "tone", hour = 7, minute = 0, sound = harp.key)) }
        val service = start(ringIntent("tone"))
        // Tippen in der Weckaufgabe: leiser, nach einer Pause wieder voll; das Klingeln läuft weiter.
        send(service, PhoneRingService.ACTION_TYPING, 2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40))
        assertTrue(PhoneRingService.ringState.value is PhoneRingService.State.Ringing)
        assertEquals(harp, WakeTones.find("raw:wake_harp"))
        assertNull(WakeTones.find("content://media/alarm/1"))
        assertEquals("content://media/alarm/1", WakeTones.resolve(context, "content://media/alarm/1").toString())
        assertNull(WakeTones.resolve(context, ""))
    }

    @Test
    fun secondRingOfTheSameNightFromTheWatchTakesItsRounds() {
        val service = start(ringIntent("a"))
        // Zweite Weckmeldung derselben Nacht (ohne Zielzeit), jetzt von der Uhr mit ihrer Restzahl: die Uhr führt.
        val again = Intent(context, PhoneRingService::class.java).setAction(PhoneRingService.ACTION_RING)
            .putExtra(PhoneRingService.EXTRA_ALARM_ID, "a").putExtra("fromWatch", true).putExtra(PhoneRingService.EXTRA_SNOOZES_LEFT, 1)
        service.onStartCommand(again, 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        val state = PhoneRingService.ringState.value as PhoneRingService.State.Ringing
        assertTrue(state.fromWatch)
        assertEquals(1, state.snoozesLeft)
    }

    @Test
    fun tonePreviewPlaysAndEndsByItself() {
        val harp = WakeTones.ALL.first { it.key == "raw:wake_harp" }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, WakeTones.uri(context, harp)), ShadowMediaPlayer.MediaInfo(28_000, 0))
        val preview = WakeTonePreview(context)
        preview.play(harp)
        // Nach einigen Sekunden endet das Probehören von selbst; ein zweites «stop» schadet nicht.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        preview.stop()
        // Ein nicht abspielbarer Ton wird nur protokolliert.
        preview.play(WakeTones.ALL.first { it.key == "raw:wake_gong" })
        preview.stop()
    }
}
