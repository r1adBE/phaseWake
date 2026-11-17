package com.phasewake.app.shared

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import com.phasewake.app.core.DiagDevice
import com.phasewake.app.core.DiagKind
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executor
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Prozessenden im Diagnose-Protokoll (Audit PERF-03): beim Start nur neue Einträge (nach dem gemerkten Zeitpunkt),
 * älteste zuerst, als Fehler mit Grund, Status, Wichtigkeit, Zeit und Speicher – ohne Beschreibung.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExitReasonsTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Europe/Zurich")

    /** 17.11.2026 02:10 und 02:20 in Zürich (MEZ, UTC+1). */
    private val at1 = Instant.parse("2026-11-17T01:10:00Z").toEpochMilli()
    private val at2 = Instant.parse("2026-11-17T01:20:00Z").toEpochMilli()
    private val manager get() = shadowOf(app.getSystemService(ActivityManager::class.java))

    private fun exit(reason: Int, at: Long, description: String = "") =
        ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setProcessName(app.packageName)
            .setPid(42)
            .setReason(reason)
            .setStatus(9)
            .setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE)
            .setPss(51_200)
            .setTimestamp(at)
            .setDescription(description)
            .build()

    @Test
    fun onlyNewExitsAreLoggedOnceWithoutDescription() {
        val file = File(app.filesDir, "exit-test.log").also { it.delete() }
        val diag = DiagnosticsLog(file, DiagDevice.WATCH, executor = Executor { it.run() })
        manager.addApplicationExitInfo(exit(ApplicationExitInfo.REASON_LOW_MEMORY, at1, "geheim"))
        manager.addApplicationExitInfo(exit(ApplicationExitInfo.REASON_ANR, at2))
        ExitReasons.logTo(app, diag, zone)
        val first = diag.entries()
        assertEquals(listOf(DiagKind.ERROR, DiagKind.ERROR), first.map { it.kind })
        assertEquals(
            listOf(
                "exit low_memory at 2026-11-17 02:10 status=9 importance=${ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE} pss=51200",
                "exit anr at 2026-11-17 02:20 status=9 importance=${ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE} pss=51200",
            ),
            first.map { it.detail },
        )
        assertTrue(first.none { it.detail.contains("geheim") })
        // Nächster Start ohne neues Prozessende: nichts dazu; ein neues (Freezer, ab API 33) kommt allein.
        ExitReasons.logTo(app, diag, zone)
        assertEquals(2, diag.entries().size)
        manager.addApplicationExitInfo(exit(14, at2 + 60_000))
        ExitReasons.logTo(app, diag, zone)
        assertEquals("exit freezer at 2026-11-17 02:21", diag.entries().last().detail.substringBefore(" status"))
        assertEquals(3, diag.entries().size)
        file.delete()
    }

    @Test
    fun unknownReasonIsANumber() {
        assertEquals("reason_99", ExitReasons.reasonName(99))
        assertEquals("crash_native", ExitReasons.reasonName(ApplicationExitInfo.REASON_CRASH_NATIVE))
    }
}
