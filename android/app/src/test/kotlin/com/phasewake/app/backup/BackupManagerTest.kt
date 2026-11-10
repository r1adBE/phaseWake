package com.phasewake.app.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.QuietApp
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.BackupException
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.WakeReason
import com.phasewake.app.phoneApp
import com.phasewake.app.shared.AlarmSnapshot
import com.phasewake.app.shared.phaseWakeStore
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sichern und Wiederherstellen am Handy ([BackupManager], Audit F-02): Sicherung schreiben, lesen, mit Passwort öffnen
 * und alles ersetzen (Wecker, Nächte, Schlafziel); falsches Passwort oder fremde Datei ändern nichts, eine unlesbare
 * Sicherung ebenso; ein nach dem Schreiben unterbrochenes Wiederherstellen wird beim Start abgeschlossen ([RestoreJournal]).
 * Der Abgleich mit der Uhr danach ist ersetzt ([synced]): Unter Robolectric antworten die Wear-Dienste nie.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class BackupManagerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = context.phoneApp as QuietApp
    private val file get() = File(context.cacheDir, "test.pwbak")
    private val uri get() = Uri.fromFile(file)
    private val password = "Mondsichel-7"

    /** Stände, mit denen der Abgleich mit der Uhr angestossen wurde. */
    private val synced = mutableListOf<AlarmSnapshot>()
    private suspend fun resync(snapshot: AlarmSnapshot) {
        synced += snapshot
    }

    private fun night(id: String, start: String): NightRecord {
        val at = Instant.parse(start)
        return NightRecord(id, at, at.plus(Duration.ofMinutes(30)), at.plus(Duration.ofMinutes(12)), WakeReason.LIGHT_SLEEP, 1, emptyList())
    }

    private val alarm = AlarmSpec(id = "a", hour = 6, minute = 30, label = "Arbeit")
    private val kept = night("a", "2026-11-09T05:00:00Z")

    @Before
    fun setUp() = runBlocking {
        file.delete()
        RestoreJournal(context.filesDir).finish()
        app.alarms.replaceAll(listOf(alarm))
        app.nights.replaceAll(listOf(kept))
        app.settings.setSleepGoal(450)
    }

    @After
    fun cleanUp() {
        file.delete()
        app.appScope.cancel()
    }

    /** Ändert alles, was die Sicherung danach zurückbringen soll. */
    private suspend fun changeEverything() {
        app.alarms.replaceAll(listOf(AlarmSpec(id = "b", hour = 8, minute = 0)))
        app.nights.replaceAll(listOf(night("b", "2026-11-10T07:00:00Z")))
        app.settings.setSleepGoal(510)
    }

    @Test
    fun roundTripReplacesEverything() = runBlocking {
        assertTrue(BackupManager.create(context, app, uri, password))
        assertTrue(file.length() > 0)
        val preview = BackupManager.open(assertNotNullBytes(BackupManager.read(context, uri)), password)
        assertEquals(1, preview.alarms)
        assertEquals(1, preview.nights)
        changeEverything()

        assertTrue(BackupManager.restore(app, preview.payload, ::resync))

        assertEquals(listOf("a"), app.alarms.current().alarms.map { it.id })
        assertEquals("Arbeit", app.alarms.current().alarms.single().label)
        assertEquals(listOf(kept), app.nights.load())
        assertEquals(450, app.settings.sleepGoalNow())
        // Abgleich mit der Uhr mit dem neuen Stand, danach abgeschlossen: keine Marke, beim nächsten Start nichts zu tun.
        assertEquals(listOf(listOf("a")), synced.map { s -> s.alarms.map { it.id } })
        // Abgeschlossen: keine Marke, beim nächsten Start nichts zu tun.
        assertNull(RestoreJournal(context.filesDir).pendingToken())
    }

    private fun assertNotNullBytes(bytes: ByteArray?): ByteArray = kotlin.test.assertNotNull(bytes, "Sicherung nicht lesbar")

    @Test
    fun wrongPasswordOrForeignFileIsRefused() = runBlocking {
        assertTrue(BackupManager.create(context, app, uri, password))
        val bytes = assertNotNullBytes(BackupManager.read(context, uri))
        val wrong = assertFailsWith<BackupException> { BackupManager.open(bytes, "Sonnenaufgang-7") }
        assertEquals(BackupException.Reason.WRONG_PASSWORD, wrong.reason)
        val foreign = assertFailsWith<BackupException> { BackupManager.open("kein Backup".encodeToByteArray(), password) }
        assertEquals(BackupException.Reason.NOT_A_BACKUP, foreign.reason)
    }

    @Test
    fun tooShortPasswordWritesNothing() = runBlocking {
        assertFalse(BackupManager.create(context, app, uri, "kurz"))
        assertFalse(file.exists() && file.length() > 0)
    }

    @Test
    fun missingFileIsUnreadable() = runBlocking {
        assertNull(BackupManager.read(context, Uri.fromFile(File(context.cacheDir, "fehlt.pwbak"))))
    }

    @Test
    fun unreadableAlarmsChangeNothing() = runBlocking {
        assertTrue(BackupManager.create(context, app, uri, password))
        val payload = BackupManager.open(assertNotNullBytes(BackupManager.read(context, uri)), password).payload
        changeEverything()
        val before = app.nights.load()

        assertFalse(BackupManager.restore(app, payload.copy(alarms = "kaputt|||"), ::resync))

        assertEquals(listOf("b"), app.alarms.current().alarms.map { it.id })
        assertEquals(before, app.nights.load())
        assertEquals(510, app.settings.sleepGoalNow())
        assertNull(RestoreJournal(context.filesDir).pendingToken())
        assertTrue(synced.isEmpty())
    }

    @Test
    fun unreadableNightsAreSkipped() = runBlocking {
        val payload = run {
            assertTrue(BackupManager.create(context, app, uri, password))
            BackupManager.open(assertNotNullBytes(BackupManager.read(context, uri)), password).payload
        }
        changeEverything()
        assertTrue(BackupManager.restore(app, payload.copy(nights = payload.nights + "keine Nacht"), ::resync))
        assertEquals(listOf(kept), app.nights.load())
    }

    @Test
    fun restoreInterruptedAfterWritingIsFinishedOnStart() = runBlocking {
        // Prozessende nach dem `edit`: Marke liegt noch, der DataStore trägt schon die Kennung.
        val journal = RestoreJournal(context.filesDir)
        journal.begin("t-1", listOf(night("alt", "2026-11-01T05:00:00Z")))
        app.phaseWakeStore.edit { it[stringPreferencesKey("restore_token")] = "t-1" }

        BackupManager.recover(app, ::resync)

        // Der neue Stand bleibt (keine alten Nächte zurück), der Abgleich mit der Uhr ist nachgeholt.
        assertEquals(listOf(kept), app.nights.load())
        assertNull(RestoreJournal(context.filesDir).pendingToken())
        assertEquals(listOf(listOf(alarm)), synced.map { it.alarms })
    }

    @Test
    fun nothingPendingDoesNothing() = runBlocking {
        BackupManager.recover(app, ::resync)
        assertTrue(synced.isEmpty())
        assertEquals(listOf(kept), app.nights.load())
        assertEquals(listOf(alarm), app.alarms.current().alarms)
    }
}
