package com.phasewake.app.shared

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Warteschlange, Fixes aus dem Audit 071597f: Zustände verfallen nie (AN-2), ein wegen der Wartezeit übergangener Eintrag
 * geht von selbst, sobald sie um ist (AN-3), und gesendet wird ohne die Sperre (AN-4). Die Uhr ist die des Zeitgebers
 * des Tests, das verzögerte Nachsenden läuft also mit ihm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxRetryTest {
    /** Gegenseite im Test: zählt Versuche; solange [gate] offen ist, hängt jedes Senden. */
    private class GatedTransport : Outbox.Transport {
        var gate: CompletableDeferred<Unit>? = null
        var attempts = 0
        val sent = mutableListOf<String>()

        override suspend fun nodes(capability: String): List<String> = listOf("node")

        override suspend fun send(node: String, path: String, data: ByteArray) {
            attempts++
            gate?.await()
            sent += Outbox.open(data)?.second.orEmpty()
        }
    }

    private val dir: File = Files.createTempDirectory("outbox-retry").toFile()
    private val transport = GatedTransport()
    private var calls = 0L

    private fun TestScope.outbox() =
        Outbox(dir, transport, { 1_000L + calls++ + testScheduler.currentTime }, backgroundScope, StandardTestDispatcher(testScheduler))

    private suspend fun TestScope.put(box: Outbox, key: String, body: String): String =
        box.enqueue(key, DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, body).also { runCurrent() }

    @Test
    fun stateKeysNeverExpire() = runTest {
        // Audit AN-2: Nach zwei Wochen ohne Gegenseite fiel auch der Stand der Wecker weg; die Änderung ging nie an.
        val box = Outbox(dir, object : Outbox.Transport {
            override suspend fun nodes(capability: String): List<String> = emptyList()
            override suspend fun send(node: String, path: String, data: ByteArray) = Unit
        }, { 1_000L + calls++ + testScheduler.currentTime }, backgroundScope, StandardTestDispatcher(testScheduler))
        for (key in listOf("night/alt", "recording/alt", QueueMessages.KEY_ALARMS, QueueMessages.KEY_LEARNING, QueueMessages.KEY_SLEEP_GOAL)) put(box, key, key)
        calls += Outbox.MAX_AGE_MS + 1
        put(box, "night/neu", "neu")
        assertEquals(
            listOf(QueueMessages.KEY_ALARMS, QueueMessages.KEY_LEARNING, QueueMessages.KEY_SLEEP_GOAL, "night/neu"),
            box.pending().map { it.key },
        )
        assertTrue(Outbox.expires("night/1-a") && Outbox.expires("recording/5"))
        assertFalse(Outbox.expires(QueueMessages.KEY_ALARMS) || Outbox.expires(QueueMessages.KEY_HOLIDAYS) || Outbox.expires(QueueMessages.KEY_ONSET_SHIFT))
    }

    @Test
    fun skippedEntryIsSentWhenTheWaitIsOver() = runTest {
        // Audit AN-3: Übergangen wegen der Wartezeit blieb ein Eintrag liegen, bis zufällig ein neuer Anlass kam.
        val box = outbox()
        put(box, "night/1-a", "n1")
        assertEquals(listOf("n1"), transport.sent)
        // Mehrere Anlässe kurz danach: übergangen, ein einziges Nachsenden geplant.
        repeat(3) { box.flush() }
        advanceTimeBy(Outbox.RESEND_AFTER_MS / 2)
        runCurrent()
        assertEquals(1, transport.sent.size, "vor Ablauf der Wartezeit nichts")
        advanceTimeBy(Outbox.RESEND_AFTER_MS)
        runCurrent()
        assertEquals(listOf("n1", "n1"), transport.sent, "nach der Wartezeit von selbst, einmal")
        // Danach nichts mehr übergangen: kein weiteres Nachsenden ohne Anlass.
        advanceTimeBy(10 * Outbox.RESEND_AFTER_MS)
        runCurrent()
        assertEquals(2, transport.sent.size)
        assertTrue(box.acked(box.pending().single().id))
    }

    @Test
    fun sendingDoesNotHoldTheLock() = runTest {
        // Audit AN-4: Ein hängendes Senden hielt die Sperre; Bestätigungen und Einreihen warteten bis zu 15 s je Schritt.
        val box = outbox()
        val gate = CompletableDeferred<Unit>()
        transport.gate = gate
        val id = put(box, "night/1-a", "n1")
        assertEquals(1, transport.attempts, "Senden läuft und hängt")
        // Ein weiterer Anlass sendet denselben Eintrag nicht ein zweites Mal gleichzeitig.
        assertEquals(1, box.flush())
        assertEquals(1, transport.attempts)
        // Bestätigen und Lesen gehen trotzdem.
        assertTrue(box.acked(id))
        assertTrue(box.pending().isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("n1"), transport.sent)
        assertTrue(box.pending().isEmpty(), "bestätigt bleibt weg")
        assertEquals(0, box.flush())
    }

    @Test
    fun leftoversAreRemoved() = runTest {
        // Audit SP-8: Ein nach einem Stromausfall leerer Eintrag und ein liegengebliebenes «.tmp» blieben für immer liegen.
        val box = outbox()
        put(box, "night/1-a", "n1")
        val empty = File(dir, "e-6e696768742f32").apply { writeText("") }
        val half = File(dir, "e-6e696768742f33").apply { writeText("v1\nid\nnight/3") }
        val tmp = File(dir, "e-6e696768742f34.tmp").apply { writeText("v1\nid") }
        val other = File(dir, "migrated").apply { writeText("2") }
        box.flush()
        assertFalse(empty.exists() || half.exists() || tmp.exists(), "Reste entfernt")
        assertTrue(other.exists(), "andere Dateien bleiben")
        assertEquals(listOf("n1"), box.pending().map { it.body })
        // Geschrieben wird weiter über «.tmp» und Umbenennen; danach liegt keines mehr.
        put(box, "night/1-a", "n1b")
        assertEquals(listOf("n1b"), box.pending().map { it.body })
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }
}
