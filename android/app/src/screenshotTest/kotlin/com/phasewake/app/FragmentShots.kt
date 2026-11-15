package com.phasewake.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.phasewake.app.core.HealthHistory
import com.phasewake.app.core.HealthSession
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.StageSpan
import com.phasewake.app.health.HealthSource
import com.phasewake.app.ui.history.HistoryScreen
import com.phasewake.app.ui.history.NightDetailActions
import com.phasewake.app.ui.history.NightDetailData
import com.phasewake.app.ui.history.NightDetailScreen
import com.phasewake.app.ui.theme.ThemeMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

// Zerstückelte Phasen wie von Samsung Health (viele Blöcke von 2–6 Min im Wechsel): Verlauf-Karte und Detailseite.
// Prüft, dass die Hypnogramme bei vielen kurzen Blöcken scharf bleiben.

private val zone = ZoneId.of("Europe/Zurich")

/** Feste Folge von 2–6-Min-Blöcken ab [from] bis [until]; Phasen im Wechsel, gegen Morgen mehr REM, kaum Tief. */
private fun fragments(from: Instant, until: Instant): List<StageSpan> = buildList {
    var seed = 7L
    fun next(n: Int): Int {
        seed = (seed * 1_103_515_245L + 12_345L) and 0x7fff_ffffL
        return ((seed shr 16) % n).toInt()
    }
    val early = listOf(SleepStage.LIGHT, SleepStage.DEEP, SleepStage.LIGHT, SleepStage.DEEP, SleepStage.REM, SleepStage.AWAKE)
    val late = listOf(SleepStage.LIGHT, SleepStage.REM, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE, SleepStage.DEEP)
    val half = from.plus(Duration.between(from, until).dividedBy(2))
    var t = from
    var last: SleepStage? = null
    while (t.isBefore(until)) {
        val pool = if (t.isBefore(half)) early else late
        var stage = pool[next(pool.size)]
        if (stage == last) stage = pool[(pool.indexOf(stage) + 1) % pool.size]
        val end = minOf(t.plus(Duration.ofMinutes(2L + next(5))), until)
        add(StageSpan(t, end, stage))
        last = stage
        t = end
    }
}

/** Eigene Nacht von heute Morgen ([nights] aus `Screens.kt`) mit zerstückelten Phasen ab 22:40. */
private val ownNight = nights.last()
private val ownStages = fragments(ownNight.windowStart.minus(Duration.ofMinutes(400)), ownNight.wokeAt.plus(Duration.ofMinutes(3)))

/** Samsung-Health-Nacht drei Nächte davor, ganz aus kurzen Blöcken. */
private val samsung = run {
    val end = ownNight.wokeAt.minus(Duration.ofDays(3)).plus(Duration.ofMinutes(40))
    val start = end.minus(Duration.ofMinutes(455))
    HealthSession(start, end, fragments(start, end), HealthSource.SAMSUNG_HEALTH)
}

private val items = HealthHistory.merge(listOf(ownNight), listOf(samsung), null, zone)

@Composable
private fun Overview(mode: ThemeMode) = Themed(mode) {
    HistoryScreen(listOf(ownNight), PaddingValues(top = 24.dp, bottom = 96.dp), loadStages = { ownStages }, healthConnected = true, items = items)
}

@Composable
private fun Detail(mode: ThemeMode) = Themed(mode) {
    NightDetailScreen(NightDetailData(items = items, stages = ownStages), items.first { it.session != null }.key, NightDetailActions())
}

@PreviewTest @Preview(locale = "de-rCH", name = "Fragmente Verlauf hell", device = PHONE_TALL) @Composable
fun FragmentListLight() = Overview(ThemeMode.LIGHT)

@PreviewTest @Preview(locale = "de-rCH", name = "Fragmente Verlauf dunkel", device = PHONE_TALL) @Composable
fun FragmentListDark() = Overview(ThemeMode.DARK)

@PreviewTest @Preview(locale = "de-rCH", name = "Fragmente Detail Samsung hell", device = PHONE_TALL) @Composable
fun FragmentDetailLight() = Detail(ThemeMode.LIGHT)

@PreviewTest @Preview(locale = "de-rCH", name = "Fragmente Detail Samsung dunkel", device = PHONE_TALL) @Composable
fun FragmentDetailDark() = Detail(ThemeMode.DARK)
