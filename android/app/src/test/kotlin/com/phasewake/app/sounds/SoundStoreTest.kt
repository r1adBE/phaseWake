package com.phasewake.app.sounds

import android.app.Application
import com.phasewake.app.core.SoundClip
import com.phasewake.app.core.SoundEvents
import com.phasewake.app.core.SoundType
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ablage der Geräusch-Ausschnitte ([SoundStore], Audit F-02): Liste übersteht einen Neustart, Löschen entfernt auch die
 * Dateien, nach 7 Tagen ist alles weg, Dateien ohne Eintrag verschwinden erst nach einer Stunde (eine laufende Aufnahme
 * bleibt), zu viele Ausschnitte einer Nacht kosten die leisesten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SoundStoreTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val dir get() = File(context.filesDir, "sounds")
    private val index get() = File(dir, "clips.txt")
    private val now = Instant.parse("2026-11-10T06:00:00Z")
    private val night = LocalDate.parse("2026-11-09")

    @Before
    @After
    fun clean() {
        dir.deleteRecursively()
    }

    /** Ausschnitt [name] [ageHours] Std. vor [now], mit Datei. */
    private fun clip(store: SoundStore, name: String, ageHours: Long = 2, peakDb: Int = -20, day: LocalDate = night): SoundClip {
        store.newFile(name).writeText("m4a")
        return SoundClip(day, now.minus(Duration.ofHours(ageHours)), SoundType.SNORE, 4_000, peakDb, name)
    }

    @Test
    fun indexSurvivesARestart() = runBlocking {
        val store = SoundStore(context)
        assertNull(store.clips.value)
        val a = clip(store, "a.m4a", ageHours = 3)
        val b = clip(store, "b.m4a", ageHours = 1)
        store.add(b)
        store.add(a)
        assertEquals(listOf(a, b), store.clips.value)
        // Neuer Prozess: dieselbe Liste aus der Datei, älteste zuerst.
        assertEquals(listOf(a, b), SoundStore(context).load())
        assertFalse(File(dir, "clips.txt.tmp").exists())
    }

    @Test
    fun unreadableIndexStartsEmpty() = runBlocking {
        dir.mkdirs()
        index.writeText("kaputt\n2026-11-09|x|SNORE|1|2|../weg.m4a")
        assertEquals(emptyList(), SoundStore(context).load())
    }

    @Test
    fun deletingRemovesEntryAndFile() = runBlocking {
        val store = SoundStore(context)
        val a = clip(store, "a.m4a")
        val b = clip(store, "b.m4a", day = night.minusDays(1))
        val c = clip(store, "c.m4a", day = night.minusDays(1))
        listOf(a, b, c).forEach { store.add(it) }
        store.delete(a)
        assertFalse(File(dir, "a.m4a").exists())
        assertEquals(listOf(b, c), store.load())
        // Eine ganze Nacht.
        store.deleteNight(night.minusDays(1))
        assertEquals(emptyList(), SoundStore(context).load())
        assertFalse(File(dir, "b.m4a").exists() || File(dir, "c.m4a").exists())
    }

    @Test
    fun clearRemovesEverything() = runBlocking {
        val store = SoundStore(context)
        store.add(clip(store, "a.m4a"))
        store.clear()
        assertEquals(emptyList(), store.clips.value)
        assertFalse(dir.exists())
    }

    @Test
    fun cleanupExpiresOldClipsAndOrphans() = runBlocking {
        val store = SoundStore(context)
        val fresh = clip(store, "fresh.m4a", ageHours = 2)
        val old = clip(store, "old.m4a", ageHours = SoundEvents.RETENTION_DAYS * 24 + 1)
        store.add(fresh)
        store.add(old)
        // Dateien ohne Eintrag: alt (Absturz) und eben entstanden (Aufnahme läuft noch).
        val orphan = store.newFile("orphan.m4a").apply { writeText("m4a") }
        orphan.setLastModified(now.minus(Duration.ofHours(2)).toEpochMilli())
        val recording = store.newFile("recording.m4a").apply { writeText("m4a") }
        recording.setLastModified(now.minus(Duration.ofMinutes(10)).toEpochMilli())
        // Auch die Liste selbst ist alt; sie bleibt.
        index.setLastModified(now.minus(Duration.ofDays(30)).toEpochMilli())
        File(dir, "fresh.m4a").setLastModified(now.minus(Duration.ofDays(1)).toEpochMilli())

        store.cleanup(now)

        assertEquals(listOf(fresh), SoundStore(context).load())
        assertFalse(File(dir, "old.m4a").exists())
        assertFalse(orphan.exists())
        assertTrue(recording.exists())
        assertTrue(File(dir, "fresh.m4a").exists())
        assertTrue(index.exists())
    }

    @Test
    fun tooManyClipsDropTheQuietest() = runBlocking {
        val store = SoundStore(context)
        val clips = (0 until SoundEvents.MAX_CLIPS_PER_NIGHT).map { clip(store, "c$it.m4a", ageHours = 3, peakDb = if (it == 7) -40 else -20) }
        clips.forEach { store.add(it) }
        val quietest = clips[7]
        store.add(clip(store, "loud.m4a", ageHours = 1, peakDb = -5))
        val kept = store.load()
        assertEquals(SoundEvents.MAX_CLIPS_PER_NIGHT, kept.size)
        assertTrue(kept.any { it.file == "loud.m4a" })
        assertFalse(kept.any { it.file == quietest.file })
        assertFalse(File(dir, quietest.file).exists())
    }

    @Test
    fun unsafeNamesHaveNoFile() {
        val store = SoundStore(context)
        assertNull(store.file(SoundClip(night, now, SoundType.TALK, 1, -10, "../nights.v1")))
        assertNull(store.file(SoundClip(night, now, SoundType.TALK, 1, -10, "..")))
        assertEquals(File(dir, "ok.m4a"), store.file(SoundClip(night, now, SoundType.TALK, 1, -10, "ok.m4a")))
    }
}
