package com.phasewake.app.shared

import com.phasewake.app.core.AlarmSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Abgleich der Wecker kommt zur Ruhe, auch wenn eine Nachricht verloren geht (Audit AN-1): Weicht der zusammengeführte
 * Stand vom empfangenen ab, antwortet der Empfänger mit seinem Stand ([AlarmRepository.Received.reply]); stimmt er
 * überein, antwortet niemand. Über das Format der Nachricht ([QueueMessages]) wie zwischen Handy und Uhr.
 */
class AlarmConvergenceTest {
    private var now = 1_000L
    private val phone = AlarmRepository(MemoryStore()) { now }
    private val watch = AlarmRepository(MemoryStore()) { now }

    private fun alarm(id: String, hour: Int = 7) = AlarmSpec(id = id, hour = hour, minute = 0)

    /** Stand über die Leitung: kodiert und wieder gelesen. */
    private fun wire(snapshot: AlarmSnapshot): AlarmSnapshot = assertNotNull(QueueMessages.decodeAlarms(QueueMessages.encodeAlarms(snapshot)))

    @Test
    fun lostMessageConvergesWithTheNextExchange() = runTest {
        // Beide kennen Wecker a.
        assertNull(watch.receive(wire(phone.upsert(alarm("a")))).reply, "gleich: keine Antwort")
        // Uhr: Einmal-Wecker nach dem Klingeln aus; die Nachricht ans Handy geht verloren.
        now = 2_000L
        watch.setEnabled("a", false)
        // Handy: anderer Wecker b; die Uhr empfängt den Stand ohne ihre eigene Änderung.
        now = 3_000L
        val fromPhone = phone.upsert(alarm("b", 8))
        val atWatch = watch.receive(wire(fromPhone))
        assertTrue(atWatch.changed, "b übernommen")
        val reply = assertNotNull(atWatch.reply, "Uhr weicht ab (a aus): antwortet mit ihrem Stand")
        // Das Handy übernimmt a = aus; sein Stand stimmt jetzt mit der Antwort überein, keine weitere Nachricht.
        val atPhone = phone.receive(wire(reply))
        assertTrue(atPhone.changed)
        assertNull(atPhone.reply)
        assertEquals(watch.current().alarms, phone.current().alarms)
        assertFalse(phone.current().alarms.first { it.id == "a" }.enabled)
        assertEquals(watch.current().stamps, phone.current().stamps)
    }

    @Test
    fun ownStateAtStartChangesNothingWhenEqual() = runTest {
        // App-Start: Jede Seite reiht ihren Stand einmal ein; stimmt er überein, merkt die Gegenseite nichts.
        watch.receive(wire(phone.upsert(alarm("a"))))
        now = 5_000L
        val result = phone.receive(wire(watch.current()))
        assertFalse(result.changed)
        assertNull(result.reply)
    }

    @Test
    fun deletionUnknownToThePeerIsAnswered() = runTest {
        watch.receive(wire(phone.upsert(alarm("a"))))
        // Handy löscht a; die Nachricht geht verloren. Die Uhr schickt später ihren (alten) Stand.
        now = 2_000L
        phone.remove("a")
        val atPhone = phone.receive(wire(watch.current()))
        assertFalse(atPhone.changed, "Löschen ist neuer: a bleibt weg")
        val reply = assertNotNull(atPhone.reply)
        assertTrue(watch.receive(wire(reply)).changed)
        assertTrue(watch.current().alarms.isEmpty())
        assertNull(phone.receive(wire(watch.current())).reply)
    }

    @Test
    fun differsIgnoresOnlyTheListStamp() {
        val base = AlarmSnapshot(listOf(alarm("a")), 10L, mapOf("a" to 10L))
        assertFalse(AlarmRepository.differs(base, base.copy(updatedAt = 20L), now))
        assertTrue(AlarmRepository.differs(base, base.copy(stamps = mapOf("a" to 9L)), now))
        assertTrue(AlarmRepository.differs(base, base.copy(alarms = listOf(alarm("a").copy(enabled = false))), now))
        assertTrue(AlarmRepository.differs(base, base.copy(deleted = mapOf("x" to 5L)), now))
        // Fehlender Zeitpunkt je Wecker: der Listen-Zeitpunkt gilt.
        assertFalse(AlarmRepository.differs(base, AlarmSnapshot(listOf(alarm("a")), 10L), now))
    }

    @Test
    fun replaceAllKeepsAtMostEightAlarms() = runTest {
        // Audit DA-8: Eine Sicherung mit mehr Weckern (fremd oder manipuliert) ergab mehr als die erlaubten 8.
        val many = (0 until 10).map { alarm("w$it", hour = 23 - it) }
        val restored = phone.replaceAll(many)
        assertEquals(AlarmSpec.MAX_ALARMS, restored.alarms.size)
        assertEquals(many.take(AlarmSpec.MAX_ALARMS).map { it.id }.toSet(), restored.alarms.map { it.id }.toSet(), "die ersten der Sicherung")
    }

    @Test
    fun implausibleTimesFromThePeerAndInStorage() = runTest {
        // Audit KN-4/AN-5: Long.MAX_VALUE als Listen-Zeitpunkt verwirft die Nachricht; einzelne Zeitpunkte fallen weg.
        val max = Long.MAX_VALUE
        val body = QueueMessages.encodeAlarms(AlarmSnapshot(listOf(alarm("a")), 10L, mapOf("a" to max), mapOf("x" to max)))
        assertEquals(AlarmSnapshot(listOf(alarm("a")), 10L), QueueMessages.decodeAlarms(body))
        assertNull(QueueMessages.decodeAlarms(QueueMessages.encodeAlarms(AlarmSnapshot(listOf(alarm("a")), max))))
        // Kaputter gespeicherter Listen-Zeitpunkt: + 1 läuft nicht über, der nächste Zeitpunkt ist jetzt.
        val store = MemoryStore()
        store.updateData { it.toMutablePreferences().apply { set(androidx.datastore.preferences.core.longPreferencesKey("alarms_updated_at"), max) } }
        assertEquals(now, AlarmRepository(store) { now }.upsert(alarm("b")).updatedAt)
        // Lernphase ebenso.
        assertNull(LearningSnapshot.decode("1|0.02||$max"))
        assertEquals(LearningSnapshot(true, 0.02, null, 9L), LearningSnapshot.decode("1|0.02||9"))
    }
}
