package com.phasewake.app.learn

import com.phasewake.app.MemoryStore
import com.phasewake.app.shared.LearningSnapshot
import com.phasewake.app.shared.LearningStore
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
}
