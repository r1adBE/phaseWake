package com.phasewake.app.learn

import androidx.datastore.preferences.core.emptyPreferences
import com.phasewake.app.MemoryStore
import com.phasewake.app.SettingsRepository
import com.phasewake.app.shared.LearningSnapshot
import com.phasewake.app.shared.LearningStore
import com.phasewake.app.shared.PhaseWakeStore
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fix S-3: Ein neu installiertes Handy schickt der Uhr seinen Stand mit Zeitpunkt, nicht mit 0. */
class LearningStampTest {
    private val now = Instant.parse("2026-11-14T08:00:00Z")

    @Test
    fun freshPhoneStampsItsStateOnce() = runBlocking {
        val repo = LearningRepository(MemoryStore())
        assertEquals(0L, repo.current().updatedAt)
        assertEquals(now.toEpochMilli(), repo.stampIfNever(now).updatedAt)
        // Schon gestempelt: bleibt (sonst ginge bei jedem Start ein neues Paket an die Uhr).
        assertEquals(now.toEpochMilli(), repo.stampIfNever(now.plusSeconds(60)).updatedAt)
        assertNull(repo.current().consent, "Zustimmung unverändert: nicht gefragt")
    }

    @Test
    fun watchOfAnEarlierPhoneTakesTheFreshState() = runBlocking {
        // Uhr mit dem Stand des früheren Handys: Lernphase an, Grundrauschen gelernt.
        val watch = LearningStore(MemoryStore())
        watch.setIfNewer(LearningSnapshot(true, 0.02, null, now.minusSeconds(86_400).toEpochMilli()))
        val phone = LearningRepository(MemoryStore())
        assertTrue(watch.setIfNewer(phone.stampIfNever(now).snapshot()))
        val adopted = watch.current()
        assertFalse(adopted.active)
        assertNull(adopted.floor)
    }

    @Test
    fun resetAfterBrokenFileIsNotStamped() = runBlocking {
        // Audit SP-7: Nach einer kaputten Datei beginnt der Speicher leer; dieser Stand ist nicht neuer als der der Uhr.
        val reset = MemoryStore(emptyPreferences().toMutablePreferences().apply { this[PhaseWakeStore.RESET_AT] = 1L })
        val watch = LearningStore(MemoryStore())
        val learned = LearningSnapshot(false, 0.02, 52.0, now.minusSeconds(86_400).toEpochMilli())
        watch.setIfNewer(learned)
        val phone = LearningRepository(reset)
        assertEquals(0L, phone.stampIfNever(now).updatedAt)
        assertFalse(watch.setIfNewer(phone.stampIfNever(now).snapshot()), "die Uhr behält ihre gelernten Werte")
        assertEquals(learned, watch.current())
        // Schlafziel und Schalter der Einschlaf-Messung ebenso.
        val settings = SettingsRepository(reset)
        assertEquals(0L, settings.sleepGoalStamp())
        assertEquals(0L, settings.autoOnsetStamped().second)
        // Eine Änderung hier gilt wieder als neuer Stand.
        phone.setConsent(true, now)
        assertEquals(now.toEpochMilli(), phone.current().updatedAt)
    }
}
