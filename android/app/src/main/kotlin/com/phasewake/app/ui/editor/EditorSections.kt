package com.phasewake.app.ui.editor

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.TimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.PhoneAlert
import com.phasewake.app.core.Sunrise
import com.phasewake.app.core.SunriseDevice
import com.phasewake.app.core.WakeTask
import com.phasewake.app.core.WindowAdvice
import com.phasewake.app.ui.Format
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import kotlin.math.roundToInt

// Abschnitte des Wecker-Editors; jeder liest und ändert den gemeinsamen [EditorState].

/** Montag bis Freitag. */
internal val WEEKDAYS =
    setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/** Uhrzeit: Zielzeit oder (Einschlaf-Modus) späteste Weckzeit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeSection(state: EditorState, time: TimePickerState) {
    Section {
        // Beschriftung bündig mit dem Stundenfeld: Im 24-h-Format ist die Anzeige (2 × 96 dp + 24 dp)
        // schmaler als das Zifferblatt (256 dp) und steht 20 dp eingerückt; im 12-h-Format nicht.
        Column(Modifier.align(Alignment.CenterHorizontally)) {
            Text(
                stringResource(if (state.sleepMode) R.string.editor_latest else R.string.editor_target),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = if (time.is24hour) 20.dp * TIME_PICKER_SCALE else 0.dp),
            )
            TimePicker(
                state = time,
                // Senkrecht: Im 12-h-Format bricht so «vorm./nachm.» in langen Sprachen nicht um.
                layoutType = TimePickerLayoutType.Vertical,
                colors = timePickerColors(),
                modifier = Modifier.padding(top = 8.dp).scaled(TIME_PICKER_SCALE),
            )
        }
    }
}

/** Schlafdauer im Einschlaf-Modus, in Schritten von 15 Minuten. */
@Composable
internal fun SleepSection(state: EditorState) {
    val context = LocalContext.current
    Section {
        Text(
            stringResource(
                R.string.editor_sleep_duration,
                Format.duration(context, Duration.ofMinutes(state.sleepMinutes.roundToInt().toLong())),
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = state.sleepMinutes,
            onValueChange = { state.sleepMinutes = (it / 15f).roundToInt() * 15f },
            valueRange = SLEEP_MIN..SLEEP_MAX,
            steps = ((SLEEP_MAX - SLEEP_MIN) / 15f).roundToInt() - 1,
        )
        Text(
            stringResource(R.string.editor_sleep_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Vorschlag aus dem Verlauf: grösseres Weckfenster, mit Knopf zum Übernehmen. */
@Composable
internal fun AdviceCard(state: EditorState, advice: WindowAdvice.Suggestion) {
    val res = LocalResources.current
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.editor_advice_title), style = MaterialTheme.typography.titleSmall)
            Text(
                res.getQuantityString(
                    R.plurals.editor_advice_text, advice.nights, advice.deadlineNights, advice.nights,
                    advice.windowMinutes,
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = { state.window = advice.windowMinutes.toFloat() }) {
                Text(stringResource(R.string.editor_advice_apply, advice.windowMinutes))
            }
        }
    }
}

/** Innenabstand links und rechts in den Feldern der Fensterwahl (Standard 12 dp ist für sechs Felder zu breit). */
private val WINDOW_SEGMENT_PADDING = 4.dp

/** Breite des Felds «Genau» im Verhältnis zu einem Zahlenfeld der Fensterwahl. */
private const val EXACT_SEGMENT_WEIGHT = 1.6f

/**
 * Weckfenster «Genau · 10 · 20 · 30 · 45 · 60» ([AlarmSpec.WINDOW_CHOICES]); ein älterer Wert (15, 25) bleibt als
 * eigenes Feld wählbar. Bei fester Zielzeit mit «von – bis», bei «Genau» mit Erklärung.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WindowSection(state: EditorState, time: TimePickerState) {
    Section {
        val minutes = state.window.roundToInt()
        val exact = minutes == AlarmSpec.EXACT_WINDOW
        val exactLabel = stringResource(R.string.editor_window_exact)
        Text(
            if (exact) exactLabel else stringResource(R.string.editor_window, minutes),
            style = MaterialTheme.typography.titleMedium,
        )
        val options = (AlarmSpec.WINDOW_CHOICES + minutes).distinct().sorted()
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp).height(IntrinsicSize.Max)) {
            options.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = value == minutes,
                    onClick = { state.window = value.toFloat() },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    // «Genau» breiter als die Zahlen (weight vor dem weight(1f) des Felds gilt), damit lange Wörter
                    // wie «Dokładnie» ohne Umbruch mitten im Wort passen.
                    modifier = Modifier
                        .weight(if (value == AlarmSpec.EXACT_WINDOW) EXACT_SEGMENT_WEIGHT else 1f)
                        .heightIn(min = 48.dp)
                        .fillMaxHeight(),
                    icon = {
                        if (options.size <= CHECK_ICON_MAX_SEGMENTS) SegmentedButtonDefaults.Icon(value == minutes)
                    },
                    // Sechs schmale Felder: wenig Innenabstand; ein einzelnes Wort nicht mitten im Wort umbrechen.
                    contentPadding = PaddingValues(horizontal = WINDOW_SEGMENT_PADDING),
                ) {
                    val text = if (value == AlarmSpec.EXACT_WINDOW) exactLabel else Format.number(value)
                    ChoiceLabel(text, maxLines = if (' ' in text.trim()) 2 else 1)
                }
            }
        }
        val hint = when {
            exact -> stringResource(R.string.editor_window_exact_hint)
            state.sleepMode -> null
            else -> LocalTime.of(time.hour, time.minute).let { target ->
                stringResource(
                    R.string.editor_window_hint, Format.time(target.minusMinutes(minutes.toLong())),
                    Format.time(target),
                )
            }
        }
        hint?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Wochentage: sieben runde Schalter und Kurzwahl (Werktage, Wochenende, täglich). */
@Composable
internal fun DaysSection(state: EditorState) {
    Section {
        Text(stringResource(R.string.editor_days), style = MaterialTheme.typography.titleMedium)
        // Sieben Tage in einer Zeile, Wochenbeginn nach Sprache und Region (z. B. Sonntag in den USA).
        val locale = LocalConfiguration.current.locales[0]
        val firstDay = WeekFields.of(locale).firstDayOfWeek
        // Ohne Abstand zwischen den Feldern: Die Lücke zwischen den Kreisen liegt im Feld ([DAY_GAP]) und ist
        // antippbar.
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            (0L until 7L).map { firstDay.plus(it) }.forEach { day ->
                DayToggle(
                    label = day.getDisplayName(TextStyle.SHORT, locale).trimEnd('.'),
                    description = day.getDisplayName(TextStyle.FULL, locale),
                    selected = day in state.days,
                    onToggle = { state.days = if (day in state.days) state.days - day else state.days + day },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { state.days = WEEKDAYS }) { Text(stringResource(R.string.editor_weekdays)) }
            TextButton(onClick = { state.days = WEEKEND }) { Text(stringResource(R.string.editor_weekend)) }
            TextButton(onClick = { state.days = DayOfWeek.entries.toSet() }) {
                Text(stringResource(R.string.alarm_every_day))
            }
        }
    }
}

/**
 * «An Feiertagen nicht wecken» (unter «Mehr»); nur für wiederholte Wecker, ein Einmal-Wecker klingelt an seinem Tag.
 */
@Composable
internal fun HolidaySection(state: EditorState) {
    Section {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                value = state.skipHolidays, role = Role.Switch, onValueChange = { state.skipHolidays = it },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.editor_skip_holidays), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.editor_skip_holidays_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.skipHolidays, onCheckedChange = null)
        }
    }
}

