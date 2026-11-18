package com.phasewake.app.shared

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Anfrage nach dem Stand der Gegenseite beim Fortsetzen: nur Unbestätigtes, ausser die anfragende Seite hat noch nie
 * etwas von der Gegenseite verarbeitet (frisch installiert, Kennungen vergessen), hat keine Wecker oder ihr Speicher
 * begann seit der letzten Anfrage leer. Ältere Versionen senden die Anfrage ohne Inhalt und bekommen weiter den
 * vollen Stand.
 */
class SyncRequestTest {
    private val dir: File = Files.createTempDirectory("sync-request").toFile()

    private fun full(receivedAny: Boolean = true, hasAlarms: Boolean = true, resetAt: Long? = null, last: Long? = null):
        Boolean = DataLayer.wantsFullSync(DataLayer.resumeSyncPayload(receivedAny, hasAlarms, resetAt, last))

    @Test
    fun freshSideAsksForEverything() {
        val payload = DataLayer.resumeSyncPayload(false, hasAlarms = true, resetAt = null, lastRequestAt = 5L)
        assertContentEquals(ByteArray(0), payload)
        assertTrue(DataLayer.wantsFullSync(payload))
    }

    @Test
    fun knownSideAsksOnlyForUnconfirmed() {
        assertFalse(full())
        assertFalse(full(last = 1_000L))
    }

    @Test
    fun sideWithoutAlarmsAsksForEverything() {
        // Wecker weg, Kennungen noch da: Das Handy hält alles für bestätigt und sendete auf «changed» nichts.
        assertTrue(full(hasAlarms = false, last = 1_000L))
    }

    @Test
    fun resetSinceTheLastRequestAsksForEverythingOnce() {
        assertTrue(full(resetAt = 2_000L, last = 1_000L), "zurückgesetzt nach der letzten Anfrage")
        assertTrue(full(resetAt = 2_000L, last = null), "zurückgesetzt, noch nie angefragt")
        assertFalse(full(resetAt = 2_000L, last = 3_000L), "nach der vollen Anfrage wieder nur Ungeprüftes")
    }

    @Test
    fun requestWithoutOrUnknownContentGetsEverything() {
        assertTrue(DataLayer.wantsFullSync(null))
        assertTrue(DataLayer.wantsFullSync(ByteArray(0)))
        assertTrue(DataLayer.wantsFullSync("full".encodeToByteArray()))
    }

    @Test
    fun receivedIdsKnowWhetherAnythingCameFromThePeer() = runBlocking {
        val file = File(dir, "outbox-received")
        assertFalse(ReceivedIds(file).any(), "frisch installiert: nichts verarbeitet")
        ReceivedIds(file).add("a1")
        assertTrue(ReceivedIds(file).any(), "übersteht das Prozessende")
        file.delete()
        assertFalse(ReceivedIds(file).any(), "Daten gelöscht: wieder wie frisch installiert")
    }

    @Test
    fun forgottenIdsAreGoneInMemoryAndOnDisk() = runBlocking {
        val file = File(dir, "outbox-received-reset")
        val ids = ReceivedIds(file)
        ids.add("a1")
        assertTrue(ids.any())
        ids.forget()
        assertFalse(file.exists(), "Datei sofort weg")
        assertFalse(ids.any(), "im Speicher ebenso")
        assertFalse(ids.contains("a1"))
        ids.add("b2")
        assertTrue(ReceivedIds(file).contains("b2"), "danach wieder wie gewohnt gemerkt")
        assertFalse(ReceivedIds(file).contains("a1"))
    }

    @Test
    fun resumeGateIsSeparateFromTheStartGate() {
        val start = SyncGate(File(dir, "outbox/sync-requested")) { 10_000L }
        val resume = SyncGate(File(dir, "outbox/sync-requested-resume")) { 10_000L }
        resume.mark()
        assertTrue(start.due(), "Fortsetzen hält die Anfrage beim Start nicht auf")
        assertFalse(resume.due())
        assertEquals(10_000L, resume.lastAt())
    }
}
