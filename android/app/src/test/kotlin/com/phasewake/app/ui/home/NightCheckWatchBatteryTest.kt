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
 * Nacht-Check vorab zum Akku der Uhr (Audit O-05, Entscheid r1AD 14.11.2026): unter 30 % misst die Uhr das Einschlafen
 * nicht (Hinweis, keine Kopfzeile «erkennt dein Einschlafen …»), in der Lernphase schon unter 40 % keine Aufzeichnung.
 * Nur Hinweise: Die Karte bleibt «Bereit».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class NightCheckWatchBatteryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun collect(battery: Int?, autoOnset: Boolean = true, learning: Boolean = false) =
        NightCheckState.collect(context, true, true, watchBattery = battery, autoOnset = autoOnset, learning = learning)

    @Test
    fun onsetHintBelowThirtyPercent() {
        assertEquals(30, NightCheckState.WATCH_ONSET_BATTERY_PERCENT)
        assertTrue(Check.WATCH_ONSET_BATTERY in collect(29).failed)
        assertFalse(Check.WATCH_ONSET_BATTERY in collect(30).failed)
        assertFalse(Check.WATCH_ONSET_BATTERY in collect(29, autoOnset = false).failed, "ohne automatische Messung nichts zu melden")
        assertTrue(NightCheckState(listOf(Check.WATCH_ONSET_BATTERY)).ready, "nur ein Hinweis")
        assertTrue(Check.WATCH_ONSET_BATTERY.advisory && !Check.WATCH_ONSET_BATTERY.inReadyList)
    }

    @Test
    fun learningHintBelowFortyPercent() {
        assertEquals(Check.WATCH_LEARN_BATTERY, NightCheckState.batteryHints(39, autoOnset = true, learning = true))
        assertNull(NightCheckState.batteryHints(40, autoOnset = true, learning = true))
        assertNull(NightCheckState.batteryHints(39, autoOnset = true, learning = false))
        // Ohne automatische Messung (nur «Gute Nacht») gilt die Lernphase trotzdem.
        assertEquals(Check.WATCH_LEARN_BATTERY, NightCheckState.batteryHints(35, autoOnset = false, learning = true))
        // Unter 30 % mit Messung: nur ein Hinweis, der der Messung (sie startet die Aufzeichnung).
        assertEquals(Check.WATCH_ONSET_BATTERY, NightCheckState.batteryHints(25, autoOnset = true, learning = true))
        assertTrue(Check.WATCH_LEARN_BATTERY in collect(35, learning = true).failed)
        assertTrue(NightCheckState(listOf(Check.WATCH_LEARN_BATTERY)).ready, "nur ein Hinweis")
    }

    @Test
    fun noHintWhenUnknownOrAlreadyRed() {
        assertNull(NightCheckState.batteryHints(null, autoOnset = true, learning = true))
        // Unter 20 % steht schon «Akku der Uhr» (rot); kein zweiter Hinweis.
        val low = collect(15, learning = true)
        assertTrue(Check.WATCH_BATTERY in low.failed)
        assertFalse(Check.WATCH_ONSET_BATTERY in low.failed || Check.WATCH_LEARN_BATTERY in low.failed)
    }

    @Test
    fun noWarningWhileTheWatchCharges() {
        // Prüfrunde 2 F-6 (wie iPhone `watchChecks` mit `charging`): Am Ladegerät misst die Uhr trotz 25 % bzw. 15 %.
        val charging = NightCheckState.collect(context, true, true, watchBattery = 25, autoOnset = true, learning = true, watchCharging = true)
        assertFalse(Check.WATCH_ONSET_BATTERY in charging.failed || Check.WATCH_LEARN_BATTERY in charging.failed)
        val low = NightCheckState.collect(context, true, true, watchBattery = 15, autoOnset = true, watchCharging = true)
        assertFalse(Check.WATCH_BATTERY in low.failed)
        assertFalse(NightCheckState.onsetSkipped(charging))
    }

    @Test
    fun headerLineGoesAwayWhenOnsetIsSkipped() {
        assertTrue(NightCheckState.onsetSkipped(collect(29)))
        assertTrue(NightCheckState.onsetSkipped(collect(15)))
        assertFalse(NightCheckState.onsetSkipped(collect(30)))
        assertFalse(NightCheckState.onsetSkipped(collect(35, learning = true)), "Lernphase: die Messung startet trotzdem")
        assertFalse(NightCheckState.onsetSkipped(null))
    }
}
