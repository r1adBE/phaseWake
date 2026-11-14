package com.phasewake.app.wear

import android.app.Application
import android.content.Context
import android.os.Looper
import com.phasewake.app.core.WakePlan
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.RecordEnd
import com.phasewake.app.wear.schedule.DirectBoot
import com.phasewake.app.wear.schedule.ScheduleRules
import com.phasewake.app.wear.system.StartLock
import com.phasewake.app.wear.wake.OnsetRules
import com.phasewake.app.wear.wake.WakeRules
import com.phasewake.app.wear.wake.WakeService
import java.time.Duration
import java.time.Instant
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dokumentierte Zahlen der Galaxy Watch als Literale (Audit BE-3): Die übrigen Tests rechnen mit der Konstante selbst
 * (`X.CONST ± 1`); hier fällt eine Änderung des Werts auf. Grenzen je Wert − 1 / Wert / Wert + 1, wo eine Regel sie prüft.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DocumentedValuesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val at = Instant.parse("2026-11-03T06:30:00Z")

    @Before
    fun setUp() = StartLock.resetForTest()

    @After
    fun cleanUp() = StartLock.resetForTest()

    @Test
    fun startLockLastsSixtySeconds() {
        assertEquals(60_000L, StartLock.TIMEOUT_MS)
        StartLock.acquire(context)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(59))
        assertTrue(StartLock.held, "nach 59 s noch gehalten")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(StartLock.held, "nach 61 s gelöst")
    }

    @Test
    fun keepGraceIsOneMinute() = assertEquals(Duration.ofMinutes(1), ScheduleRules.KEEP_GRACE)

    @Test
    fun nightEndsTwoHoursAfterTarget() = assertEquals(Duration.ofHours(2), WakeService.NIGHT_HARD_LIMIT)

    @Test
    fun ringTimeoutIsTenMinutes() {
        assertEquals(600_000L, WakeRules.RING_TIMEOUT_MS)
        assertEquals(at.plus(Duration.ofMinutes(10)).plusSeconds(30), WakeRules.resumeAt(at))
    }

    @Test
    fun onsetRunSurvivesGapsUpToTwentyEpochs() {
        assertEquals(20, OnsetRules.MAX_GAP_EPOCHS)
        // Eine ruhige Epoche, dann [gap] ungemessene, dann ruhige bis zur nötigen Strecke (30 Epochen = 15 Min).
        fun findWithGap(gap: Int): Int? {
            val size = 1 + gap + 60
            val measured = List(size) { it == 0 || it > gap }
            return OnsetRules.find(List(size) { 0.001 }, measured, 0.01)
        }
        assertEquals(0, findWithGap(19))
        assertEquals(0, findWithGap(20))
        assertEquals(22, findWithGap(21), "Lücke über 20 Epochen beginnt von vorn")
    }

    @Test
    fun recordingEndsBelowFifteenPercent() {
        assertEquals(15, NightRecording.MIN_BATTERY)
        val start = at.minus(Duration.ofHours(1))
        assertEquals(RecordEnd.BATTERY, NightRecording.endNow(start, at, 14))
        assertNull(NightRecording.endNow(start, at, 15))
        assertNull(NightRecording.endNow(start, at, 16))
    }

    @Test
    fun restartCatchesUpForThirtyMinutes() {
        assertEquals(Duration.ofMinutes(30), DirectBoot.MISSED_DURING_BOOT)
        val saved = DirectBoot.Saved(WakePlan("a", at.minus(Duration.ofMinutes(30)), at), at, watchSound = false, label = "")
        assertTrue(DirectBoot.catchesUp(saved, at.plus(Duration.ofMinutes(29))))
        assertTrue(DirectBoot.catchesUp(saved, at.plus(Duration.ofMinutes(30))))
        assertFalse(DirectBoot.catchesUp(saved, at.plus(Duration.ofMinutes(31))))
    }
}
