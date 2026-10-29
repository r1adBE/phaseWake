package com.phasewake.app.shared

import com.phasewake.app.core.Codec
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.NightHistory
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
        val repo = NightRepository(file, Dispatchers.Unconfined)
        assertNull(repo.nights.value)
        assertEquals(emptyList(), repo.load())
        assertEquals(emptyList(), repo.nights.value)
    }

    @Test
    fun addReplaceAndKeepFeeling() = runTest {
        val repo = NightRepository(file, Dispatchers.Unconfined)
        repo.add(night("a", 1_000, feeling = 4))
        repo.add(night("b", 1_000))
        // Dieselbe Nacht noch einmal von der Uhr, ohne Befinden: das Befinden bleibt.
        repo.add(night("a", 1_000))
        assertEquals(listOf("b", "a"), repo.load().map { it.alarmId })
        assertEquals(4, repo.load().last().feeling)
        repo.setFeeling("b", Instant.ofEpochMilli(1_000), 2)
        // Neu aus der Datei gelesen: alles noch da.
        val again = NightRepository(file, Dispatchers.Unconfined).load()
        assertEquals(listOf(2, 4), again.map { it.feeling })
    }

    @Test
    fun replaceAllTrimsAndClear() = runTest {
        val repo = NightRepository(file, Dispatchers.Unconfined)
        repo.replaceAll((0 until NightHistory.MAX_NIGHTS + 3).map { night("a", it * 100_000_000L) })
        assertEquals(NightHistory.MAX_NIGHTS, NightRepository(file, Dispatchers.Unconfined).load().size)
        repo.clear()
        assertEquals(emptyList(), NightRepository(file, Dispatchers.Unconfined).load())
    }

    @Test
    fun unreadableNightIsSkipped() = runTest {
        file.writeText(Codec.encodeNight(night("a", 1_000)) + "\n%%\nphasewake-night 1\nkaputt\n\n%%\n" + Codec.encodeNight(night("b", 2_000)))
        assertEquals(listOf("a", "b"), NightRepository(file, Dispatchers.Unconfined).load().map { it.alarmId })
    }
}
