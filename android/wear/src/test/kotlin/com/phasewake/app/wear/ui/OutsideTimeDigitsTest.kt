package com.phasewake.app.wear.ui

import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.complication.NextAlarmComplicationService
import com.phasewake.app.wear.record.RecordControl
import com.phasewake.app.wear.tile.NextAlarmTileService
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audit UI-9, Nachtrag: Zeiten ausserhalb der Bildschirme der App (Mitteilungen, Kachel, Komplikation) mit den Ziffern
 * der Sprache – Arabisch mit ٠–٩, Deutsch mit 0–9.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class OutsideTimeDigitsTest {
    private val before = Locale.getDefault()
    private val zone = ZoneId.of("Europe/Zurich")
    private val ar = Locale.forLanguageTag("ar")
    private val de = Locale.forLanguageTag("de-CH")
    private val start = LocalDateTime.of(2026, 11, 12, 6, 30).atZone(zone).toInstant()
    private val target = LocalDateTime.of(2026, 11, 12, 7, 0).atZone(zone).toInstant()

    @After
    fun restoreLocale() = Locale.setDefault(before)

    private fun context(locale: Locale): Context {
        val config = Configuration(RuntimeEnvironment.getApplication().resources.configuration).apply { setLocale(locale) }
        return RuntimeEnvironment.getApplication().createConfigurationContext(config)
    }

    private fun assertArabic(text: String) {
        assertTrue(text.any { it in '٠'..'٩' }, text)
        assertFalse(text.any { it in '0'..'9' }, text)
    }

    private fun assertLatin(text: String) {
        assertTrue(text.any { it in '0'..'9' }, text)
        assertFalse(text.any { it in '٠'..'٩' }, text)
    }

    @Test
    fun shortTimeUsesDigitsOfLanguage() {
        val arabic = shortTime(start, zone, ar)
        assertArabic(arabic)
        // Dieselben Ziffern wie Zahlen in Texten der Sprache (`getString` mit %d).
        assertTrue(arabic.contains(String.format(ar, "%d", 6)) && arabic.contains(String.format(ar, "%02d", 30)), arabic)
        assertEquals("06:30", shortTime(start, zone, de))
    }

    @Test
    fun complicationTimeAndPreview() {
        assertArabic(NextAlarmComplicationService.shownText(start, zone, ar))
        assertEquals("06:30", NextAlarmComplicationService.shownText(start, zone, de))
        assertArabic(NextAlarmComplicationService.previewText(ar))
        assertEquals("06:30", NextAlarmComplicationService.previewText(de))
    }

    @Test
    fun tileWindowAndExact() {
        val window = WakePlan("a", start, target)
        val exact = window.copy(exact = true)
        assertArabic(NextAlarmTileService.tileTexts(context(ar), window, zone, ar).first)
        assertArabic(NextAlarmTileService.tileTexts(context(ar), exact, zone, ar).first)
        assertEquals("06:30 – 07:00", NextAlarmTileService.tileTexts(context(de), window, zone, de).first)
        assertLatin(NextAlarmTileService.tileTexts(context(de), exact, zone, de).first)
    }

    @Test
    fun recordingNotification() {
        Locale.setDefault(ar)
        assertArabic(text(RecordControl.notification(context(ar), Instant.now())))
        Locale.setDefault(de)
        assertLatin(text(RecordControl.notification(context(de), Instant.now())))
    }

    private fun text(notification: Notification): String = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
}
