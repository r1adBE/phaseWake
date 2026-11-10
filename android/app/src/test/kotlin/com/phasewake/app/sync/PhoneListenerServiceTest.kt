package com.phasewake.app.sync

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.phasewake.app.OfflineApp
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Codec
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.WakeReason
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.onsetShift
import com.phasewake.app.shared.AlarmSnapshot
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.OnsetShift
import com.phasewake.app.shared.Outbox
import com.phasewake.app.shared.QueueMessages
import com.phasewake.app.shared.RecordEnd
import com.phasewake.app.shared.RecordSync
import com.phasewake.app.shared.RingMessage
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Empfang am Handy ([PhoneListenerService], Audit F-02): Einträge der Warteschlange der Uhr ([PhoneListenerService.handleQueued])
 * übernehmen Wecker, Nächte, Aufzeichnungen, den Stand von «Nacht aufzeichnen» und die frühere Zielzeit; dieselbe Nacht
 * zweimal ändert nichts, Unlesbares wird übergangen, ein Eintrag ohne Kennung gar nicht verarbeitet. Die Bestätigung der
 * Uhr entfernt genau den gesendeten Eintrag; ein verspätetes «stop» ändert nichts.
 *
 * Ohne Wear-Dienste ([OfflineApp]): Ihre Aufgaben enden unter Robolectric nie; darum geht der Test an der Bestätigung des
 * Empfangs vorbei (geprüft in :shared, `OutboxTest`) und nur über Wege ohne Antwort der Uhr.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineApp::class)
class PhoneListenerServiceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = context.phoneApp as OfflineApp
    private val outbox get() = Outbox.forContext(context)
    private val service by lazy { Robolectric.buildService(PhoneListenerService::class.java).get() }
    private val since = Instant.now()

    /** Nachricht der Uhr unter [path] mit Inhalt [data]. */
    private class Event(private val path: String, private val data: ByteArray = ByteArray(0)) : MessageEvent {
        override fun getRequestId(): Int = 0
        override fun getPath(): String = path
        override fun getData(): ByteArray = data
        override fun getSourceNodeId(): String = "watch"
    }

    /** Eintrag der Warteschlange der Uhr unter [path] mit Text [body], wie nach dem Öffnen der Nachricht. */
    private fun queued(path: String, body: String) = runBlocking { service.handleQueued(path, body) }

    @Before
    fun setUp() = runBlocking {
        File(context.filesDir, "recordings").deleteRecursively()
        app.nights.replaceAll(emptyList())
        app.alarms.replaceAll(emptyList())
        app.recordStatus.value = null
    }

    @After
    fun cleanUp() = app.appScope.cancel()

    private fun diag() = app.diag.entries().filter { !it.at.isBefore(since.minusSeconds(1)) }.map { it.detail }

    /** Wartet, bis das Diagnose-Protokoll (schreibt im Hintergrund) eine Zeile hat, für die [match] gilt. */
    private fun awaitDiag(match: (String) -> Boolean) {
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (diag().none(match)) {
            check(System.nanoTime() < until) { "nicht im Protokoll: ${diag()}" }
            Thread.sleep(5)
        }
    }

    private fun night(id: String, start: Instant) =
        NightRecord(id, start, start.plus(Duration.ofMinutes(30)), start.plus(Duration.ofMinutes(20)), WakeReason.LIGHT_SLEEP, 0, emptyList())

    @Test
    fun newerAlarmsFromTheWatchAreTakenOver() = runBlocking {
        val alarms = listOf(AlarmSpec(id = "w", hour = 6, minute = 45))
        queued(DataLayer.PATH_MSG_ALARMS, QueueMessages.encodeAlarms(AlarmSnapshot(alarms, System.currentTimeMillis() + 60_000)))
        assertEquals(alarms, app.alarms.current().alarms)
        awaitDiag { it == "from watch" }
        // Ein älterer Stand der Uhr (vor der letzten Änderung am Handy) ändert nichts.
        queued(DataLayer.PATH_MSG_ALARMS, QueueMessages.encodeAlarms(AlarmSnapshot(emptyList(), 1L)))
        assertEquals(alarms, app.alarms.current().alarms)
    }

    @Test
    fun nightIsStoredOnce() = runBlocking {
        val night = night("a", Instant.parse("2026-11-09T05:30:00Z"))
        queued(DataLayer.PATH_MSG_NIGHT, Codec.encodeNight(night))
        // Dieselbe Nacht nochmals (Bestätigung verloren, die Uhr sendet erneut): nur einmal im Verlauf.
        queued(DataLayer.PATH_MSG_NIGHT, Codec.encodeNight(night))
        assertEquals(listOf(night), app.nights.load())
        // Die Uhr hat diese Nacht übernommen: Zweitwecker dieser Nacht ausgesetzt.
        awaitDiag { it == "phone backup paused" }
    }

    @Test
    fun finishedRecordingIsStoredUnfinishedIsNot() = runBlocking {
        val start = Instant.parse("2026-11-08T22:00:00Z")
        val finished = NightRecording(start, List(4) { Epoch(0.02, 55.0) }, start.plusSeconds(120), RecordEnd.WAKE)
        queued(DataLayer.PATH_MSG_RECORDING, NightRecording.encode(finished.copy(start = start.minusSeconds(3_600), end = null, endReason = null)))
        queued(DataLayer.PATH_MSG_RECORDING, NightRecording.encode(finished))
        assertEquals(listOf(start), app.recordings.load().map { it.start })
        awaitDiag { it == "recording received epochs=4 end=${RecordEnd.WAKE.code}" }
    }

    @Test
    fun recordStatusIsShown() {
        val running = Instant.parse("2026-11-09T21:00:00Z")
        queued(DataLayer.PATH_MSG_RECORD_STATUS, running.toEpochMilli().toString())
        assertEquals(RecordSync.RecordStatus(running), app.recordStatus.value)
        // 0 = läuft nicht, Unlesbares ändert nichts.
        queued(DataLayer.PATH_MSG_RECORD_STATUS, "0")
        assertEquals(RecordSync.RecordStatus(null), app.recordStatus.value)
        queued(DataLayer.PATH_MSG_RECORD_STATUS, "bald")
        assertEquals(RecordSync.RecordStatus(null), app.recordStatus.value)
    }

    @Test
    fun earlierTargetOfTheWatchIsKept() = runBlocking {
        // Eingestellt in 8 Std., die Uhr weckt 20 Min. früher (Einschlaf-Modus); neuer als jede frühere Meldung.
        val original = System.currentTimeMillis() + Duration.ofHours(8).toMillis()
        val shift = OnsetShift("a", original, original - Duration.ofMinutes(20).toMillis(), System.currentTimeMillis())
        queued(DataLayer.PATH_MSG_ONSET_SHIFT, shift.encode())
        assertEquals(shift, app.backup.onsetShift())
    }

    @Test
    fun unreadableEntriesAreSkipped() = runBlocking {
        queued(DataLayer.PATH_MSG_NIGHT, "keine Nacht")
        queued(DataLayer.PATH_MSG_RECORDING, "keine Aufzeichnung")
        queued(DataLayer.PATH_MSG_ALARMS, "x\nkaputt")
        queued(DataLayer.PATH_MSG_ONSET_SHIFT, "")
        queued("/phasewake/m/unbekannt", "neuer Inhalt einer neueren Uhr")
        // Ohne Kennung (leer oder ohne Zeilenumbruch): gar nicht verarbeitet, auch nicht bestätigt.
        service.onMessageReceived(Event(DataLayer.PATH_MSG_NIGHT, "ohne-kennung".encodeToByteArray()))
        service.onMessageReceived(Event(DataLayer.PATH_MSG_NIGHT))
        assertTrue(app.nights.load().isEmpty())
        assertTrue(app.recordings.load().isEmpty())
        assertTrue(app.alarms.current().alarms.isEmpty())
    }

    @Test
    fun ackRemovesOnlyTheSentEntry() = runBlocking {
        outbox.drop("")
        // Einreihen versucht gleich zu senden; ohne Wear-Dienste bleibt der Eintrag liegen.
        outbox.enqueue(QueueMessages.KEY_ALARMS, DataLayer.PATH_MSG_ALARMS, DataLayer.CAPABILITY_WATCH, "1\n")
        val sent = outbox.pending().single()
        // Bestätigung eines älteren Standes (inzwischen ersetzt) oder leer: Eintrag bleibt.
        service.onMessageReceived(Event(DataLayer.PATH_ACK, "veraltet".encodeToByteArray()))
        service.onMessageReceived(Event(DataLayer.PATH_ACK))
        assertEquals(listOf(sent.id), outbox.pending().map { it.id })
        service.onMessageReceived(Event(DataLayer.PATH_ACK, sent.id.encodeToByteArray()))
        assertTrue(outbox.pending().isEmpty())
    }

    @Test
    fun staleStopChangesNothing() {
        val old = System.currentTimeMillis() - Duration.ofMinutes(10).toMillis()
        service.onMessageReceived(Event(DataLayer.PATH_STOP, RingMessage("a", old, old).encode()))
        awaitDiag { it == "from watch (stale, ignored)" }
    }

    @Test
    fun outdatedWatchMessagesAreIgnored() {
        // Veraltete Uhr-App: nur die Kennung, ohne Sendezeit bzw. ohne Schlummer-Runden – kein Rückfall.
        service.onMessageReceived(Event(DataLayer.PATH_STOP, "a".encodeToByteArray()))
        awaitDiag { it == "from watch (incomplete, ignored)" }
        val now = System.currentTimeMillis()
        service.onMessageReceived(Event(DataLayer.PATH_RING, RingMessage("a", now, now).encode()))
        awaitDiag { it == "watch rang (incomplete, ignored)" }
    }

    @Test
    fun testAlarmReceivedByTheWatchIsLogged() {
        service.onMessageReceived(Event(DataLayer.PATH_TEST_ACK, "test-1".encodeToByteArray()))
        awaitDiag { it == "watch received" }
    }
}
