package com.phasewake.app.ui.home

import android.content.Context
import com.phasewake.app.QuietApp
import com.phasewake.app.core.RecorderSkip
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Nacht-Check (Audit F-06): Hat die Uhr die Messung vor dem Schlafen wegen zu schwachen Akkus nicht gestartet, ist er
 * rot (wie auf dem iPhone). Ohne Meldung und mit einem Grund, den es nur auf der Apple Watch gibt, nicht.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class NightCheckRecorderTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun failed(skip: RecorderSkip.Reason?) = NightCheckState.collect(context, true, true, recorderSkip = skip)

    @Test
    fun skippedMeasurementBlocks() {
        val state = failed(RecorderSkip.Reason.BATTERY)
        assertTrue(Check.WATCH_RECORDER in state.failed)
        assertFalse(Check.WATCH_RECORDER.advisory)
        assertFalse(state.ready)
    }

    @Test
    fun noWarningWithoutReportOrForOtherReasons() {
        assertFalse(Check.WATCH_RECORDER in failed(null).failed, "keine Meldung (auch ältere Uhr)")
        assertFalse(Check.WATCH_RECORDER in failed(RecorderSkip.Reason.MOTION).failed)
        assertFalse(Check.WATCH_RECORDER.inReadyList)
    }
}
