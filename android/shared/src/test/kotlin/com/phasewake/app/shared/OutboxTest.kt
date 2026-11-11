package com.phasewake.app.shared

import android.net.Uri
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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
@OptIn(ExperimentalCoroutinesApi::class)
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

    /**
     * Warteschlange im Takt des Tests: Dateizugriffe und Senden im Hintergrund auf dessen Zeitgeber, die Uhr ist [clock]
     * (jeder Aufruf eine Millisekunde später) plus die Zeit des Zeitgebers (verzögertes Nachsenden, Audit AN-3).
     */
    private fun TestScope.outbox() =
        Outbox(dir, transport, { clock++ + testScheduler.currentTime }, backgroundScope, StandardTestDispatcher(testScheduler))

    /** Einreihen und das Senden im Hintergrund gleich ablaufen lassen (ohne verzögertes Nachsenden). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.put(box: Outbox, key: String, path: String, capability: String, body: String): String =
        box.enqueue(key, path, capability, body).also { runCurrent() }

    /** Lässt die Wartezeit bis zum erneuten Senden verstreichen ([Outbox.RESEND_AFTER_MS]). */
    private fun later() {
        clock += Outbox.RESEND_AFTER_MS
    }

    @Test
    fun sendsAndKeepsUntilAcknowledged() = runTest {
        val box = outbox()
        put(box, "night/1-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "nacht")
        assertEquals(1, transport.sent.size)
        val (node, path, data) = transport.sent.single()
        assertEquals("node-${DataLayer.CAPABILITY_PHONE}", node)
        assertEquals(DataLayer.PATH_MSG_NIGHT, path)
        assertEquals("nacht", Outbox.open(data)?.second)
        // Ohne Bestätigung bleibt er; ein Anlass gleich danach sendet ihn nicht noch einmal (Audit A-1) ...
        assertEquals(1, box.flush())
        assertEquals(1, transport.sent.size)
        // ... erst nach der Wartezeit.
        later()
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
        put(box, "night/1-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "eins")
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
        put(outbox(), "recording/5", DataLayer.PATH_MSG_RECORDING, DataLayer.CAPABILITY_PHONE, "aufzeichnung")
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
        put(box, "alarms", DataLayer.PATH_MSG_ALARMS, DataLayer.CAPABILITY_WATCH, "alt")
        val oldId = transport.lastId()
        put(box, "alarms", DataLayer.PATH_MSG_ALARMS, DataLayer.CAPABILITY_WATCH, "neu")
        assertEquals(listOf("neu"), box.pending().map { it.body })
        // Bestätigung des alten Standes entfernt den neuen nicht.
        assertFalse(box.acked(oldId))
        assertEquals(1, box.pending().size)
        // Verschiedene Nächte bleiben getrennt, älteste zuerst.
        put(box, "night/1-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "n1")
        put(box, "night/2-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "n2")
        assertEquals(listOf("alarms", "night/1-a", "night/2-a"), box.pending().map { it.key })
    }

    @Test
    fun dropRemovesOnlyOlderEntriesWithThePrefix() = runTest {
        transport.reachable = false
        val box = outbox()
        put(box, "night/1-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "vorher")
        val cut = clock
        put(box, "night/2-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "nachher")
        put(box, "alarms", DataLayer.PATH_MSG_ALARMS, DataLayer.CAPABILITY_PHONE, "wecker")
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
    fun enqueueSendsOnlyTheNewEntryAndFlushWaitsPerEntry() = runTest {
        // Audit A-1 (Doppelte am Gerät): Jeder Anlass schickte alles Unbestätigte erneut.
        val box = outbox()
        put(box, "night/1-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "n1")
        put(box, "night/2-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "n2")
        put(box, "recording/3", DataLayer.PATH_MSG_RECORDING, DataLayer.CAPABILITY_PHONE, "r3")
        assertEquals(listOf("n1", "n2", "r3"), transport.sent.map { Outbox.open(it.third)?.second }, "je Eintrag einmal")
        // Viele Anlässe kurz hintereinander (Nachrichten der Gegenseite): nichts doppelt.
        repeat(5) { box.flush() }
        assertEquals(3, transport.sent.size)
        later()
        box.flush()
        assertEquals(6, transport.sent.size, "nach der Wartezeit jeder unbestätigte einmal")
        // Ohne Verbindung gilt ein Eintrag nicht als gesendet: beim nächsten Anlass gleich wieder.
        transport.reachable = false
        put(box, "night/4-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "n4")
        transport.reachable = true
        box.flush()
        assertEquals(listOf("n4"), transport.sent.drop(6).map { Outbox.open(it.third)?.second })
    }

    @Test
    fun resendRule() {
        assertTrue(Outbox.resendDue(null, 5L))
        assertFalse(Outbox.resendDue(1_000L, 1_000L + Outbox.RESEND_AFTER_MS - 1))
        assertTrue(Outbox.resendDue(1_000L, 1_000L + Outbox.RESEND_AFTER_MS))
        assertTrue(Outbox.resendDue(1_000L, 999L), "Uhr zurückgestellt: gleich")
    }

    @Test
    fun queueIsBoundedByAgeAndCountPerKind() = runTest {
        // Audit A-8: Ohne Gegenseite wuchs die Warteschlange ohne Grenze.
        transport.reachable = false
        val box = outbox()
        put(box, "night/old", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "alt")
        put(box, "alarms", DataLayer.PATH_MSG_ALARMS, DataLayer.CAPABILITY_PHONE, "wecker")
        clock += Outbox.MAX_AGE_MS + 1
        put(box, "night/new", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "neu")
        assertEquals(listOf("alarms", "night/new"), box.pending().map { it.key }, "Nacht älter als 14 Tage: weg, Zustand bleibt (AN-2)")
        repeat(Outbox.MAX_PER_KIND + 5) { put(box, "night/$it", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "n$it") }
        put(box, "recording/1", DataLayer.PATH_MSG_RECORDING, DataLayer.CAPABILITY_PHONE, "r")
        val pending = box.pending()
        assertEquals(Outbox.MAX_PER_KIND, pending.count { it.key.startsWith(QueueMessages.KEY_NIGHT) })
        assertEquals("night/${Outbox.MAX_PER_KIND + 4}", pending.last { it.key.startsWith(QueueMessages.KEY_NIGHT) }.key, "die neuesten bleiben")
        assertTrue(pending.none { it.key == "night/new" || it.key == "night/0" })
        assertEquals(1, pending.count { it.key == "recording/1" }, "andere Art unberührt")
        assertEquals("night/", Outbox.kindOf("night/1-a"))
        assertEquals("alarms", Outbox.kindOf("alarms"))
    }

    @Test
    fun dropIfMatchesTheContent() = runTest {
        transport.reachable = false
        val box = outbox()
        put(box, "night/1-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "weg")
        put(box, "night/2-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, "bleibt")
        box.dropIf(QueueMessages.KEY_NIGHT) { it.body == "weg" }
        assertEquals(listOf("bleibt"), box.pending().map { it.body })
    }

    @Test
    fun historyClearDropsQueuedNightsAndRecordingsByTheirEnd() = runTest {
        // Audit D-02: nach dem Inhalt (Ende bis zum Löschen), nicht nur nach dem Zeitpunkt des Einreihens.
        val cut = java.time.Instant.parse("2026-11-10T12:00:00Z")
        fun night(woke: String) = com.phasewake.app.core.NightRecord(
            "a", java.time.Instant.parse(woke).minusSeconds(1_800), java.time.Instant.parse(woke), java.time.Instant.parse(woke),
            com.phasewake.app.core.WakeReason.DEADLINE, 0, emptyList(),
        )
        val before = com.phasewake.app.core.Codec.encodeNight(night("2026-11-10T06:00:00Z"))
        val after = com.phasewake.app.core.Codec.encodeNight(night("2026-11-11T06:00:00Z"))
        assertTrue(QueueMessages.nightEndsBy(before, cut.toEpochMilli()))
        assertFalse(QueueMessages.nightEndsBy(after, cut.toEpochMilli()))
        assertFalse(QueueMessages.nightEndsBy("kaputt", cut.toEpochMilli()))
        val start = java.time.Instant.parse("2026-11-09T22:00:00Z")
        val done = NightRecording(start, emptyList(), start.plusSeconds(60), RecordEnd.WAKE)
        assertTrue(QueueMessages.recordingEndsBy(NightRecording.encode(done), cut.toEpochMilli()))
        assertFalse(QueueMessages.recordingEndsBy(NightRecording.encode(done.copy(end = null, endReason = null)), cut.toEpochMilli()), "läuft noch")
        assertFalse(QueueMessages.recordingEndsBy(NightRecording.encode(done.copy(end = cut.plusSeconds(1))), cut.toEpochMilli()))
        // In der Warteschlange: eingereiht nach dem Löschen, aber eine Nacht von davor (z. B. erneut eingereiht).
        transport.reachable = false
        clock = cut.toEpochMilli() + 1_000
        val box = outbox()
        put(box, "night/1-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, before)
        put(box, "night/2-a", DataLayer.PATH_MSG_NIGHT, DataLayer.CAPABILITY_PHONE, after)
        put(box, "alarms", DataLayer.PATH_MSG_ALARMS, DataLayer.CAPABILITY_PHONE, "wecker")
        box.dropIf(QueueMessages.KEY_NIGHT) { QueueMessages.droppedByClear(it, cut.toEpochMilli()) }
        assertEquals(listOf("night/2-a", "alarms"), box.pending().map { it.key })
        assertFalse(QueueMessages.droppedByClear(box.pending().last(), Long.MAX_VALUE), "andere Einträge nie")
    }

    @Test
    fun envelopeAndMessageFormats() {
        assertEquals("id" to "a\nb", Outbox.open(Outbox.wrap("id", "a\nb")))
        assertNull(Outbox.open("ohne-kennung".encodeToByteArray()))
        assertNull(Outbox.open(null))
        val snapshot = AlarmSnapshot(listOf(com.phasewake.app.core.AlarmSpec(id = "a", hour = 7, minute = 0), com.phasewake.app.core.AlarmSpec(id = "b", hour = 8, minute = 30)), 42L)
        assertEquals(snapshot, QueueMessages.decodeAlarms(QueueMessages.encodeAlarms(snapshot)))
        // Mit Zeitpunkten je Wecker und gelöschten Weckern (Audit A-4), auch mit Sonderzeichen in der Kennung.
        val stamped = snapshot.copy(stamps = mapOf("a" to 40L, "b" to 41L), deleted = mapOf("x=1\ny" to 39L))
        assertEquals(stamped, QueueMessages.decodeAlarms(QueueMessages.encodeAlarms(stamped)))
        // Format einer früheren Version (ohne Zeitpunkte je Wecker): «neuere Liste gewinnt».
        val legacy = "42\n${com.phasewake.app.core.Codec.encodeAlarms(snapshot.alarms)}"
        assertEquals(snapshot.copy(perAlarm = false), QueueMessages.decodeAlarms(legacy))
        // Eine frühere Version liest das neue Format: die Wecker, die Zusatzzeilen fallen weg.
        val body = QueueMessages.encodeAlarms(stamped)
        assertEquals(snapshot.alarms, com.phasewake.app.core.Codec.decodeAlarms(body.substringAfter('\n')))
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
        val first = "id-1-" + java.util.UUID.randomUUID()
        withTimeoutOrNull(2_000L) { sync.receive("node", Outbox.wrap(first, "inhalt")) { got = it } }
        assertEquals("inhalt", got)
        // Derselbe Eintrag noch einmal (Audit A-1): nur bestätigen, nicht noch einmal verarbeiten.
        var again = 0
        withTimeoutOrNull(2_000L) { sync.receive("node", Outbox.wrap(first, "inhalt")) { again++ } }
        assertEquals(0, again)
        var called = false
        withTimeoutOrNull(2_000L) { sync.receive("node", "ohne-kennung".encodeToByteArray()) { called = true } }
        assertFalse(called, "unlesbar: nicht verarbeiten")
        // Fehler beim Verarbeiten: kein Absturz (und keine Bestätigung); später nochmals verarbeitet.
        val failing = "id-2-" + java.util.UUID.randomUUID()
        withTimeoutOrNull(2_000L) { sync.receive("node", Outbox.wrap(failing, "x")) { error("Speicher voll") } }
        var retried = 0
        withTimeoutOrNull(2_000L) { sync.receive("node", Outbox.wrap(failing, "x")) { retried++ } }
        assertEquals(1, retried, "gescheitert: nicht als verarbeitet gemerkt")
        withTimeoutOrNull(2_000L) { sync.acked("unbekannt".encodeToByteArray()) }
        withTimeoutOrNull(2_000L) { sync.dropHistory(0L) }
        Unit
    }
}
