package com.phasewake.app.ui.home

import android.content.Context
import com.phasewake.app.MemoryStore
import com.phasewake.app.QuietApp
import com.phasewake.app.SettingsRepository
import com.phasewake.app.core.DeepLink
import com.phasewake.app.core.Finding
import com.phasewake.app.core.FindingKind
import com.phasewake.app.phoneApp
import com.phasewake.app.ui.history.weeklyDeepLine
import java.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** Texte der Auffälligkeiten (Entscheid r1AD 15.11.2026), Tiefschlaf im Wochenrückblick und «Ausblenden» für 7 Tage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class FindingTextTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun cleanUp() = RuntimeEnvironment.getApplication().phoneApp.appScope.cancel()

    @Test
    fun everyKindHasItsText() {
        assertEquals(
            "On average you took 1 h 8 min to fall asleep. Tip: if you’re still awake after 20 minutes, get up and go back to bed only " +
                "when you feel sleepy. If this lasts for weeks, talk to your doctor.",
            findingText(context, Finding(FindingKind.LONG_LATENCY, 68)),
        )
        assertEquals(
            "Deep sleep made up only about 11 % of your sleep (typically 13–23 %). On nights under 6 hours it was clearly lower: 5 % " +
                "instead of 13 %. Tip: no caffeine from the afternoon on and no screens in the last hour before bed.",
            findingText(context, Finding(FindingKind.LOW_DEEP, 11, DeepLink.SHORT_NIGHTS, 5, 13)),
        )
        assertEquals(
            "Deep sleep made up only about 9 % of your sleep (typically 13–23 %). On nights you went to bed late it was clearly lower: " +
                "4 % instead of 12 %. Tip: no caffeine from the afternoon on and no screens in the last hour before bed.",
            findingText(context, Finding(FindingKind.LOW_DEEP, 9, DeepLink.LATE_BEDTIME, 4, 12)),
        )
        assertEquals(
            "Deep sleep made up only about 12 % of your sleep (typically 13–23 %). Tip: no caffeine from the afternoon on and no screens " +
                "in the last hour before bed.",
            findingText(context, Finding(FindingKind.LOW_DEEP, 12)),
        )
        assertEquals(
            "Nights under 6 hours: 3 of 7. Tip: work out your bedtime from your wake-up time and stick to it.",
            findingText(context, Finding(FindingKind.SHORT_NIGHTS, 3)),
        )
        assertEquals(
            "After falling asleep you were awake about 21 % of the time. Tip: keep your bedroom dark, quiet and cool, and skip alcohol in the evening.",
            findingText(context, Finding(FindingKind.MUCH_AWAKE, 21)),
        )
        assertEquals(
            "Your bedtime varied by up to 2 h 54 min. Tip: go to bed at about the same time every day, weekends included.",
            findingText(context, Finding(FindingKind.BEDTIME_VARIES, 174)),
        )
        assertEquals(
            "On weekends your sleep was shifted by 1 h 31 min compared with weekdays. Tip: keep the same sleep times on weekends too.",
            findingText(context, Finding(FindingKind.SOCIAL_JETLAG, 91)),
        )
        assertEquals("Deep sleep 11 % of your sleep · week before 13 %", weeklyDeepLine(context, 11 to 13))
    }

    @Test
    fun hiddenFindingIsStored() = runBlocking {
        var now = 1_800_000_000_000L
        val settings = SettingsRepository(MemoryStore()) { now }
        assertEquals(emptyMap(), settings.findingsHidden.first())
        settings.hideFinding(FindingKind.LONG_LATENCY)
        now += 1000
        settings.hideFinding(FindingKind.BEDTIME_VARIES)
        assertEquals(
            mapOf(FindingKind.LONG_LATENCY to Instant.ofEpochMilli(1_800_000_000_000L), FindingKind.BEDTIME_VARIES to Instant.ofEpochMilli(now)),
            settings.findingsHidden.first(),
        )
    }
}
