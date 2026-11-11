package com.phasewake.app.wear.diag

import android.content.Context
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.DiagReports
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.shared.Outbox
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Antwort der Uhr auf die Anfrage des Handys ([WatchDiag.sendReport]): Der Bericht geht über [Outbox.sendOnce] an das
 * Handy (gross über einen Kanal, geprüft am Handy in `WatchReportTest`); erreicht er keines, steht das im Protokoll der
 * Uhr. Die Wear-Dienste antworten unter Robolectric nie, darum ein [Outbox.Transport] im Test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class WatchDiagTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = context.wearApp
    private val since = Instant.now().minusSeconds(1)

    /** Handy im Test: erreichbar oder nicht, Fehler beim Senden, alles Gesendete. */
    private class Phone(private val reachable: Boolean = true, private val failing: Boolean = false) : Outbox.Transport {
        val sent = mutableListOf<Pair<String, ByteArray>>()

        override suspend fun nodes(capability: String): List<String> = if (reachable) listOf("phone") else emptyList()

        override suspend fun send(node: String, path: String, data: ByteArray) {
            check(!failing) { "Verbindung weg" }
            sent += path to data
        }
    }

    @After
    fun cleanUp() = app.appScope.cancel()

    /** Zeilen «diag report not sent» im Protokoll der Uhr. */
    private fun notSent() = app.diag.entries().count { !it.at.isBefore(since) && it.kind == DiagKind.SYNC && it.detail.startsWith("diag report not sent") }

    /** Sendet über [phone] und wartet, bis das Protokoll (schreibt im Hintergrund) eine Zeile «not sent» mehr hat. */
    private fun sendAndAwaitNotSent(phone: Phone) {
        val before = notSent()
        runBlocking { WatchDiag.sendReport(app, phone) }
        val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (notSent() <= before) {
            check(System.nanoTime() < until) { "nicht im Protokoll: ${app.diag.entries().takeLast(5)}" }
            Thread.sleep(5)
        }
    }

    @Test
    fun reportGoesToThePhone() = runBlocking {
        val phone = Phone()
        WatchDiag.sendReport(app, phone)
        val (path, data) = phone.sent.single()
        assertEquals(DataLayer.PATH_DIAG_LOG, path)
        val report = assertNotNull(DiagReports.decode(data.decodeToString()))
        assertEquals(listOf("version", "next", "battery", "exact", "notifications"), report.status.map { it.first })
    }

    @Test
    fun unreachablePhoneIsLogged() = sendAndAwaitNotSent(Phone(reachable = false))

    @Test
    fun failedSendIsLogged() = sendAndAwaitNotSent(Phone(failing = true))
}
