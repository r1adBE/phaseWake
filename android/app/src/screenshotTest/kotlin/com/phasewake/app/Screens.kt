package com.phasewake.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.PhoneAlert
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.StageSpan
import com.phasewake.app.core.WakeReason
import com.phasewake.app.core.WindowAdvice
import com.phasewake.app.ring.PhoneRingService
import com.phasewake.app.ring.RingScreen
import com.phasewake.app.ring.SunriseScreen
import com.phasewake.app.core.Sunrise
import com.phasewake.app.ui.editor.EditorScreen
import com.phasewake.app.ui.history.HistoryCharts
import com.phasewake.app.ui.history.HistoryChartsStacked
import com.phasewake.app.ui.history.HistoryScreen
import com.phasewake.app.ui.home.Check
import com.phasewake.app.ui.home.HomeScreen
import com.phasewake.app.ui.home.NightCheckState
import com.phasewake.app.core.Trends
import com.phasewake.app.ui.settings.HealthState
import com.phasewake.app.ui.settings.SettingsScreen
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ui.theme.ThemeMode
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant

/** Bildschirmfotos aller Handy-Bildschirme, hell und dunkel. Beispieldaten, keine echten. */

/** Feste Testzeit: Mittwoch 28.10.2026, 07:20 in Zürich. Bilder hängen so nicht vom Tag des Laufs ab. */
private val FIXED_NOW: Instant = java.time.ZonedDateTime.of(2026, 10, 28, 7, 20, 0, 0, java.time.ZoneId.of("Europe/Zurich")).toInstant()

private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

private val alarms = listOf(
    AlarmSpec(id = "work", hour = 7, minute = 0, days = weekdays, label = "Arbeit", sunrise = Sunrise.WINDOW),
    AlarmSpec(id = "weekend", hour = 9, minute = 30, days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), enabled = false, phone = PhoneAlert.VIBRATE, sleepDurationMinutes = 450),
)

private fun night(daysAgo: Long, wokeMinute: Long, reason: WakeReason): NightRecord {
    // Relativ zur festen Testzeit, damit die jüngste Nacht als «Dein Morgen» erscheint.
    val start = FIXED_NOW.minus(Duration.ofDays(daysAgo)).minus(Duration.ofHours(2))
    val epochs = List((wokeMinute * 2).toInt()) { i ->
        val m = when {
            i % 9 == 4 -> 0.0015
            i >= wokeMinute * 2 - 3 -> 0.004 + (i % 3) * 0.002
            else -> 0.0002
        }
        Epoch(m, 52.0 + (i % 5))
    }
    val feeling = when (daysAgo) { 1L -> 3; 2L -> 4; else -> null }
    return NightRecord("work", start, start.plus(Duration.ofMinutes(30)), start.plus(Duration.ofMinutes(wokeMinute)), reason, if (daysAgo == 2L) 1 else 0, epochs, feeling)
}

private val nights = listOf(night(2, 30, WakeReason.DEADLINE), night(1, 22, WakeReason.AWAKE), night(0, 11, WakeReason.LIGHT_SLEEP))

private val stages = nights.last().let { n ->
    listOf(
        StageSpan(n.windowStart, n.windowStart.plusSeconds(360), SleepStage.DEEP),
        StageSpan(n.windowStart.plusSeconds(360), n.windowStart.plusSeconds(900), SleepStage.REM),
        StageSpan(n.windowStart.plusSeconds(900), n.target, SleepStage.LIGHT),
    )
}

private val padding = PaddingValues(top = 24.dp, bottom = 96.dp)

@Composable
private fun Themed(mode: ThemeMode, content: @Composable () -> Unit) {
    // Auf dem Gerät folgt die Standard-Sprache der App-Sprache; im Renderer muss man sie setzen.
    java.util.Locale.setDefault(java.util.Locale.forLanguageTag("de-CH"))
    PhaseWakeTheme(mode) { Surface { content() } }
}

@Composable
private fun Home(mode: ThemeMode, list: List<AlarmSpec>, check: NightCheckState = NightCheckState(emptyList())) = Themed(mode) {
    HomeScreen(list, nights.last(), check = check, onFix = {}, contentPadding = padding, onAdd = {}, onEdit = {}, onToggle = { _, _ -> }, clock = { FIXED_NOW })
}

@PreviewTest @Preview(locale = "de-rCH", name = "Startseite hell", device = PHONE) @Composable
fun HomeLight() = Home(ThemeMode.LIGHT, alarms)

@PreviewTest @Preview(locale = "de-rCH", name = "Startseite dunkel", device = PHONE) @Composable
fun HomeDark() = Home(ThemeMode.DARK, alarms)

@PreviewTest @Preview(locale = "de-rCH", name = "Startseite leer hell", device = PHONE) @Composable
fun HomeEmptyLight() = Home(ThemeMode.LIGHT, emptyList())

