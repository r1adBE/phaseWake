package com.phasewake.app.shared

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Abgleich beim Prozessstart entprellt: Ein bestätigter, unveränderter Zustand wird nicht erneut
 * eingereiht und gesendet; ein geänderter immer, ebenso einer, solange unter seinem Schlüssel noch etwas wartet. Die
 * Bestätigung übersteht das Prozessende. Dazu die Anfrage nach dem Stand der Gegenseite höchstens alle 30 Min.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class AckedContentTest {
    /** Gegenseite im Test: immer erreichbar, merkt sich alles Gesendete (Kennung, Inhalt). */
    private class Peer : Outbox.Transport {
        val sent = mutableListOf<Pair<String, String>>()

        override suspend fun nodes(capability: String): List<String> = listOf("uhr")

        override suspend fun send(node: String, path: String, data: ByteArray) {
            sent += assertNotNull(Outbox.open(data))
        }
    }

    private val dir: File = Files.createTempDirectory("acked").toFile()
    private val peer = Peer()
    private var clock = 1_000L

    private fun TestScope.outbox() =
        Outbox(dir, peer, { clock++ + testScheduler.currentTime }, backgroundScope, StandardTestDispatcher(testScheduler))

    /** Datei der bestätigten Stände; eine neue Instanz entspricht einem neuen Prozess. */
    private fun acked() = AckedContent(File(dir, "acked"))

    /** Wie beim Start: einreihen, falls geändert, und das Senden im Hintergrund ablaufen lassen. */
    private suspend fun TestScope.start(box: Outbox, content: AckedContent, body: String): String? =
        box.enqueueIfChanged(content, QueueMessages.KEY_ALARMS, DataLayer.PATH_MSG_ALARMS, DataLayer.CAPABILITY_WATCH, body).also { runCurrent() }

    @Test
    fun confirmedUnchangedStateIsNotSentAgain() = runTest {
        val box = outbox()
        val content = acked()
        val id = assertNotNull(start(box, content, "wecker-a"), "noch nie bestätigt: einreihen")
        assertEquals(listOf(id to "wecker-a"), peer.sent)
        assertTrue(box.ackedAndRemember(content, id))
        // Neuer Prozess (neue Instanzen auf denselben Dateien), gleicher Inhalt: nichts eingereiht, nichts gesendet.
        val again = outbox()
        assertNull(start(again, acked(), "wecker-a"))
        assertTrue(again.pending().isEmpty())
        assertEquals(1, peer.sent.size)
    }

    @Test
    fun changedContentIsSent() = runTest {
        val box = outbox()
        val content = acked()
        assertTrue(box.ackedAndRemember(content, assertNotNull(start(box, content, "wecker-a"))))
        // Inzwischen geändert (z. B. Wecker auf der Uhr verschoben, Nachricht verloren): wird gesendet.
        val id = assertNotNull(start(outbox(), acked(), "wecker-b"))
        assertEquals(id to "wecker-b", peer.sent.last())
        assertEquals(2, peer.sent.size)
    }

    @Test
    fun aWaitingEntryIsAlwaysReplaced() = runTest {
        val box = outbox()
        val content = acked()
        assertTrue(box.ackedAndRemember(content, assertNotNull(start(box, content, "wecker-a"))))
        // Geändert, noch nicht bestätigt; dann zurück auf den bestätigten Stand: Der wartende Eintrag darf nicht bleiben.
        box.enqueue(QueueMessages.KEY_ALARMS, DataLayer.PATH_MSG_ALARMS, DataLayer.CAPABILITY_WATCH, "wecker-b").also { runCurrent() }
        assertNotNull(start(box, content, "wecker-a"))
        assertEquals(listOf("wecker-a"), box.pending().map { it.body })
    }

    @Test
    fun onlyTheConfirmedVersionCounts() = runTest {
        val box = outbox()
        val content = acked()
        val old = assertNotNull(start(box, content, "wecker-a"))
        // Ersetzt, bevor die Bestätigung kam: Die alte Bestätigung entfernt nichts und merkt nichts.
        box.enqueue(QueueMessages.KEY_ALARMS, DataLayer.PATH_MSG_ALARMS, DataLayer.CAPABILITY_WATCH, "wecker-b").also { runCurrent() }
        assertFalse(box.ackedAndRemember(content, old))
        assertFalse(content.matches(QueueMessages.KEY_ALARMS, "wecker-a"))
        assertFalse(content.matches(QueueMessages.KEY_ALARMS, "wecker-b"))
    }

    @Test
    fun nightsAreNotRemembered() = runTest {
        val content = acked()
        content.remember(Outbox.Entry("n1", QueueMessages.KEY_NIGHT + "1-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "nacht", 1L))
        assertFalse(content.matches(QueueMessages.KEY_NIGHT + "1-a", "nacht"))
        assertFalse(File(dir, "acked").exists())
    }

    @Test
    fun unreadableFileSendsAsBefore() = runTest {
        File(dir, "acked").writeText("kaputt\nzz=1\n")
        assertFalse(acked().matches(QueueMessages.KEY_ALARMS, "wecker-a"))
        assertNotNull(start(outbox(), acked(), "wecker-a"))
    }

    @Test
    fun syncRequestAtMostEveryThirtyMinutes() {
        var now = 1_000_000L
        val gate = SyncGate(File(dir, "sync-requested")) { now }
        assertTrue(gate.due(), "noch nie angefragt")
        gate.mark()
        now += SyncGate.MIN_INTERVAL_MS - 1
        assertFalse(SyncGate(File(dir, "sync-requested")) { now }.due(), "neuer Prozess vor Ablauf: nicht erneut")
        now += 1
        assertTrue(gate.due())
        // Zurückgestellte Uhr: gleich wieder.
        assertTrue(SyncGate.due(last = 5_000L, now = 4_000L))
        assertTrue(SyncGate.due(last = null, now = 0L))
    }
}
