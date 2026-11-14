package com.phasewake.app.ui.home

import android.content.Context
import com.phasewake.app.QuietApp
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
 * Nacht-Check: Hinweis, wenn Handy und Uhr verschiedene Testversionen haben (Entscheid r1AD 14.11.2026). Nur ein
 * Hinweis, nur mit beiden Nummern und nicht zusätzlich zu «Uhr-App veraltet».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class NightCheckBuildTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun collect(phone: Int?, watch: Int?, outdated: Boolean = false) =
        NightCheckState.collect(context, true, true, watchOutdated = outdated, phoneBuild = phone, watchBuild = watch)

    @Test
    fun hintWhenNumbersDiffer() {
        assertEquals(96 to 95, NightCheckState.buildHint(96, 95, watchOutdated = false))
        val state = collect(96, 95)
        assertTrue(Check.BUILD_MISMATCH in state.failed)
        assertEquals(96 to 95, state.builds)
        assertTrue(NightCheckState(listOf(Check.BUILD_MISMATCH), builds = 96 to 95).ready, "nur ein Hinweis, die Karte bleibt bereit")
        assertTrue(Check.BUILD_MISMATCH.advisory && !Check.BUILD_MISMATCH.inReadyList)
    }

    @Test
    fun noHintWhenEqualOrUnknown() {
        assertNull(NightCheckState.buildHint(96, 96, watchOutdated = false))
        assertNull(NightCheckState.buildHint(96, null, watchOutdated = false), "ältere Uhr oder lokaler Build")
        assertNull(NightCheckState.buildHint(null, 95, watchOutdated = false))
        assertFalse(Check.BUILD_MISMATCH in collect(96, 96).failed)
        assertFalse(Check.BUILD_MISMATCH in collect(null, 95).failed)
        assertNull(collect(96, null).builds)
    }

    @Test
    fun onlyOneHintWithOutdatedWatch() {
        assertNull(NightCheckState.buildHint(96, 95, watchOutdated = true))
        val state = collect(96, 95, outdated = true)
        assertTrue(Check.WATCH_OUTDATED in state.failed)
        assertFalse(Check.BUILD_MISMATCH in state.failed)
    }
}