@PreviewTest @Preview(locale = "de-rCH", name = "Startseite leer dunkel", device = PHONE) @Composable
fun HomeEmptyDark() = Home(ThemeMode.DARK, emptyList())

@PreviewTest @Preview(locale = "de-rCH", name = "Editor hell", device = PHONE_TALL) @Composable
fun EditorLight() = Themed(ThemeMode.LIGHT) { EditorScreen(alarms.first(), advice = WindowAdvice.Suggestion(30, 4, 6), onBack = {}, onSave = {}, onDelete = {}) }

@PreviewTest @Preview(locale = "de-rCH", name = "Editor dunkel", device = PHONE_TALL) @Composable
fun EditorDark() = Themed(ThemeMode.DARK) { EditorScreen(alarms.first(), advice = WindowAdvice.Suggestion(30, 4, 6), onBack = {}, onSave = {}, onDelete = {}) }

@PreviewTest @Preview(locale = "de-rCH", name = "Nacht-Check offen", device = PHONE) @Composable
fun HomeCheck() = Home(ThemeMode.LIGHT, alarms, NightCheckState(listOf(Check.WATCH, Check.DND)))

private val trends = Trends(asleepThisWeek = 432, asleepLastWeek = 401, bedtimeSpreadMinutes = 18, feelingThisWeek = 3.8, feelingLastWeek = 3.2)

/** Diagramme: 14 Nächte mit Weckfenster 06:30–07:00, Schlaf aus der Gesundheits-App, Befinden und Notizen. */
private val chartZone = java.time.ZoneId.systemDefault()
private val chartMorning = java.time.LocalDate.of(2026, 10, 28)
private fun chartAt(daysAgo: Long, hour: Int, minute: Int): Instant =
    chartMorning.minusDays(daysAgo).atTime(hour, minute).atZone(chartZone).toInstant()

/** Je Nacht (0 = heute Morgen): Minuten vor der Zielzeit, Grund, Befinden, Einschlafen (Minuten nach 22:00), Schlaf in Minuten. */
private val chartPlan = listOf(
    Triple(12, WakeReason.LIGHT_SLEEP, 4) to (70 to 448), Triple(0, WakeReason.DEADLINE, 3) to (95 to 405),
    Triple(18, WakeReason.LIGHT_SLEEP, 5) to (55 to 470), Triple(6, WakeReason.AWAKE, 4) to (80 to 452),
    Triple(-4, WakeReason.DEADLINE, 2) to (140 to 372), Triple(9, WakeReason.LIGHT_SLEEP, 4) to (60 to 462),
    Triple(21, WakeReason.LIGHT_SLEEP, 4) to (75 to 455), Triple(3, WakeReason.AWAKE, 3) to (100 to 420),
    Triple(0, WakeReason.DEADLINE, 3) to (85 to 430), Triple(14, WakeReason.LIGHT_SLEEP, 4) to (65 to 458),
    Triple(11, WakeReason.LIGHT_SLEEP, 3) to (110 to 398), Triple(0, WakeReason.DEADLINE, 2) to (125 to 380),
    Triple(16, WakeReason.LIGHT_SLEEP, 4) to (70 to 451), Triple(8, WakeReason.LIGHT_SLEEP, 3) to (90 to 428),
)

private val chartNights = chartPlan.mapIndexed { i, (wake, _) ->
    val start = chartAt(i.toLong(), 6, 30)
    val target = start.plus(Duration.ofMinutes(30))
    NightRecord("work", start, target, target.minus(Duration.ofMinutes(wake.first.toLong())), wake.second, if (wake.first < 0) 1 else 0, emptyList(), wake.third)
}.reversed()

private val chartSleeps = chartPlan.mapIndexedNotNull { i, (_, sleep) ->
    // Zwei Nächte ohne Daten (Uhr nicht getragen).
    if (i == 5 || i == 9) return@mapIndexedNotNull null
    val start = chartAt(i + 1L, 22, 0).plus(Duration.ofMinutes(sleep.first.toLong()))
    com.phasewake.app.core.SleepSummary(start, chartAt(i.toLong(), 6, 50), sleep.second.toLong())
}.reversed()

/** Schlafphasen der jüngsten Nacht: Zyklen von Leicht, Tief, Leicht und REM, am Morgen leicht. */
private val chartStages = buildList {
    var t = chartAt(1, 23, 10)
    val cycle = listOf(SleepStage.AWAKE to 10, SleepStage.LIGHT to 25, SleepStage.DEEP to 45, SleepStage.LIGHT to 20, SleepStage.REM to 20)
    val end = chartAt(0, 6, 50)
    var k = 0
    while (t.isBefore(end)) {
        val (stage, minutes) = cycle[k % cycle.size]
        // Gegen Morgen weniger Tiefschlaf, mehr REM und leichter Schlaf.
        val length = if (stage == SleepStage.DEEP) maxOf(10, minutes - k * 4) else minutes + (if (stage == SleepStage.REM) k * 2 else 0)
        val next = minOf(t.plus(Duration.ofMinutes(length.toLong())), end)
        // Wach nur beim Einschlafen und einmal kurz um 03 Uhr (fünfter Zyklus).
        add(StageSpan(t, next, if (stage == SleepStage.AWAKE && k != 0 && k != 20) SleepStage.LIGHT else stage))
        t = next
        k++
    }
}

