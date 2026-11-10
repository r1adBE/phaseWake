package com.phasewake.app.shared

import android.net.Uri
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Warteschlange statt Datenpaketen (Audit C2): Einträge bleiben lokal, bis die Gegenseite bestätigt; ohne Verbindung
 * oder bei einem Fehler wird später erneut gesendet; gleicher Schlüssel ersetzt; einmalige Umstellung vom Data Layer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OutboxTest {
    /** Gegenseite im Test: erreichbar oder nicht, Fehler beim Senden, alles Gesendete. */
    private class FakeTransport : Outbox.Transport {
        var reachable = true
        var failing = false
        val sent = mutableListOf<Triple<String, String, ByteArray>>()

        override suspend fun nodes(capability: String): List<String> = if (reachable) listOf("node-$capability") else emptyList()

        override suspend fun send(node: String, path: String, data: ByteArray) {
            check(!failing) { "Verbindung weg" }
            sent += Triple(node, path, data)
        }

        /** Kennung der zuletzt gesendeten Nachricht. */
        fun lastId(): String = assertNotNull(Outbox.open(sent.last().third)).first
    }

    private val dir: File = Files.createTempDirectory("outbox").toFile()
    private val transport = FakeTransport()
    private var clock = 1_000L
    private fun outbox() = Outbox(dir, transport) { clock++ }

    @Test
    fun sendsAndKeepsUntilAcknowledged() = runTest {
        val box = outbox()
        box.enqueue("night/1-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "nacht")
        assertEquals(1, transport.sent.size)
        val (node, path, data) = transport.sent.single()
        assertEquals("node-${DataLayer.CAPABILITY_PHONE}", node)
        assertEquals(DataLayer.PATH_MSG_NIGHT, path)
        assertEquals("nacht", Outbox.open(data)?.second)
        // Ohne Bestätigung bleibt er und wird beim nächsten Anlass erneut gesendet.
        assertEquals(1, box.flush())
        assertEquals(2, transport.sent.size)
        assertTrue(box.acked(transport.lastId()))
        assertEquals(0, box.flush())
        assertEquals(2, transport.sent.size)
        assertFalse(box.acked("unbekannt"))
    }

    @Test
    fun retriesAfterNoConnectionOrFailure() = runTest {
        transport.reachable = false
        val box = outbox()
        box.enqueue("night/1-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "eins")
        assertTrue(transport.sent.isEmpty())
        transport.reachable = true
        transport.failing = true
        assertEquals(1, box.flush(), "Fehler beim Senden: bleibt")
        transport.failing = false
        assertEquals(1, box.flush())
        assertEquals("eins", Outbox.open(transport.sent.single().third)?.second)
    }

    @Test
    fun survivesARestartOfTheProcess() = runTest {
        transport.reachable = false
        outbox().enqueue("recording/5", DataLayer.PATH_MSG_RECORDING, DataLayer.CAPABILITY_PHONE, "aufzeichnung")
        // Neue Instanz auf demselben Ordner (Prozess neu): Eintrag ist noch da.
        val again = outbox()
        assertEquals(listOf("recording/5"), again.pending().map { it.key })
        transport.reachable = true
        again.flush()
        assertTrue(again.acked(transport.lastId()))
        assertTrue(again.pending().isEmpty())
    }

    @Test
    fun sameKeyReplacesAndAnOldAckKeepsTheNewer() = runTest {
        val box = outbox()
        box.enqueue("alarms", DataLayer.PATH_MSG_ALARMS, DataLayer.CAPABILITY_WATCH, "alt")
        val oldId = transport.lastId()
        box.enqueue("alarms", DataLayer.PATH_MSG_ALARMS, DataLayer.CAPABILITY_WATCH, "neu")
        assertEquals(listOf("neu"), box.pending().map { it.body })
        // Bestätigung des alten Standes entfernt den neuen nicht.
        assertFalse(box.acked(oldId))
        assertEquals(1, box.pending().size)
        // Verschiedene Nächte bleiben getrennt, älteste zuerst.
        box.enqueue("night/1-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "n1")
        box.enqueue("night/2-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "n2")
        assertEquals(listOf("alarms", "night/1-a", "night/2-a"), box.pending().map { it.key })
    }

    @Test
    fun dropRemovesOnlyOlderEntriesWithThePrefix() = runTest {
        transport.reachable = false
        val box = outbox()
        box.enqueue("night/1-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "vorher")
        val cut = clock
        box.enqueue("night/2-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "nachher")
        box.enqueue("alarms", DataLayer.PATH_MSG_ALARMS, DataLayer.CAPABILITY_PHONE, "wecker")
        box.drop(QueueMessages.KEY_NIGHT, before = cut)
        assertEquals(listOf("night/2-a", "alarms"), box.pending().map { it.key })
    }

    @Test
    fun migrationQueuesLegacyContentThenDeletesOnce() = runTest {
        transport.reachable = false
        val box = outbox()
        val night = Uri.parse("wear://uhr/phasewake/night/1-a")
        val alarms = Uri.parse("wear://uhr/phasewake/alarms")
        val deleted = mutableListOf<Uri>()
        val entry = Outbox.Entry("id-1", "night/1-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "nacht", 1L)
        val legacy = listOf(Outbox.Legacy(night, entry), Outbox.Legacy(alarms))
        assertTrue(box.migrateOnce({ legacy }, { deleted += it }))
        assertEquals(listOf(night, alarms), deleted)
        // Inhalt vor dem Löschen eingereiht: geht nicht verloren, auch ohne Verbindung.
        assertEquals(listOf("nacht"), box.pending().map { it.body })
        // Nur einmal.
        assertFalse(box.migrateOnce({ legacy }, { deleted += it }))
        assertEquals(2, deleted.size)
    }

    @Test
    fun failedMigrationRunsAgain() = runTest {
        val box = outbox()
        var attempts = 0
        val item = Outbox.Legacy(Uri.parse("wear://uhr/phasewake/alarms"))
        assertFalse(box.migrateOnce({ listOf(item) }, { attempts++; error("Wear-Dienste fehlen") }))
        assertTrue(box.migrateOnce({ listOf(item) }, { attempts++ }))
        assertEquals(2, attempts)
    }

    @Test
    fun envelopeAndMessageFormats() {
        assertEquals("id" to "a\nb", Outbox.open(Outbox.wrap("id", "a\nb")))
        assertNull(Outbox.open("ohne-kennung".encodeToByteArray()))
        assertNull(Outbox.open(null))
        val snapshot = AlarmSnapshot(listOf(com.phasewake.app.core.AlarmSpec(id = "a", hour = 7, minute = 0), com.phasewake.app.core.AlarmSpec(id = "b", hour = 8, minute = 30)), 42L)
        assertEquals(snapshot, QueueMessages.decodeAlarms(QueueMessages.encodeAlarms(snapshot)))
        assertNull(QueueMessages.decodeAlarms("kaputt"))
        assertEquals(450 to 7L, QueueMessages.decodeStamped("450|7"))
        assertNull(QueueMessages.decodeStamped("450"))
        val learning = LearningSnapshot(true, 0.02, null, 9L)
        assertEquals(learning, LearningSnapshot.decode(LearningSnapshot.encode(learning)))
        assertEquals(RecordSync.RecordStatus(null), RecordSync.decodeStatus("0"))
        assertEquals(RecordSync.RecordStatus(java.time.Instant.ofEpochMilli(5)), RecordSync.decodeStatus("5"))
    }

    @Test
    fun receiveHandlesBeforeAcknowledging() = runBlocking {
        // Ohne Wear-Dienste (Testplattform): Verarbeiten zuerst, Bestätigen danach scheitert still; darum mit Frist.
        val sync = WearSync(RuntimeEnvironment.getApplication(), DataLayer.CAPABILITY_PHONE)
        var got: String? = null
        withTimeoutOrNull(2_000L) { sync.receive("node", Outbox.wrap("id-1", "inhalt")) { got = it } }
        assertEquals("inhalt", got)
        var called = false
        withTimeoutOrNull(2_000L) { sync.receive("node", "ohne-kennung".encodeToByteArray()) { called = true } }
        assertFalse(called, "unlesbar: nicht verarbeiten")
        // Fehler beim Verarbeiten: kein Absturz (und keine Bestätigung).
        withTimeoutOrNull(2_000L) { sync.receive("node", Outbox.wrap("id-2", "x")) { error("Speicher voll") } }
        // Auf der Uhr ist die Gegenseite das Handy: nie «ältere Uhr».
        assertFalse(withTimeoutOrNull(2_000L) { sync.legacyWatch() } ?: false)
        withTimeoutOrNull(2_000L) { sync.acked("unbekannt".encodeToByteArray()) }
        withTimeoutOrNull(2_000L) { sync.dropHistory(0L) }
        Unit
    }
}
