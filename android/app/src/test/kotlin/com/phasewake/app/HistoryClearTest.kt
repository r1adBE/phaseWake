package com.phasewake.app

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.phasewake.app.shared.phaseWakeStore
import com.phasewake.app.core.BackupSettings
import com.phasewake.app.core.TimeBounds
import java.io.File
import java.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
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
 * «Verlauf löschen» (Audit DA-1, DA-5): Der Zeitpunkt gilt höchstens bis jetzt und nur innerhalb von [TimeBounds], beim
 * Verwenden wie beim Wiederherstellen; geteilte Exporte verschwinden mit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class HistoryClearTest {
    private val now = 1_800_000_000_000L
    private val store = MemoryStore()
    private val settings = SettingsRepository(store) { now }

    @After
    fun cleanUp() = RuntimeEnvironment.getApplication().phoneApp.appScope.cancel()

    @Test
    fun clearedAtIsNeverLaterThanNowAndPlausible() {
        assertEquals(5L, SettingsRepository.clearedAt(5L, now))
        assertEquals(now, SettingsRepository.clearedAt(now + 1, now), "Zukunft: jetzt")
        assertNull(SettingsRepository.clearedAt(Long.MAX_VALUE, now))
        assertNull(SettingsRepository.clearedAt(-1L, now))
        assertNull(SettingsRepository.clearedAt(null, now))
    }

    @Test
    fun futureValueIsClampedWhenUsedAndRestored() = runBlocking {
        // Uhr verstellt beim Löschen: Alle künftigen Nächte aus Health Connect blieben sonst ausgeblendet.
        settings.setHistoryClearedAt(Instant.ofEpochMilli(now + 86_400_000L))
        assertEquals(Instant.ofEpochMilli(now), settings.historyClearedAt.first())
        assertEquals(now, settings.backupSettings().historyClearedAt)
        // Sicherung «aus der Zukunft» oder kaputt.
        store.edit { settings.restoreInto(it, BackupSettings(historyClearedAt = TimeBounds.MAX_MS)) }
        assertEquals(Instant.ofEpochMilli(now), settings.historyClearedAt.first())
        store.edit { settings.restoreInto(it, BackupSettings(historyClearedAt = Long.MAX_VALUE)) }
        assertNull(settings.historyClearedAt.first(), "unplausibel: nie gelöscht")
        store.edit { settings.restoreInto(it, BackupSettings(historyClearedAt = now - 1)) }
        assertEquals(Instant.ofEpochMilli(now - 1), settings.historyClearedAt.first())
    }

    @Test
    fun clearHistoryAlsoRemovesSharedExports() = runBlocking {
        val app = RuntimeEnvironment.getApplication().phoneApp
        val export = File(app.cacheDir, "export").apply { mkdirs() }.resolve("phasewake-naechte.csv")
        export.writeText("Nacht;Bewegung;Puls")
        try {
            // Ohne Wear-Dienste endet das Löschen auf der Uhr womöglich nie; die Exporte sind vorher weg.
            withTimeoutOrNull(5_000L) { app.clearHistory() }
            assertFalse(export.exists(), "geteilter Export gelöscht")
            assertTrue(app.nights.load().isEmpty())
        } finally {
            // Der Speicher lebt über den Test hinaus: wieder «nie gelöscht».
            app.phaseWakeStore.edit { it.remove(longPreferencesKey("history_cleared_at")) }
        }
    }
}
