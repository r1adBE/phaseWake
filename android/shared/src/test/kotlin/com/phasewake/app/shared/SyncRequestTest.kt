package com.phasewake.app.shared

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Anfrage nach dem Stand der Gegenseite beim Fortsetzen: nur Unbestätigtes, ausser die anfragende Seite hat noch nie
 * etwas von der Gegenseite verarbeitet (frisch installiert, zurückgesetzt). Ältere Versionen senden die Anfrage ohne
 * Inhalt und bekommen weiter den vollen Stand.
 */
class SyncRequestTest {
    private val dir: File = Files.createTempDirectory("sync-request").toFile()

    @Test
    fun freshSideAsksForEverything() {
        val payload = DataLayer.resumeSyncPayload(receivedAny = false)
        assertContentEquals(ByteArray(0), payload)
        assertTrue(DataLayer.wantsFullSync(payload))
    }

    @Test
    fun knownSideAsksOnlyForUnconfirmed() {
        assertFalse(DataLayer.wantsFullSync(DataLayer.resumeSyncPayload(receivedAny = true)))
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
}