/** Weckart: Uhr (Vibration, mit Ton) und Handy als Zweitwecker; die Weckaufgabe steht unter «Mehr». */
@Composable
internal fun AlertSection(state: EditorState) {
    Section {
        Text(stringResource(R.string.editor_alert), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.editor_alert_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AlertLabel(R.drawable.ic_watch, R.string.editor_alert_watch)
        Choice(
            options = listOf(false to R.string.alert_vibrate_only, true to R.string.alert_vibrate_sound),
            selected = state.watchSound,
            onSelect = { state.watchSound = it },
        )

        AlertLabel(R.drawable.ic_phone, R.string.editor_alert_phone)
        Choice(
            options = listOf(
                PhoneAlert.OFF to R.string.alert_off, PhoneAlert.VIBRATE to R.string.alert_vibrate,
                PhoneAlert.SOUND to R.string.alert_sound,
            ),
            selected = state.phone,
            onSelect = { state.phone = it },
        )
        if (state.phone != PhoneAlert.OFF) {
            Text(
                stringResource(R.string.editor_phone_backup_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * «Zum Beenden» (unter «Mehr»): Halten oder Weckaufgabe; nur mit Handy-Wecker (gelöst wird am Handy, ohne ihn gilt
 * Halten).
 */
@Composable
internal fun WakeTaskSection(state: EditorState) {
    Section {
        Text(
            stringResource(R.string.wake_task_title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Choice(
            options = listOf(WakeTask.NONE to R.string.wake_task_hold, WakeTask.MATH to R.string.wake_task_math),
            selected = state.wakeTask,
            onSelect = { state.wakeTask = it },
        )
        AnimatedVisibility(state.wakeTask == WakeTask.MATH) {
            Text(
                stringResource(R.string.wake_task_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Sonnenaufgang im Haupteditor, mit Gerätewahl (Handy, Uhr oder beide). */
@Composable
internal fun SunriseSection(state: EditorState) {
    val res = LocalResources.current
    Section {
        Text(stringResource(R.string.sunrise_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.editor_sunrise_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp),
        )
        Choice(
            options = listOf(
                Sunrise.OFF to R.string.alert_off, Sunrise.RING to R.string.sunrise_ring,
                Sunrise.WINDOW to R.string.sunrise_window,
            ),
            selected = state.sunrise,
            onSelect = { state.sunrise = it },
        )
        AnimatedVisibility(state.sunrise != Sunrise.OFF) {
            Column {
                Text(
                    if (state.sunrise == Sunrise.WINDOW) stringResource(R.string.editor_sunrise_window_hint)
                    else res.getQuantityString(
                        R.plurals.editor_sunrise_ring_hint, Sunrise.RING_SECONDS / 60, Sunrise.RING_SECONDS / 60,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    stringResource(R.string.sunrise_device_title), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                )
                Choice(
                    options = listOf(
                        SunriseDevice.PHONE to R.string.sunrise_device_phone,
                        SunriseDevice.WATCH to R.string.sunrise_device_watch,
                        SunriseDevice.BOTH to R.string.sunrise_device_both,
                    ),
                    selected = state.sunriseDevice,
                    onSelect = { state.sunriseDevice = it },
                )
                // Nur wenn das Handy leuchtet: wie es hingelegt wird.
                AnimatedVisibility(state.sunriseDevice != SunriseDevice.WATCH) {
                    Text(
                        stringResource(R.string.editor_sunrise_phone_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
