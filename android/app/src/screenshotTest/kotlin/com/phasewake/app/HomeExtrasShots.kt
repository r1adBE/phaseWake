package com.phasewake.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Transient
import com.phasewake.app.ui.home.HomeExtras
import com.phasewake.app.ui.home.HomeScreen
import com.phasewake.app.ui.settings.HolidaysGroup
import com.phasewake.app.ui.settings.HolidaysUi
import com.phasewake.app.ui.settings.TestGroup
import com.phasewake.app.ui.theme.ThemeMode
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/** Bildschirmfotos der Startseite mit Powernap, Überspringen und Feiertag sowie der Feiertage in den Einstellungen. */

/** Wie [HomeScreen] rechnet: in der Zeitzone des Geräts (bzw. des Renderers). */
private val zone: ZoneId = ZoneId.systemDefault()

private val extrasPadding = PaddingValues(top = 24.dp, bottom = 96.dp)

/** Wecker «Arbeit»: weckt an Feiertagen nicht. */
private val work = AlarmSpec(id = "work", hour = 7, minute = 0, days = weekdays, label = "Arbeit", skipHolidays = true)

/** Wecker «Sport»: der nächste Termin ist übersprungen (wie nach «Nächstes Mal überspringen»). */
private val sport = AlarmSchedule.skipNext(AlarmSpec(id = "sport", hour = 6, minute = 30, days = weekdays, label = "Sport"), FIXED_NOW, zone)

/** Der nächste Arbeitstag ist ein Feiertag; so erscheint der Hinweis unabhängig von der Zeitzone des Renderers. */
private val holidays: Set<LocalDate> = setOfNotNull(AlarmSchedule.nextSkipDate(work.copy(skipHolidays = false), FIXED_NOW, zone))

/** Startseite ohne letzte Nacht und ohne Nacht-Check, damit Powernap und Wecker-Liste sichtbar bleiben. */
@Composable
private fun HomeWith(mode: ThemeMode, alarms: List<AlarmSpec>, extras: HomeExtras) = Themed(mode) {
    HomeScreen(
        alarms, lastNight = null, check = null, onFix = {}, contentPadding = extrasPadding,
        onAdd = {}, onEdit = {}, onToggle = { _, _ -> }, clock = { FIXED_NOW }, extras = extras,
    )
}

private val napIdle = HomeExtras(onNap = {})

/** Powernap vor 5 Minuten gestartet, 20 Minuten lang. */
private val napRunning = HomeExtras(
    nap = Transient.nap(work, FIXED_NOW.minus(Duration.ofMinutes(5)), zone, 20, "preview"),
    onNap = {},
)

private val skipAndHoliday = HomeExtras(holidays = holidays, onSkip = {})

@PreviewTest @Preview(locale = "de-rCH", name = "Powernap hell", device = PHONE) @Composable
fun NapLight() = HomeWith(ThemeMode.LIGHT, listOf(work), napIdle)

@PreviewTest @Preview(locale = "de-rCH", name = "Powernap dunkel", device = PHONE) @Composable
fun NapDark() = HomeWith(ThemeMode.DARK, listOf(work), napIdle)

@PreviewTest @Preview(locale = "de-rCH", name = "Powernap läuft hell", device = PHONE) @Composable
fun NapRunningLight() = HomeWith(ThemeMode.LIGHT, listOf(work), napRunning)

@PreviewTest @Preview(locale = "de-rCH", name = "Powernap läuft dunkel", device = PHONE) @Composable
fun NapRunningDark() = HomeWith(ThemeMode.DARK, listOf(work), napRunning)

@PreviewTest @Preview(locale = "de-rCH", name = "Übersprungen und Feiertag hell", device = PHONE) @Composable
fun HomeSkipLight() = HomeWith(ThemeMode.LIGHT, listOf(work, sport), skipAndHoliday)

@PreviewTest @Preview(locale = "de-rCH", name = "Übersprungen und Feiertag dunkel", device = PHONE) @Composable
fun HomeSkipDark() = HomeWith(ThemeMode.DARK, listOf(work, sport), skipAndHoliday)

/** Feiertage der Schweiz, Kanton Bern; nächster Feiertag Weihnachten. */
private val holidaysUi = HolidaysUi(
    countries = listOf("AT" to "Österreich", "CH" to "Schweiz", "DE" to "Deutschland"),
    country = "CH",
    regions = listOf("BE" to "Bern", "ZH" to "Zürich"),
    region = "BE",
    next = LocalDate.of(2026, 12, 25),
    onChoose = { _, _ -> },
)

/** Gruppen «Wecker testen» und «Feiertage» der Einstellungen. */
@Composable
private fun HolidaySettings(mode: ThemeMode) = Themed(mode) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TestGroup(onTest = {})
        HolidaysGroup(holidaysUi)
    }
}

@PreviewTest @Preview(locale = "de-rCH", name = "Einstellungen Feiertage hell", device = PHONE) @Composable
fun SettingsHolidaysLight() = HolidaySettings(ThemeMode.LIGHT)

@PreviewTest @Preview(locale = "de-rCH", name = "Einstellungen Feiertage dunkel", device = PHONE) @Composable
fun SettingsHolidaysDark() = HolidaySettings(ThemeMode.DARK)
