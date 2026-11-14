package com.phasewake.app.shared

import android.content.Context
import android.os.UserManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Der DataStore der App: vor dem ersten Entsperren kein Zugriff (Audit SP-4), eine kaputte Datei bleibt als Kopie und
 * der leere Stand ist als solcher markiert (Audit SP-7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhaseWakeStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val users get() = shadowOf(context.getSystemService(UserManager::class.java))
    private val key = stringPreferencesKey("test_sp4")

    @After
    fun unlock() {
        users.setUserUnlocked(true)
        runBlocking { context.phaseWakeStore.edit { it.remove(key) } }
    }

    @Test
    fun noAccessBeforeFirstUnlock() = runBlocking {
        context.phaseWakeStore.edit { it[key] = "w1;w2" }
        // Gesperrt: Lesen und Schreiben scheitern sofort, statt einen leeren Stand zu liefern und zu behalten.
        users.setUserUnlocked(false)
        assertFailsWith<IllegalStateException> { context.phaseWakeStore.data.first() }
        assertFailsWith<IllegalStateException> { context.phaseWakeStore.edit { it.remove(key) } }
        // Nach dem Entsperren, gleicher Prozess: der echte Stand, unverändert.
        users.setUserUnlocked(true)
        assertEquals("w1;w2", context.phaseWakeStore.data.first()[key])
    }

    @Test
    fun brokenFileIsKeptAndTheEmptyStateMarked() = runBlocking {
        val dir = Files.createTempDirectory("store").toFile()
        val file = File(dir, "phasewake.preferences_pb")
        val garbage = byteArrayOf(0x0a, 0x7f, 0x01, 0x02, 0x03)
        file.writeBytes(garbage)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PhaseWakeStore.create(file, scope) { 1_234L }
        val prefs = store.data.first()
        assertTrue(PhaseWakeStore.wasReset(prefs))
        assertEquals(1_234L, prefs[PhaseWakeStore.RESET_AT])
        assertNull(prefs[key])
        assertContentEquals(garbage, File(dir, "phasewake.preferences_pb.broken").readBytes(), "Kopie der kaputten Datei")
        // Die Marke bleibt beim nächsten Schreiben; ein heiler Stand trägt keine.
        store.edit { it[key] = "neu" }
        assertTrue(PhaseWakeStore.wasReset(store.data.first()))
        scope.cancel()
        val fresh = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val other = PhaseWakeStore.create(File(dir, "heil.preferences_pb"), fresh)
        assertFalse(PhaseWakeStore.wasReset(other.data.first()))
        fresh.cancel()
    }
}
