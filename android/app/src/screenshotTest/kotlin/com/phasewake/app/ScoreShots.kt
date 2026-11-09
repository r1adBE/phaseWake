package com.phasewake.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.HealthHistory
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.OverviewUnit
import com.phasewake.app.core.SleepOverviews
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.SleepSummary
import com.phasewake.app.core.StageSpan
import com.phasewake.app.core.WakeReason
import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.RecordEnd
import com.phasewake.app.ui.history.NightDetailActions
import com.phasewake.app.ui.history.NightDetailData
import com.phasewake.app.ui.history.NightDetailScreen
import com.phasewake.app.ui.history.OverviewCard
import com.phasewake.app.ui.history.OverviewSwitch
import com.phasewake.app.ui.theme.ThemeMode
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin

// Auswertung (11k): Übersicht «Tage / Wochen / Monate» und Detailseite einer Nacht nur mit eigener Aufzeichnung
// (Schlafwert aus Schlafdauer und Einschlafdauer, Puls und Bewegung über die ganze Nacht).

private val zone = ZoneId.of("Europe/Zurich")
private val today = LocalDate.of(2026, 11, 8)

/** 90 Nächte mit wechselnder Schlafenszeit und Dauer; einzelne Nächte ohne Daten. */
private val sleeps: List<SleepSummary> = (0L until 90L).mapNotNull { back ->
    if (back % 11 == 4L) return@mapNotNull null
    val night = today.minusDays(back)
    val bed = night.atTime(22, 30).atZone(zone).toInstant().plus(Duration.ofMinutes((back * 17) % 70))
    val asleep = 380L + (back * 23) % 110
    SleepSummary(bed, bed.plus(Duration.ofMinutes(asleep + 12)), asleep)
}.reversed()

/** Phasen für jede Nacht ausser jeder dritten (dort nur die Dauer): Zyklen aus Leicht, Tief, Leicht und REM. */
private val stages: List<StageSpan> = sleeps.filterIndexed { i, _ -> i % 3 != 0 }.flatMap { sleep ->
    val cycle = listOf(SleepStage.LIGHT to 25L, SleepStage.DEEP to 40L, SleepStage.LIGHT to 20L, SleepStage.REM to 22L)
    buildList {
        var t = sleep.start
        var k = 0
        while (t.isBefore(sleep.end)) {
            val (stage, minutes) = cycle[k % cycle.size]
            val next = minOf(t.plus(Duration.ofMinutes(minutes + (k % 3))), sleep.end)
            add(StageSpan(t, next, stage))
            t = next
            k++
        }
    }
}

private fun overviewOf(withStages: Boolean) = OverviewUnit.entries.mapNotNull { unit ->
    SleepOverviews.of(sleeps, zone, today, 450, unit, DayOfWeek.MONDAY, if (withStages) stages else emptyList())?.let { unit to it }
}.toMap()

/** Auswahl und Übersicht wie oben im Verlauf. */
@Composable
private fun Overview(mode: ThemeMode, unit: OverviewUnit, withStages: Boolean = true) = Themed(mode) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OverviewSwitch(unit, {})
        OverviewCard(overviewOf(withStages), unit, { null }, Modifier.fillMaxWidth())
    }
}

@PreviewTest @Preview(locale = "de-rCH", name = "Übersicht Tage hell", device = PHONE) @Composable
fun OverviewDaysLight() = Overview(ThemeMode.LIGHT, OverviewUnit.DAYS)

@PreviewTest @Preview(locale = "de-rCH", name = "Übersicht Tage dunkel", device = PHONE) @Composable
fun OverviewDaysDark() = Overview(ThemeMode.DARK, OverviewUnit.DAYS)

@PreviewTest @Preview(locale = "de-rCH", name = "Übersicht Wochen dunkel", device = PHONE) @Composable
fun OverviewWeeksDark() = Overview(ThemeMode.DARK, OverviewUnit.WEEKS)

@PreviewTest @Preview(locale = "de-rCH", name = "Übersicht Monate hell", device = PHONE) @Composable
fun OverviewMonthsLight() = Overview(ThemeMode.LIGHT, OverviewUnit.MONTHS)

@PreviewTest @Preview(locale = "de-rCH", name = "Übersicht ohne Phasen dunkel", device = PHONE) @Composable
fun OverviewPlainDark() = Overview(ThemeMode.DARK, OverviewUnit.DAYS, withStages = false)

/** Eigene Nacht ohne Gesundheitsdaten: Fenster 06:30–07:00, geweckt 06:48. */
private val ownNight: NightRecord = run {
    val start = today.plusDays(1).atTime(6, 30).atZone(zone).toInstant()
    val target = start.plus(Duration.ofMinutes(30))
    NightRecord("work", start, target, target.minus(Duration.ofMinutes(12)), WakeReason.LIGHT_SLEEP, 0, List(40) { Epoch(0.004 + (it % 7) * 0.003, 58.0 + it % 5) })
}

/** Aufzeichnung der Uhr ab 22:50 bis zum Wecken: Puls sinkt in der Nacht und steigt gegen Morgen, Bewegung in Wellen. */
private val recording: NightRecording = run {
    val start = today.atTime(22, 50).atZone(zone).toInstant()
    val count = (Duration.between(start, ownNight.wokeAt).seconds / 30).toInt()
    val epochs = List(count) { i ->
        val f = i.toDouble() / count
        val cycle = sin(2 * PI * i / 180)
        val motion = if (i in 400 until 412) null else (0.002 + 0.004 * (cycle + 1) + if (i % 97 == 0) 0.05 else 0.0)
        val pulse = if (i in 400 until 440) null else 64 - 10 * sin(PI * f) + 3 * cycle + (if (f > 0.93) 6 else 0)
        Epoch(motion, pulse)
    }
    NightRecording(start, epochs, ownNight.wokeAt, RecordEnd.WAKE, bedAt = start, asleepAt = start.plus(Duration.ofMinutes(18)))
}

private val recordedItems = HealthHistory.merge(listOf(ownNight), emptyList(), null, zone)

@Composable
private fun Recorded(mode: ThemeMode) = Themed(mode) {
    NightDetailScreen(NightDetailData(items = recordedItems, recordings = listOf(recording)), recordedItems.first().key, NightDetailActions())
}

@PreviewTest @Preview(locale = "de-rCH", name = "Nacht Detail Aufzeichnung hell", device = PHONE_TALL) @Composable
fun NightDetailRecordedLight() = Recorded(ThemeMode.LIGHT)

@PreviewTest @Preview(locale = "de-rCH", name = "Nacht Detail Aufzeichnung dunkel", device = PHONE_TALL) @Composable
fun NightDetailRecordedDark() = Recorded(ThemeMode.DARK)
