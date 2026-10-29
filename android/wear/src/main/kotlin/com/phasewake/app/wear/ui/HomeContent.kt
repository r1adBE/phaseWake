package com.phasewake.app.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SplitSwitchButton
import androidx.wear.compose.material3.Text
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Bedtime
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.DayFormat
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.components.FlowingWave
import com.phasewake.app.wear.ui.components.WindowRing
import com.phasewake.app.wear.ui.theme.Brand
import com.phasewake.app.wear.ui.theme.nightGlow
import com.phasewake.app.wear.wake.ringReasonRes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Startbildschirm der Uhr ohne eigenen Zustand: nächstes Fenster, letzte Nacht, Wecker.

/** Startbildschirm ohne eigenen Zustand; so auch für Vorschau und Bildschirmfotos nutzbar. */
@Composable
internal fun HomeContent(
    alarms: List<AlarmSpec>,
    plan: WakePlan?,
    loading: Boolean = false,
    lastNight: NightRecord? = null,
    zone: ZoneId,
    goodNightActive: Boolean,
    goodNightTooFar: Boolean = false,
    permissionsMissing: Boolean,
    dndBlocks: Boolean,
    onGoodNight: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenDnd: () -> Unit,
    onToggle: (AlarmSpec, Boolean) -> Unit,
    onAdd: () -> Unit = {},
    onEdit: (AlarmSpec) -> Unit = {},
    extras: WatchHomeExtras = WatchHomeExtras(),
) {
    val sleepAlarm = alarms.firstOrNull { it.id == plan?.alarmId && it.sleepDurationMinutes != null }
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize().nightGlow(strength = 0.55f)) {
            item { NextWindow(plan, alarms.firstOrNull { it.id == plan?.alarmId }, zone, loading, extras) }
            if (sleepAlarm != null) {
                item {
                    GoodNightButton(
                        active = goodNightActive,
                        tooFar = goodNightTooFar && !goodNightActive,
                        sleepMinutes = sleepAlarm.sleepDurationMinutes ?: 0,
                        onClick = onGoodNight,
                    )
                }
            }
            extras.onNap?.let { onNap -> item { NapChip(extras.nap, zone, onNap, extras.onStopNap) } }
            if (permissionsMissing) {
                item {
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.home_permissions)) },
                        secondaryLabel = { Text(stringResource(R.string.home_permissions_hint)) },
                    )
                }
            }
            if (dndBlocks) {
                item {
                    Button(
                        onClick = onOpenDnd,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        label = { Text(stringResource(R.string.dnd_title)) },
                        secondaryLabel = { Text(stringResource(R.string.dnd_text)) },
                    )
                }
            }
            if (lastNight != null) {
                item { LastNight(lastNight, zone) }
            }
            if (alarms.isNotEmpty()) {
                item { ListHeader { Text(stringResource(R.string.home_alarms)) } }
            }
            items(alarms.size) { index ->
                val alarm = alarms[index]
                AlarmSwitch(alarm, extras.skipped(alarm), onEdit = { onEdit(alarm) }) { enabled -> onToggle(alarm, enabled) }
            }
            if (extras.diagnostics) {
                item {
                    Button(onClick = extras.onDiagnostics, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.diag_title)) })
                }
            }
            if (!loading) {
                item {
                    if (alarms.size < MAX_ALARMS) {
                        Button(
                            onClick = onAdd,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            icon = { Text("+", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { }) },
                            label = { Text(stringResource(R.string.home_add_alarm)) },
                        )
                    } else {
                        Text(
                            stringResource(R.string.home_max_alarms, MAX_ALARMS),
                            style = MaterialTheme.typography.bodySmall,
                            color = Brand.Muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NextWindow(plan: WakePlan?, alarm: AlarmSpec?, zone: ZoneId, loading: Boolean, extras: WatchHomeExtras) {
    // Etwas kleiner als das Display, damit darunter «Gute Nacht» schon beim Öffnen zu sehen ist.
    // Tippen zählt nur für das Freischalten der Diagnose (7×), ohne Rolle für Bildschirmleser.
    val onTap = extras.onWindowTap
    Box(
        Modifier.size(width = 164.dp, height = 164.dp).pointerInput(Unit) { detectTapGestures { onTap() } },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) return@Box
        // Einschlafpunkt nur zu einem gespeicherten Wecker, nicht zu Powernap oder Probewecker.
        val bedtime = plan?.takeIf { alarm != null }?.let { Bedtime.onDial(it.target, extras.sleepGoalMinutes) }
        if (plan != null) {
            WindowRing(
                dialFraction(plan.windowStart, zone),
                dialFraction(plan.target, zone),
                Modifier.size(156.dp),
                nowFraction = extras.now?.let { dialFraction(it, zone) },
                bedtimeFraction = bedtime?.let { dialFraction(it, zone) },
                // «Genau»: kein Bogen, nur der Weckpunkt.
                showArc = !plan.exact,
            )
        }
        // Der Ring ist für Bildschirmleser verborgen: die Bettzeit kommt darum nur in die Beschreibung des Textblocks,
        // sichtbar bleibt sie allein als feiner Punkt.
        val texts = plan?.let { windowTexts(it, alarm, zone, extras.now) }
        val bedtimeText = bedtime?.let { stringResource(R.string.chart_bedtime, formatTime(it, zone)) }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = if (texts == null) {
                Modifier.semantics(mergeDescendants = true) { }
            } else {
                Modifier.clearAndSetSemantics { contentDescription = (texts.filter { it.isNotEmpty() } + listOfNotNull(bedtimeText)).joinToString(", ") }
            },
        ) {
            if (texts == null) {
                Text(stringResource(R.string.home_no_alarm), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.home_no_alarm_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(130.dp),
                )
            } else {
                val (day, window, length) = texts
                Text(day, style = MaterialTheme.typography.labelSmall, color = Brand.Mist)
                Text(window, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                if (length.isNotEmpty()) Text(length, style = MaterialTheme.typography.labelSmall, color = Brand.Muted)
                FlowingWave(Modifier.padding(top = 6.dp).width(96.dp).height(18.dp))
            }
        }
    }
}

/**
 * Tag, Fenster und Fensterlänge des Plans als Texte; «heute» und «morgen» bezogen auf [now] (`null` = jetzt).
 * Bei «Genau» ([WakePlan.exact]) «Genau um 7:00» und keine Fensterlänge (leerer Text).
 */
@Composable
private fun windowTexts(plan: WakePlan, alarm: AlarmSpec?, zone: ZoneId, now: Instant?): List<String> {
    val day = plan.target.atZone(zone).toLocalDate()
    val today = (now ?: Instant.now()).atZone(zone).toLocalDate()
    val locale = LocalConfiguration.current.locales[0]
    return listOf(
        when (day) {
            today -> stringResource(R.string.home_today)
            today.plusDays(1) -> stringResource(R.string.home_tomorrow)
            else -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
        },
        if (plan.exact) {
            stringResource(R.string.window_exact_line, formatTime(plan.target, zone))
        } else {
            stringResource(R.string.home_window, formatTime(plan.windowStart, zone), formatTime(plan.target, zone))
        },
        if (plan.exact) "" else stringResource(R.string.home_window_length, alarm?.windowMinutes ?: AlarmSpec.DEFAULT_WINDOW_MINUTES),
    )
}

/** Letzte Nacht in einer Zeile: wann und warum geweckt wurde. */
@Composable
private fun LastNight(night: NightRecord, zone: ZoneId) {
    val reason = stringResource(ringReasonRes(night.reason))
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) { },
    ) {
        Text(stringResource(R.string.home_last_night), style = MaterialTheme.typography.labelSmall, color = Brand.Muted)
        Text(formatTime(night.wokeAt, zone), style = MaterialTheme.typography.titleMedium)
        Text(reason, style = MaterialTheme.typography.bodySmall, color = Brand.Mist, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GoodNightButton(active: Boolean, tooFar: Boolean, sleepMinutes: Int, onClick: () -> Unit) {
    val hours = sleepMinutes / 60
    val rest = sleepMinutes % 60
    val duration = if (rest == 0) {
        stringResource(R.string.duration_hours, hours)
    } else {
        stringResource(R.string.duration_hours_minutes, hours, rest)
    }
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        // Weckzeit zu weit weg: Hinweis statt Messung.
        enabled = !tooFar,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Brand.Deep else Brand.Echo,
            contentColor = Brand.Moon,
            secondaryContentColor = Brand.Star,
        ),
        label = { Text(stringResource(R.string.home_good_night)) },
        secondaryLabel = {
            Text(
                when {
                    active -> stringResource(R.string.home_good_night_active)
                    tooFar -> stringResource(R.string.home_good_night_too_far)
                    else -> stringResource(R.string.home_good_night_hint, duration)
                },
            )
        },
    )
}

/** Zeile antippen öffnet den Editor, der Schalter rechts schaltet ein/aus. */
@Composable
private fun AlarmSwitch(alarm: AlarmSpec, skipped: String?, onEdit: () -> Unit, onToggle: (Boolean) -> Unit) {
    val time = LocalTime.of(alarm.hour, alarm.minute)
    val start = time.minusMinutes(alarm.windowMinutes.toLong())
    val days = DayFormat.format(
        alarm.days, LocalConfiguration.current.locales[0],
        stringResource(R.string.alarm_once), stringResource(R.string.alarm_every_day),
    )
    val window = if (alarm.isExact) {
        stringResource(R.string.window_exact_line, formatTime(time))
    } else {
        stringResource(R.string.home_window, formatTime(start), formatTime(time))
    }
    SplitSwitchButton(
        checked = alarm.enabled,
        onCheckedChange = onToggle,
        toggleContentDescription = window,
        onContainerClick = onEdit,
        containerClickLabel = stringResource(R.string.editor_edit),
        modifier = Modifier.fillMaxWidth(),
        label = { Text(window) },
        secondaryLabel = {
            val skip = skipped?.let { stringResource(R.string.alarm_skipped, it) }
            Text(listOfNotNull(days, alarm.label, skip).filter { it.isNotBlank() }.joinToString(" · "))
        },
    )
}

/** Position einer Uhrzeit auf dem 12-Stunden-Zifferblatt (0 = 12 Uhr). */
private fun dialFraction(instant: Instant, zone: ZoneId): Float {
    val t = instant.atZone(zone).toLocalTime()
    return ((t.hour % 12) * 60 + t.minute) / 720f
}

private val shortTime: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun formatTime(instant: Instant, zone: ZoneId): String = shortTime.format(instant.atZone(zone))

private fun formatTime(time: LocalTime): String = shortTime.format(time)
