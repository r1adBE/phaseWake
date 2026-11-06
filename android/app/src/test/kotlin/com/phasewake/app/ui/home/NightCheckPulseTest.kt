package com.phasewake.app.ui.home

import android.content.Context
import com.phasewake.app.QuietApp
import com.phasewake.app.shared.WatchPulse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Nacht-Check: Darf die Uhr den Puls nicht im Hintergrund messen, warnt er rot (blockierend). Ältere Uhren ohne Angabe,
 * eine nicht erreichbare Uhr und eine Uhr, der ohnehin Rechte fehlen, lösen diese Warnung nicht aus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class NightCheckPulseTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun failed(reachable: Boolean = true, ready: Boolean? = true, pulse: WatchPulse?) =
        NightCheckState.collect(context, reachable, ready, watchPulse = pulse)

    @Test
    fun missingBackgroundPulseWarnsAndBlocks() {
        val state = failed(pulse = WatchPulse(heartRate = true, background = false))
        assertTrue(Check.WATCH_PULSE in state.failed)
        assertFalse(Check.WATCH_PULSE.advisory)
        assertFalse(state.ready)
    }

    @Test
    fun noWarningWithoutReportOrWhenAllowed() {
        assertFalse(Check.WATCH_PULSE in failed(pulse = null).failed, "ältere Uhr ohne Angabe")
        assertFalse(Check.WATCH_PULSE in failed(pulse = WatchPulse(heartRate = true, background = true)).failed)
        assertFalse(Check.WATCH_PULSE in failed(reachable = false, pulse = WatchPulse(heartRate = true, background = false)).failed)
        // Fehlen der Uhr schon Rechte, sagt «Phase Wake auf der Uhr» das; nicht doppelt.
        val setup = failed(ready = false, pulse = WatchPulse(heartRate = false, background = false)).failed
        assertTrue(Check.WATCH_SETUP in setup)
        assertFalse(Check.WATCH_PULSE in setup)
    }
}
