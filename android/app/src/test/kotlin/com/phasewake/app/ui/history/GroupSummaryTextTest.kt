package com.phasewake.app.ui.history

import android.content.Context
import com.phasewake.app.QuietApp
import com.phasewake.app.core.HistoryGroupSummary
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** Zeile unter einer Woche oder einem Jahr: ein «Ø», Befinden als Wert von 5 mit dem Komma der Sprache. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class GroupSummaryTextTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    @Config(qualifiers = "de-rCH")
    fun swissGermanUsesAComma() {
        assertEquals(
            "3 Nächte · Ø 7\u00A0Std. 27\u00A0Min Schlaf · Befinden: 1,5/5",
            groupSummaryText(context, HistoryGroupSummary(nights = 3, asleepMinutes = 447, feeling = 1.5)),
        )
    }

    @Test
    @Config(qualifiers = "en")
    fun englishUsesAPoint() {
        assertEquals(
            "1 night · Feeling: 4.0/5",
            groupSummaryText(context, HistoryGroupSummary(nights = 1, asleepMinutes = null, feeling = 4.0)),
        )
    }

    @Test
    @Config(qualifiers = "de-rCH")
    fun onlyWhatIsKnown() {
        assertEquals("2 Nächte", groupSummaryText(context, HistoryGroupSummary(2, null, null)))
    }
}
