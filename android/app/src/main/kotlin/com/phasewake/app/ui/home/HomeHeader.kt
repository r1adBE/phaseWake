package com.phasewake.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.WakePlan
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.components.AnimatedNight
import com.phasewake.app.ui.components.HeroWave
import com.phasewake.app.ui.components.LogoMark
import com.phasewake.app.ui.components.Night
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

// Kopfbereich der Startseite: Gruss, nächstes Weckfenster, Tag.

/**
 * Kopfbereich im Nachthimmel-Verlauf, randlos bis unter die Statusleiste: Logo, Begrüssung und
 * das nächste Weckfenster gross; ohne Wecker eine Einladung, den ersten zu stellen.
 */
@Composable
internal fun Header(
    plan: WakePlan?,
    alarm: AlarmSpec?,
    now: Instant,
    zone: ZoneId,
    fullPage: Boolean,
    /** `null`: Limit erreicht, kein Knopf «Neuer Wecker». */
    onAdd: (() -> Unit)?,
    onEdit: (String) -> Unit,
    /** Datum eines Feiertags, an dem ein Wecker nicht weckt (schon formatiert); `null` = keiner. */
    holidayNote: String? = null,
    /** Beginn der automatischen Einschlaf-Messung der Uhr (schon formatiert); `null` = keine Zeile. */
    autoOnset: String? = null,
) {
    val context = LocalContext.current
    // In Vorschau und Bildschirmfotos gibt es keinen zweiten Frame: dort gleich sichtbar.
    val inspection = LocalInspectionMode.current
    var appeared by remember { mutableStateOf(inspection) }
    LaunchedEffect(Unit) { appeared = true }
    val shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
    val base = Modifier
        .fillMaxWidth()
        .clip(shape)
        .then(if (plan != null) Modifier.clickable { onEdit(plan.alarmId) } else Modifier)
    // Auf der ganzseitigen Nacht (dunkel) braucht der Kopf keinen eigenen Hintergrund.
    val background: @Composable (Modifier, @Composable () -> Unit) -> Unit =
        if (fullPage) { m, c -> Box(m) { c() } } else { m, c -> AnimatedNight(m) { c() } }
    background(base) {
        Column(Modifier.statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoMark(48.dp)
                Text(
                    "Phase Wake",
                    style = MaterialTheme.typography.titleLarge,
                    color = Night.Text,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            AnimatedVisibility(appeared, enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 4 }) {
                Column(Modifier.padding(top = 28.dp).semantics(mergeDescendants = true) { heading() }) {
                    Text(stringResource(greeting(now, zone)), style = MaterialTheme.typography.titleMedium, color = Night.Muted)
                    if (plan != null && alarm != null) {
                        val dayText = dayLabel(plan.target, now, zone)
                        HeroTime(plan, zone)
                        // Zwei Zeilen statt eines Umbruchs mitten im Satz: wann und welcher – dann wie lange noch.
                        Text(
                            listOf(dayText, alarm.label).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Night.Muted,
                        )
                        // «Genau»: kein «Weckt im leichten Schlaf» und kein «Weckfenster läuft»; die Zeile oben sagt schon alles.
                        if (!plan.exact) {
                            Text(
                                if (now.isBefore(plan.windowStart)) {
                                    stringResource(R.string.home_next_in, Format.duration(context, Duration.between(now, plan.windowStart)))
                                } else {
                                    stringResource(R.string.home_window_running)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Night.Muted,
                            )
                        }
                        autoOnset?.let {
                            Text(stringResource(R.string.home_auto_onset, it), style = MaterialTheme.typography.bodySmall, color = Night.Muted)
                        }
                        holidayNote?.let { HolidayNote(it) }
                        HeroWave(Modifier.fillMaxWidth().height(76.dp).padding(top = 18.dp), windowFraction = 0.32f, onNight = true)
                    } else {
                        Text(
                            stringResource(R.string.home_no_alarm_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Night.Text,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Text(stringResource(R.string.home_no_alarm_text), style = MaterialTheme.typography.bodyMedium, color = Night.Muted)
                        holidayNote?.let { HolidayNote(it) }
                        HeroWave(Modifier.fillMaxWidth().height(64.dp).padding(vertical = 14.dp), onNight = true)
                        if (onAdd != null) {
                            Button(
                                onClick = onAdd,
                                colors = ButtonDefaults.buttonColors(containerColor = Night.Wave, contentColor = Night.Top),
                            ) {
                                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.home_add_alarm))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Das Weckfenster gross in einer Zeile (Gestaltung 1, Audit N-2): AM/PM klein hinter der Zeit, und reicht die Breite
 * nicht (lange Sprachen, Arabisch, grosse Schrift), wird die Schrift schrittweise kleiner statt umzubrechen.
 */
@Composable
private fun HeroTime(plan: WakePlan, zone: ZoneId) {
    val target = Format.timeWithPeriod(plan.target, zone)
    val text = if (plan.exact) {
        // Die Zeit steckt im Satz «Genau um …»: ihren Zusatz dort suchen.
        val line = stringResource(R.string.window_exact_line, target.first)
        heroText(line, listOf(target), line.indexOf(target.first))
    } else {
        val start = Format.timeWithPeriod(plan.windowStart, zone)
        heroText(start.first + " – " + target.first, listOf(start, target), 0)
    }
    val style = if (plan.exact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium
    BasicText(
        text,
        style = style.copy(color = Night.Text),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = HERO_MIN_SIZE, maxFontSize = style.fontSize, stepSize = 1.sp),
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )
}

/** Kleinste Schrift des Weckfensters, bevor es abgeschnitten würde. */
private val HERO_MIN_SIZE = 20.sp

/** Anteil der Schriftgrösse für AM/PM im Weckfenster. */
private const val PERIOD_SCALE = 0.42f

/**
 * [line] mit kleinem Tageszeit-Zusatz: [times] sind die Zeiten in der Reihenfolge, in der sie in [line] stehen,
 * die erste ab [from]. Nicht gefundene Zeiten bleiben unverändert.
 */
internal fun heroText(line: String, times: List<Pair<String, IntRange?>>, from: Int): AnnotatedString = buildAnnotatedString {
    append(line)
    var cursor = from.coerceAtLeast(0)
    for ((time, period) in times) {
        val at = line.indexOf(time, cursor)
        if (at < 0) continue
        if (period != null) addStyle(SpanStyle(fontSize = PERIOD_SCALE.em), at + period.first, at + period.last + 1)
        cursor = at + time.length
    }
}

/** «Do., 30.10.: Feiertag – nicht geweckt». */
@Composable
private fun HolidayNote(day: String) {
    Text(stringResource(R.string.home_holiday_skipped, day), style = MaterialTheme.typography.bodyMedium, color = Night.Muted)
}

/** «Heute», «Morgen» oder der Wochentag («Montag») – nie «Morgen» für einen Tag weiter weg. */
@Composable
private fun dayLabel(target: Instant, now: Instant, zone: ZoneId): String {
    val day = target.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val locale = LocalConfiguration.current.locales[0]
    return when (day) {
        today -> stringResource(R.string.home_next_today)
        today.plusDays(1) -> stringResource(R.string.home_next_tomorrow)
        else -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
    }
}

/** «Guten Morgen» bis 11, «Guten Tag» bis 17, «Guten Abend» bis 22, danach «Gute Nacht». */
private fun greeting(now: Instant, zone: ZoneId): Int = when (now.atZone(zone).hour) {
    in 5..10 -> R.string.greeting_morning
    in 11..16 -> R.string.greeting_day
    in 17..21 -> R.string.greeting_evening
    else -> R.string.greeting_night
}