private val chartNotes = mapOf(
    chartMorning.minusDays(2) to listOf(com.phasewake.app.core.SleepNotes.CAFFEINE),
    chartMorning.minusDays(5) to listOf(com.phasewake.app.core.SleepNotes.ALCOHOL, com.phasewake.app.core.SleepNotes.LATE_MEAL),
    chartMorning.minusDays(9) to listOf(com.phasewake.app.core.SleepNotes.EXERCISE),
)

private val charts = HistoryCharts.of(chartNights, chartSleeps, chartStages, chartNotes, alarms, 450, FIXED_NOW, chartZone)

@PreviewTest @Preview(locale = "de-rCH", name = "Verlauf hell", device = PHONE_TALL) @Composable
fun HistoryLight() = Themed(ThemeMode.LIGHT) { HistoryScreen(nights, padding, loadStages = { stages }, onExport = {}, trends = trends, healthConnected = true, charts = charts) }

@PreviewTest @Preview(locale = "de-rCH", name = "Verlauf dunkel", device = PHONE_TALL) @Composable
fun HistoryDark() = Themed(ThemeMode.DARK) { HistoryScreen(nights, padding, loadStages = { stages }, onExport = {}, trends = trends, healthConnected = false, charts = charts) }

@PreviewTest @Preview(locale = "de-rCH", name = "Verlauf Diagramme hell", device = PHONE_TALL) @Composable
fun HistoryChartsLight() = Themed(ThemeMode.LIGHT) { HistoryChartsStacked(charts, emptyList(), Modifier.padding(16.dp)) }

@PreviewTest @Preview(locale = "de-rCH", name = "Verlauf Diagramme dunkel", device = PHONE_TALL) @Composable
fun HistoryChartsDark() = Themed(ThemeMode.DARK) { HistoryChartsStacked(charts, emptyList(), Modifier.padding(16.dp)) }

@PreviewTest @Preview(locale = "de-rCH", name = "Einstellungen hell", device = PHONE) @Composable
fun SettingsLight() = Themed(ThemeMode.LIGHT) {
    SettingsScreen(ThemeMode.LIGHT, "1.0", padding, onThemeMode = {}, onExport = {}, onClearHistory = {}, health = HealthState.CONNECTED, onConnectHealth = {})
}

@PreviewTest @Preview(locale = "de-rCH", name = "Einstellungen dunkel", device = PHONE) @Composable
fun SettingsDark() = Themed(ThemeMode.DARK) {
    SettingsScreen(ThemeMode.DARK, "1.0", padding, onThemeMode = {}, onExport = {}, onClearHistory = {}, health = HealthState.AVAILABLE, onConnectHealth = {})
}

@PreviewTest @Preview(locale = "de-rCH", name = "Wecken Handy", device = PHONE) @Composable
fun RingPhone() = Themed(ThemeMode.DARK) {
    RingScreen(PhoneRingService.State.Ringing(fromWatch = true, snoozesLeft = 3), onStop = {}, onSnooze = {}, clock = { FIXED_NOW })
}

@PreviewTest @Preview(locale = "de-rCH", name = "Sonnenaufgang Anfang", device = PHONE) @Composable
fun SunriseEarly() = Themed(ThemeMode.DARK) {
    SunriseScreen(progress = 0.3f, now = java.time.LocalDate.now().atTime(6, 39).atZone(java.time.ZoneId.systemDefault()).toInstant(), onOff = {})
}

@PreviewTest @Preview(locale = "de-rCH", name = "Sonnenaufgang Mitte", device = PHONE) @Composable
fun SunriseMiddle() = Themed(ThemeMode.DARK) {
    SunriseScreen(progress = 0.55f, now = java.time.LocalDate.now().atTime(6, 46).atZone(java.time.ZoneId.systemDefault()).toInstant(), onOff = {})
}

@PreviewTest @Preview(locale = "de-rCH", name = "Wecken mit Sonnenaufgang", device = PHONE) @Composable
fun RingSunrise() = Themed(ThemeMode.DARK) {
    RingScreen(PhoneRingService.State.Ringing(fromWatch = true, snoozesLeft = 3, sunrise = Sunrise.WINDOW), onStop = {}, onSnooze = {}, light = 0.92f, clock = { FIXED_NOW })
}

private const val PHONE = "spec:width=411dp,height=891dp"
private const val PHONE_TALL = "spec:width=411dp,height=1600dp"
