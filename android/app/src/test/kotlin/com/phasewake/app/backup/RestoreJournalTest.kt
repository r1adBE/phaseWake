package com.phasewake.app.backup

import android.app.Application
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.WakeReason
import com.phasewake.app.shared.NightRepository
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Wiederherstellen gegen Prozessende ([RestoreJournal], Review K-03): Endet der Prozess zwischen Nächten und DataStore,
 * gilt beim nächsten Start wieder der alte Stand (alte Nächte zurück) oder, wenn der DataStore schon geschrieben war,
 * der neue. Ein «neuer Prozess» ist hier ein neues [RestoreJournal] und ein neues [NightRepository] auf denselben Dateien.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RestoreJournalTest {
    private val dir: File get() = RuntimeEnvironment.getApplication().filesDir
    private val nightsFile get() = File(dir, "nights.v1")

    private fun night(id: String, start: Long) =
        NightRecord(id, Instant.ofEpochMilli(start), Instant.ofEpochMilli(start + 1_800_000), Instant.ofEpochMilli(start + 900_000), WakeReason.DEADLINE, 0, emptyList())

    private val old = listOf(night("alt", 1_000_000))
    private val restored = listOf(night("neu", 2_000_000), night("neu", 3_000_000))

    /** Bis zum Tausch der Nächte, dann «Prozessende» (kein `edit`, keine Aufräumarbeit). */
    private fun interruptedRestore(token: String) = runBlocking {
        val nights = NightRepository(nightsFile)
        nights.replaceAll(old)
        RestoreJournal(dir).begin(token, nights.load())
        nights.replaceAll(restored)
    }

    @Test
    fun interruptedBeforeDataStoreRollsBack() = runBlocking {
        interruptedRestore("t1")
        val nights = NightRepository(nightsFile)
        val journal = RestoreJournal(dir)
        // Der DataStore trägt noch die Kennung eines früheren Wiederherstellens (oder keine).
        assertEquals(RestoreJournal.Outcome.ROLLED_BACK, journal.recover("t0", nights))
        assertEquals(old, NightRepository(nightsFile).load())
        assertNull(journal.pendingToken())
        assertFalse(File(dir, "restore-nights-before.v1").exists(), "Kopie nach dem Abschluss weg")
    }

    @Test
    fun interruptedAfterDataStoreKeepsNewState() = runBlocking {
        interruptedRestore("t2")
        val journal = RestoreJournal(dir)
        // Das `edit` hatte die Kennung schon geschrieben: der neue Stand ist vollständig.
        assertEquals(RestoreJournal.Outcome.FINISHED, journal.recover("t2", NightRepository(nightsFile)))
        assertEquals(restored, NightRepository(nightsFile).load())
        // Die Marke bleibt bis nach dem Abgleich mit der Uhr (Review N-3): Endet der Prozess vorher, wieder FINISHED.
        assertEquals("t2", journal.pendingToken())
        assertEquals(RestoreJournal.Outcome.FINISHED, RestoreJournal(dir).recover("t2", NightRepository(nightsFile)))
        assertEquals(restored, NightRepository(nightsFile).load())
        journal.finish()
        assertNull(journal.pendingToken())
    }

    @Test
    fun withoutMarkerNothingChangesAndStaleCopyGoes() = runBlocking {
        val nights = NightRepository(nightsFile)
        nights.replaceAll(old)
        // Prozessende nach der Kopie, aber vor der Marke: Kopie ohne Marke.
        NightRepository(File(dir, "restore-nights-before.v1")).replaceAll(restored)
        assertEquals(RestoreJournal.Outcome.NONE, RestoreJournal(dir).recover(null, nights))
        assertEquals(old, NightRepository(nightsFile).load())
        assertFalse(File(dir, "restore-nights-before.v1").exists())
    }

    @Test
    fun finishedRestoreLeavesNothingBehind() = runBlocking {
        val journal = RestoreJournal(dir)
        journal.begin("t3", old)
        assertEquals("t3", journal.pendingToken())
        journal.finish()
        assertNull(journal.pendingToken())
        assertEquals(RestoreJournal.Outcome.NONE, RestoreJournal(dir).recover("t3", NightRepository(nightsFile)))
    }
}
