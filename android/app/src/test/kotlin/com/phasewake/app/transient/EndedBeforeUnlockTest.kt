package com.phasewake.app.transient

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.UserManager
import com.phasewake.app.OfflineApp
import com.phasewake.app.core.Transient
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.BackupAlarm
import com.phasewake.app.ring.BackupDirectBoot
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Audit SP-4: Probewecker oder Powernap am Handy vor dem ersten Entsperren beendet. Der Speicher der App ist dann nicht
 * lesbar; das Ende wird im geräteverschlüsselten Speicher gemerkt und nach dem Entsperren nachgeholt, ohne dass der
 * Speicher gesperrt gelesen wird (sonst behielte DataStore einen leeren Stand: keine Wecker, kein Zweitwecker).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineApp::class)
class EndedBeforeUnlockTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = context.phoneApp
    private val users get() = shadowOf(context.getSystemService(UserManager::class.java))

    @After
    fun cleanUp() {
        users.setUserUnlocked(true)
        BackupDirectBoot.takeEnded(context)
        runBlocking { app.transient.clear() }
    }

    @Test
    fun endedWhileLockedIsCaughtUpAfterUnlock() {
        val nap = Transient.nap(null, Instant.now(), ZoneId.systemDefault(), 20, "sp4")
        runBlocking { app.transient.set(nap) }
        users.setUserUnlocked(false)
        Transients.endedHere(app, nap.plan.alarmId)
        Transients.endedHere(app, "3f2a")
        assertEquals(listOf(nap.plan.alarmId), BackupDirectBoot.takeEnded(context).also { ids -> ids.forEach { BackupDirectBoot.markEnded(context, it) } })
        users.setUserUnlocked(true)
        assertNotNull(runBlocking { app.transient.stored.first() }, "gesperrt nicht angefasst")
        // Nach dem Entsperren (BOOT_COMPLETED an den Zweitwecker): aufgehoben, die Marke ist abgeholt.
        context.sendBroadcast(Intent(context, BackupAlarm.SystemReceiver::class.java).setAction(Intent.ACTION_BOOT_COMPLETED))
        val end = System.nanoTime() + 5_000_000_000L
        while (runBlocking { app.transient.stored.first() } != null) {
            if (System.nanoTime() > end) fail("Plan nicht aufgehoben")
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertNull(runBlocking { app.transient.stored.first() })
        assertEquals(emptyList(), BackupDirectBoot.takeEnded(context))
    }
}
