package com.phasewake.app.ui.home

import android.content.Context
import android.content.res.Configuration
import com.phasewake.app.MemoryStore
import com.phasewake.app.QuietApp
import com.phasewake.app.SettingsRepository
import com.phasewake.app.core.DeepLink
import com.phasewake.app.core.Finding
import com.phasewake.app.core.FindingKind
import com.phasewake.app.phoneApp
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.history.weeklyDeepLine
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Locale
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
import kotlin.test.assertTrue

/** Texte der Auffälligkeiten, Tiefschlaf im Wochenrückblick und «Ausblenden» für 7 Tage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class FindingTextTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun cleanUp() = RuntimeEnvironment.getApplication().phoneApp.appScope.cancel()

    @Test
    fun everyKindHasItsText() {
        assertEquals(
            "On average you took 1\u00a0h 8\u00a0min to fall asleep. " +
                "Tip: if you’re still awake after 20 minutes, " +
                "get up and go back to bed only " +
                "when you feel sleepy. If this lasts for weeks, talk to your doctor.",
            findingText(context, Finding(FindingKind.LONG_LATENCY, 68)),
        )
        assertEquals(
            "Deep sleep made up only about 11 % of your sleep (typically 13–23 %). " +
                "On nights under 6 hours it was clearly lower: 5 % " +
                "instead of 13 %. Tip: no caffeine from the afternoon on and no screens in the last hour before bed.",
            findingText(context, Finding(FindingKind.LOW_DEEP, 11, DeepLink.SHORT_NIGHTS, 5, 13)),
        )
        assertEquals(
            "Deep sleep made up only about 9 % of your sleep (typically 13–23 %). " +
                "On nights you went to bed late it was clearly lower: " +
                "4 % instead of 12 %. Tip: no caffeine from the afternoon on and no screens in the last hour " +
                    "before bed.",
            findingText(context, Finding(FindingKind.LOW_DEEP, 9, DeepLink.LATE_BEDTIME, 4, 12)),
        )
        assertEquals(
            "Deep sleep made up only about 12 % of your sleep (typically 13–23 %). " +
                "Tip: no caffeine from the afternoon on and no screens " +
                "in the last hour before bed.",
            findingText(context, Finding(FindingKind.LOW_DEEP, 12)),
        )
        assertEquals(
            "Nights under 6 hours: 3 of 7. Tip: work out your bedtime from your target time and stick to it.",
            findingText(context, Finding(FindingKind.SHORT_NIGHTS, 3)),
        )
        assertEquals(
            "After falling asleep you were awake about 21 % of the time. " +
                "Tip: keep your bedroom dark, quiet and cool, and skip alcohol in the evening.",
            findingText(context, Finding(FindingKind.MUCH_AWAKE, 21)),
        )
        assertEquals(
            "Your bedtime varied by up to 2\u00a0h 54\u00a0min. " +
                "Tip: go to bed at about the same time every day, weekends included.",
            findingText(context, Finding(FindingKind.BEDTIME_VARIES, 174)),
        )
        assertEquals(
            "On weekends your sleep was shifted by 1\u00a0h 31\u00a0min compared with weekdays. " +
                "Tip: keep the same sleep times on weekends too.",
            findingText(context, Finding(FindingKind.SOCIAL_JETLAG, 91)),
        )
        assertEquals("Deep sleep 11 % of your sleep · week before 13 %", weeklyDeepLine(context, 11 to 13))
    }

    /**
     * Stunden und Minuten bleiben je mit ihrer Einheit zusammen, umbrechen darf nur zwischen den beiden
     * Teilen («6 Std.⏎17 Min», nicht «6 Std. 17 Mi⏎n»). Gilt für alle Sprachen von App und Uhr.
     */
    @Test
    fun durationBreaksOnlyBetweenHoursAndMinutes() {
        val german = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.GERMAN) },
        )
        assertEquals("6\u00a0Std. 17\u00a0Min", Format.duration(german, Duration.ofMinutes(377)))
        val entry = Regex("<string name=\"duration_hours_minutes\">(.*?)</string>")
        val values = listOf("src/main/res", "../wear/src/main/res").flatMap { dir ->
            File(dir).listFiles().orEmpty().map { File(it, "strings.xml") }.filter { it.exists() }
        }.mapNotNull { file -> entry.find(file.readText())?.let { file.path to it.groupValues[1] } }
        assertEquals(148, values.size)
        for ((path, value) in values) {
            if (value.none { it == ' ' || it == '\u00a0' }) continue // z. B. Japanisch ganz ohne Abstand
            val parts = value.split(' ')
            assertEquals(2, parts.size, "$path: $value")
            assertTrue("%1\$d" in parts[0] && "%2\$d" in parts[1], "$path: $value")
        }
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
            mapOf(
                FindingKind.LONG_LATENCY to Instant.ofEpochMilli(1_800_000_000_000L),
                FindingKind.BEDTIME_VARIES to Instant.ofEpochMilli(now),
            ),
            settings.findingsHidden.first(),
        )
    }
}
