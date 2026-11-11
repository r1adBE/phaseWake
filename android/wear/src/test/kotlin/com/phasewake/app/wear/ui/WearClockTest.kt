package com.phasewake.app.wear.ui

import android.provider.Settings
import com.phasewake.app.wear.QuietWear
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audit N-10: Die Galaxy Watch zeigte Zeiten immer im Format der Sprache (Deutsch 24 h, Englisch 12 h), auch wenn die
 * Uhr auf das andere Format gestellt war. Jetzt folgen sie der 12/24-h-Einstellung ([WearClock]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class WearClockTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val evening = LocalDateTime.of(2026, 11, 12, 19, 5).atZone(zone).toInstant()

    @Test
    fun twentyFourHoursInEnglish() {
        val text = shortTime(evening, zone, Locale.US, is24 = true)
        assertEquals("19:05", text)
    }

    @Test
    fun twelveHoursInGerman() {
        val text = shortTime(evening, zone, Locale.GERMANY, is24 = false)
        assertFalse("19" in text, text)
        assertTrue(text.startsWith("7:05"), text)
    }

    @Test
    fun withoutSettingLanguageDecides() {
        assertEquals("19:05", shortTime(evening, zone, Locale.GERMANY, is24 = null))
    }

    @Test
    fun followsSystemSetting() {
        val app = RuntimeEnvironment.getApplication()
        WearClock.update(app)
        Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, "24")
        assertEquals(true, WearClock.use24Hour)
        assertEquals("19:05", shortTime(evening, zone, Locale.US))
        Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, "12")
        assertEquals(false, WearClock.use24Hour)
        assertFalse("19" in shortTime(evening, zone, Locale.US))
    }

    @Test
    fun arabicTwelveHourKeepsArabicDigits() {
        val text = shortTime(evening, zone, Locale.forLanguageTag("ar"), is24 = false)
        assertTrue(text.any { it in '٠'..'٩' } && text.none { it in '0'..'9' }, text)
    }
}
