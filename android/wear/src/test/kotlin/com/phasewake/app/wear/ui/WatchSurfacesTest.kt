package com.phasewake.app.wear.ui

import android.content.Context
import android.content.res.Configuration
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.QuietWear
import com.phasewake.app.wear.R
import com.phasewake.app.wear.complication.NextAlarmComplicationService
import com.phasewake.app.wear.tile.NextAlarmTileService
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import android.provider.Settings
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Prüfrunde 2, Galaxy Watch: Komplikation mit Zielzeit und Tipp-Aktion (B-4), Kachel ohne doppeltes «Nächster Wecker»
 * und mit Wochentag, wenn der Wecker nicht morgen ist (B-5), Speichern im Editor nach Änderungen am Handy (B-3, B-11).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class WatchSurfacesTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val de = Locale.forLanguageTag("de-CH")
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant()

    /** Fest 24 Std. (die Uhr liest die Einstellung, siehe `WearClock`): Erwartung «07:00». */
    @Before
    fun use24Hour() {
        Settings.System.putString(RuntimeEnvironment.getApplication().contentResolver, Settings.System.TIME_12_24, "24")
        WearClock.update(RuntimeEnvironment.getApplication())
    }

    private fun context(locale: Locale): Context {
        val config = Configuration(RuntimeEnvironment.getApplication().resources.configuration).apply { setLocale(locale) }
        return RuntimeEnvironment.getApplication().createConfigurationContext(config)
    }

    @Test
    fun complicationShowsTargetAndOpensApp() {
        val context = context(de)
        val data = NextAlarmComplicationService.forPlan(context, WakePlan("a", at(18, 6, 40), at(18, 7)), zone, de) as ShortTextComplicationData
        val now = at(17, 22)
        assertEquals("07:00", data.text.getTextAt(context.resources, now).toString())
        assertEquals(context.getString(R.string.tile_next) + " 07:00", data.contentDescription?.getTextAt(context.resources, now).toString())
        assertNotNull(data.tapAction, "Tippen auf dem Zifferblatt tut nichts")
    }

    @Test
    fun tileExactHasNoRepeatedLineAndNamesDistantDay() {
        val context = context(de)
        val next = context.getString(R.string.tile_next)
        // Dienstagabend, Wecker «Genau» am Freitag: Wochentag statt «wie morgen», keine zweite Zeile «Nächster Wecker».
        val friday = WakePlan("a", at(20, 7), at(20, 7), exact = true)
        val far = NextAlarmTileService.tileTexts(context, friday, zone, at(17, 22), de)
        assertEquals("$next · Fr.", far.header)
        assertNull(far.line)
        assertTrue(far.title.contains("07:00"), far.title)
        // Morgen früh: kein Wochentag; mit Fenster die Zeile zum leichten Schlaf.
        val tomorrow = NextAlarmTileService.tileTexts(context, WakePlan("a", at(18, 6, 40), at(18, 7)), zone, at(17, 22), de)
        assertEquals(next, tomorrow.header)
        assertEquals(context.getString(R.string.tile_light_sleep), tomorrow.line)
        // Nach Mitternacht ist es heute: ebenfalls ohne Wochentag.
        assertEquals(next, NextAlarmTileService.tileTexts(context, friday, zone, at(20, 1), de).header)
    }

    @Test
    fun editorSaveAfterChangesElsewhere() {
        fun alarm(id: String) = AlarmSpec(id = id, hour = 7, minute = 0, days = setOf(DayOfWeek.MONDAY))
        val eight = (1..8).map { alarm("a$it") }
        assertEquals(EditorSave.SAVE, editorSave(existed = true, stored = eight, draftId = "a3"))
        assertEquals(EditorSave.GONE, editorSave(existed = true, stored = eight - alarm("a3"), draftId = "a3"))
        assertEquals(EditorSave.FULL, editorSave(existed = false, stored = eight, draftId = "new"))
        assertEquals(EditorSave.SAVE, editorSave(existed = false, stored = eight.take(7), draftId = "new"))
        // Zweites Speichern desselben neuen Weckers (schon gespeichert): bleibt ein Speichern, kein «voll».
        assertEquals(EditorSave.SAVE, editorSave(existed = false, stored = eight.take(7) + alarm("new"), draftId = "new"))
    }
}
