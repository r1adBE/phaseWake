package com.phasewake.app.shared

import com.phasewake.app.core.Codec
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.NightHistory
import com.phasewake.app.core.NightKeep
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.WakeReason
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Verlauf in einer Datei (AtomicFile braucht Android: Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NightRepositoryTest {
    private val dir: File = Files.createTempDirectory("nights").toFile()
    private val file = File(dir, "nights.v1")

    private fun night(id: String, start: Long, feeling: Int? = null) = NightRecord(
        alarmId = id,
        windowStart = Instant.ofEpochMilli(start),
        target = Instant.ofEpochMilli(start + 1_800_000),
        wokeAt = Instant.ofEpochMilli(start + 1_200_000),
        reason = WakeReason.LIGHT_SLEEP,
        snoozes = 0,
        epochs = listOf(Epoch(0.01, 58.0)),
        feeling = feeling,
    )

    @Test
    fun emptyWithoutFile() = runTest {
        val repo = NightRepository(file, NightKeep.WATCH, Dispatchers.Unconfined)
        assertNull(repo.nights.value)
        assertEquals(emptyList(), repo.load())
        assertEquals(emptyList(), repo.nights.value)
    }

    @Test
    fun addReplaceAndKeepFeeling() = runTest {
        val repo = NightRepository(file, NightKeep.WATCH, Dispatchers.Unconfined)
        repo.add(night("a", 1_000, feeling = 4))
        repo.add(night("b", 1_000))
        // Dieselbe Nacht noch einmal von der Uhr, ohne Befinden: das Befinden bleibt.
        repo.add(night("a", 1_000))
        assertEquals(listOf("b", "a"), repo.load().map { it.alarmId })
        assertEquals(4, repo.load().last().feeling)
        repo.setFeeling("b", Instant.ofEpochMilli(1_000), 2)
        // Neu aus der Datei gelesen: alles noch da.
        val again = NightRepository(file, NightKeep.WATCH, Dispatchers.Unconfined).load()
        assertEquals(listOf(2, 4), again.map { it.feeling })
    }

    /**
     * Befinden der Uhr nach dem Wecken (die Uhr schickt dieselbe Nacht mit ihrer Wahl noch einmal): gilt nur ohne
     * gespeichertes; eine Eingabe am Handy bleibt, auch wenn die Wahl verspätet oder wiederholt ankommt.
     */
    @Test
    fun watchFeelingNeverOverwritesThePhone() = runTest {
        val repo = NightRepository(file, NightKeep.PHONE, Dispatchers.Unconfined) { Instant.ofEpochMilli(2_000_000) }
        repo.add(night("a", 1_000))
        repo.add(night("a", 1_000, feeling = 2))
        assertEquals(2, repo.load().single().feeling, "ohne gespeichertes: Wahl der Uhr übernommen")
        repo.setFeeling("a", Instant.ofEpochMilli(1_000), 5)
        repo.add(night("a", 1_000, feeling = 2))
        assertEquals(5, repo.load().single().feeling, "Eingabe am Handy bleibt")
    }

    /** Die Uhr speichert ihre Wahl und schickt die so geänderte Nacht; ohne diese Nacht: nichts. */
    @Test
    fun setFeelingReturnsTheUpdatedNight() = runTest {
        val repo = NightRepository(file, NightKeep.WATCH, Dispatchers.Unconfined)
        repo.add(night("a", 1_000))
        assertEquals(3, repo.setFeeling("a", Instant.ofEpochMilli(1_000), 3)?.feeling)
        assertNull(repo.setFeeling("a", Instant.ofEpochMilli(2_000), 3))
        assertEquals(listOf(3), repo.load().map { it.feeling })
    }

    @Test
    fun replaceAllTrimsAndClear() = runTest {
        val repo = NightRepository(file, NightKeep.WATCH, Dispatchers.Unconfined)
        repo.replaceAll((0 until NightHistory.WATCH_NIGHTS + 3).map { night("a", it * 100_000_000L) })
        assertEquals(
            NightHistory.WATCH_NIGHTS, NightRepository(file, NightKeep.WATCH, Dispatchers.Unconfined).load().size,
        )
        repo.clear()
        assertEquals(emptyList(), NightRepository(file, NightKeep.WATCH, Dispatchers.Unconfined).load())
    }

    /** Handy: alles jünger als 2 Jahre bleibt beim Speichern, Älteres fällt weg; die Uhr behält 90 Nächte. */
    @Test
    fun phoneKeepsTwoYearsOnSave() = runTest {
        val day = 86_400_000L
        val now = Instant.ofEpochMilli(1_000 * day)
        val repo = NightRepository(file, NightKeep.PHONE, Dispatchers.Unconfined) { now }
        // Wecken 20 Min nach Fensterbeginn: Fensterbeginn so wählen, dass das Wecken genau auf der Grenze liegt.
        val edge = now.toEpochMilli() - 730 * day - 1_200_000
        val days = (1..200).map { night("n$it", edge + it * day) }
        repo.replaceAll(listOf(night("alt", edge - 1), night("grenze", edge)) + days)
        assertEquals(201, repo.load().size)
        assertEquals("grenze", repo.load().first().alarmId)
        repo.add(night("neu", now.toEpochMilli() - 1_200_000))
        assertEquals(202, NightRepository(file, NightKeep.PHONE, Dispatchers.Unconfined) { now }.load().size)
        // Ein Tag später: die Nacht auf der Grenze fällt beim nächsten Speichern weg.
        val later = NightRepository(file, NightKeep.PHONE, Dispatchers.Unconfined) { now.plusMillis(day) }
        later.add(night("noch-neuer", now.toEpochMilli()))
        assertEquals("n1", later.load().first().alarmId)
        // Die Uhr kürzt dieselben Nächte auf 90.
        val watch = NightRepository(file, NightKeep.WATCH, Dispatchers.Unconfined)
        watch.add(night("uhr", now.toEpochMilli()))
        assertEquals(NightHistory.WATCH_NIGHTS, watch.load().size)
    }

    /** «Älter als 90 Tage löschen»: was bis zur Grenze geweckt hat, ist weg. */
    @Test
    fun clearBeforeRemovesOlderNights() = runTest {
        val repo = NightRepository(file, NightKeep.WATCH, Dispatchers.Unconfined)
        repo.replaceAll(listOf(night("alt", 1_000), night("neu", 5_000_000)))
        repo.clearBefore(Instant.ofEpochMilli(1_000 + 1_200_000))
        val again = NightRepository(file, NightKeep.WATCH, Dispatchers.Unconfined).load()
        assertEquals(listOf("neu"), again.map { it.alarmId })
    }

    @Test
    fun unreadableNightIsSkipped() = runTest {
        file.writeText(
            Codec.encodeNight(night("a", 1_000)) + "\n%%\nphasewake-night 1\nkaputt\n\n%%\n" + Codec.encodeNight(
                night("b", 2_000),
            ),
        )
        val loaded = NightRepository(file, NightKeep.WATCH, Dispatchers.Unconfined).load()
        assertEquals(listOf("a", "b"), loaded.map { it.alarmId })
    }
}
