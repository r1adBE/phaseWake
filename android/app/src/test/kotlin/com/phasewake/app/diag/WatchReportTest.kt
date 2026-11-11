package com.phasewake.app.diag

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.phasewake.app.OfflineApp
import com.phasewake.app.core.DiagDevice
import com.phasewake.app.core.DiagEntry
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.DiagLog
import com.phasewake.app.core.DiagReport
import com.phasewake.app.core.DiagReports
import com.phasewake.app.phoneApp
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.Outbox
import com.phasewake.app.shared.PeerLimit
import com.phasewake.app.sync.PhoneListenerService
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Diagnose-Bericht der Uhr am Handy (Fehler beim Gerätetest: keine Zeilen der Uhr, Version und nächster Wecker
 * «unbekannt»): Ein voller Bericht (500 Einträge à 200 Zeichen) ist grösser als eine Nachricht der Wear-Dienste und ging
 * verloren. Er geht jetzt über einen Kanal ([Outbox.sendOnce]) und kommt vollständig an; ein kleiner Bericht weiterhin als
 * Nachricht. Ohne Antwort der Uhr steht «watch report missing» im Protokoll des Handys.
 *
 * Die Wear-Dienste antworten unter Robolectric nie; die Gegenseite ist darum ein [Outbox.Transport], der wie
 * [Outbox.WearTransport] nach Grösse wählt und den Weg des Handys nimmt: Nachricht an
 * [PhoneListenerService.onMessageReceived], Kanal gelesen wie [com.phasewake.app.shared.WearSync.readChannel]
 * ([PeerLimit.read]) und an [PhoneListenerService.handleChannel].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineApp::class)
class WatchReportTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = context.phoneApp as OfflineApp
    private val service by lazy { Robolectric.buildService(PhoneListenerService::class.java).get() }
    private val since = Instant.now()

    /** Nachricht der Uhr unter [path] mit Inhalt [data]. */
    private class Event(private val path: String, private val data: ByteArray) : MessageEvent {
        override fun getRequestId(): Int = 0
        override fun getPath(): String = path
        override fun getData(): ByteArray = data
        override fun getSourceNodeId(): String = "watch"
    }

    /** Uhr → Handy wie über die Wear-Dienste; zählt, welcher Weg genommen wurde. */
    private inner class PhoneSide : Outbox.Transport {
        var messages = 0
        var channels = 0

        override suspend fun nodes(capability: String): List<String> = listOf("phone")

        override suspend fun send(node: String, path: String, data: ByteArray) {
            if (Outbox.fitsMessage(data)) {
                messages++
                service.onMessageReceived(Event(path, data))
            } else {
                channels++
                val read = assertNotNull(PeerLimit.read(ByteArrayInputStream(data)))
                service.handleChannel("watch", path, read)
            }
        }
    }

    @After
    fun cleanUp() = app.appScope.cancel()

    /** Voller Bericht: [DiagLog.MAX_ENTRIES] Einträge mit dem längsten Text (auch Zeichen mit mehreren Bytes). */
    private fun fullReport(): DiagReport {
        val start = Instant.now().minus(Duration.ofDays(6)).toEpochMilli()
        val entries = List(DiagLog.MAX_ENTRIES) { i ->
            val detail = ("$i record ä–ö phase light sleep window ").repeat(10).take(DiagLog.MAX_DETAIL).trim()
            DiagEntry(Instant.ofEpochMilli(start + i * 60_000L), DiagDevice.WATCH, DiagKind.entries[i % DiagKind.entries.size], detail)
        }
        val status = listOf("version" to "16.2.2 (82)", "next" to "2026-11-12T05:30:00Z – 2026-11-12T06:00:00Z", "battery" to "64", "exact" to "true", "notifications" to "true")
        return DiagReport(status, entries)
    }

    @Test
    fun fullReportArrivesOverAChannel() = runBlocking {
        val report = fullReport()
        assertTrue(report.entries.all { it.detail.length in (DiagLog.MAX_DETAIL - 1)..DiagLog.MAX_DETAIL })
        val data = DiagReports.encode(report).encodeToByteArray()
        // Über der Grenze einer Nachricht: Auf dem früheren Weg ging er verloren.
        assertTrue(data.size > Outbox.MAX_MESSAGE_BYTES, "Grösse: ${data.size}")
        val phone = PhoneSide()
        assertEquals(1, Outbox.sendOnce(phone, DataLayer.CAPABILITY_PHONE, DataLayer.PATH_DIAG_LOG, data))
        assertEquals(0, phone.messages)
        assertEquals(1, phone.channels)
        assertEquals(report, WatchReport.report.value)
    }

    @Test
    fun smallReportArrivesAsMessage() = runBlocking {
        val report = DiagReport(listOf("version" to "16.2.2 (83)"), fullReport().entries.take(3))
        val phone = PhoneSide()
        assertEquals(1, Outbox.sendOnce(phone, DataLayer.CAPABILITY_PHONE, DataLayer.PATH_DIAG_LOG, DiagReports.encode(report).encodeToByteArray()))
        assertEquals(1, phone.messages)
        assertEquals(0, phone.channels)
        assertEquals(report, WatchReport.report.value)
    }

    @Test
    fun noPhoneOrFailureCountsAsNotSent() = runBlocking {
        val none = object : Outbox.Transport {
            override suspend fun nodes(capability: String): List<String> = emptyList()
            override suspend fun send(node: String, path: String, data: ByteArray) {
                error("nicht erwartet")
            }
        }
        assertEquals(0, Outbox.sendOnce(none, DataLayer.CAPABILITY_PHONE, DataLayer.PATH_DIAG_LOG, ByteArray(1)))
        val failing = object : Outbox.Transport {
            override suspend fun nodes(capability: String): List<String> = listOf("phone")
            override suspend fun send(node: String, path: String, data: ByteArray) {
                error("Verbindung weg")
            }
        }
        assertEquals(0, Outbox.sendOnce(failing, DataLayer.CAPABILITY_PHONE, DataLayer.PATH_DIAG_LOG, ByteArray(1)))
    }

    @Test
    fun missingAnswerIsLogged() = runBlocking {
        // Keine Uhr antwortet (Wear-Dienste unter Robolectric): nach der Frist `null` und eine Zeile im Protokoll.
        assertNull(WatchReport.fetch(app, timeoutMs = 300))
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (app.diag.entries().none { !it.at.isBefore(since.minusSeconds(1)) && it.kind == DiagKind.SYNC && it.detail == "watch report missing" }) {
            check(System.nanoTime() < until) { "nicht im Protokoll: ${app.diag.entries().takeLast(5)}" }
            Thread.sleep(5)
        }
    }
}
