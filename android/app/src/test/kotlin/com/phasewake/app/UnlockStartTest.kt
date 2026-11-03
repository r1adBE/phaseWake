package com.phasewake.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.UserManager
import com.phasewake.app.backup.RestoreJournal
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.WakeReason
import com.phasewake.app.ring.BackupAlarm
import com.phasewake.app.shared.NightRepository
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PhaseWakeApp], gestartet vor dem ersten Entsperren nach einem Neustart (wie vom Zweitwecker oder LOCKED_BOOT_COMPLETED).
 */
class LockedStartApp : PhaseWakeApp() {
    override fun onCreate() {
        shadowOf(getSystemService(UserManager::class.java)).setUserUnlocked(false)
        super.onCreate()
    }
}

/**
 * Start nach dem Entsperren (Review K-10): Startet der Prozess gesperrt, holt erst ACTION_USER_UNLOCKED die
 * Start-Arbeiten ([PhaseWakeApp.initUnlocked]) nach, genau einmal; ein späteres BOOT_COMPLETED tut nichts mehr. Dabei
 * wird ein unterbrochenes Wiederherstellen ([RestoreJournal]) zurückgesetzt. Ob die Start-Arbeiten liefen, zeigt der
 * Export-Ordner im Cache: Sie löschen ihn als Erstes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LockedStartApp::class)
class UnlockStartTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val dir: File get() = context.filesDir
    private val nightsFile get() = File(dir, "nights.v1")
    private val exportDir get() = File(context.cacheDir, "export")

    private fun night(id: String, start: Long) =
        NightRecord(id, Instant.ofEpochMilli(start), Instant.ofEpochMilli(start + 1_800_000), Instant.ofEpochMilli(start + 900_000), WakeReason.DEADLINE, 0, emptyList())

    private val old = listOf(night("alt", 1_000_000))
    private val restored = listOf(night("neu", 2_000_000))

    @After
    fun cleanUp() {
        // Abholen von der Uhr und Neuplanen laufen im Hintergrund weiter: nicht in den nächsten Test hinein.
        context.phoneApp.appScope.cancel()
    }

    /** Wiederherstellen bis zum Tausch der Nächte, dann «Prozessende» (der DataStore hat die Kennung nicht). */
    private fun interruptedRestore() = runBlocking {
        val nights = NightRepository(nightsFile)
        nights.replaceAll(old)
        RestoreJournal(dir).begin("t1", nights.load())
        nights.replaceAll(restored)
    }

    /** Ein früher geteilter Export liegt im Cache. */
    private fun markExport() = assertTrue(exportDir.mkdirs())

    @Test
    fun unlockRunsStartWorkOnceAndRecoversRestore() {
        // Gesperrt gestartet: keine Start-Arbeiten, das Journal bleibt offen.
        interruptedRestore()
        markExport()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(exportDir.exists(), "vor dem Entsperren nichts gelöscht")

        // Entsperren: Start-Arbeiten laufen, das unterbrochene Wiederherstellen wird zurückgesetzt.
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(exportDir.exists(), "Start-Arbeiten nach dem Entsperren gelaufen")
        runBlocking {
            withTimeout(Duration.ofSeconds(10).toMillis()) {
                while (RestoreJournal(dir).pendingToken() != null) delay(10)
            }
            assertEquals(old, NightRepository(nightsFile).load())
        }

        // BOOT_COMPLETED danach (gleicher Prozess): kein zweiter Durchlauf.
        markExport()
        context.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setComponent(ComponentName(context, BackupAlarm.SystemReceiver::class.java)))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(exportDir.exists(), "Start-Arbeiten nur einmal je Prozess")
        // Auch ein direkter weiterer Aufruf tut nichts.
        context.phoneApp.initUnlocked()
        assertTrue(exportDir.exists(), "Start-Arbeiten nur einmal je Prozess")
    }
}
