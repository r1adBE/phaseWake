package com.phasewake.app.ui.editor

import android.content.Intent
import android.media.RingtoneManager
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.TimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.PhoneAlert
import com.phasewake.app.core.Sunrise
import com.phasewake.app.core.SunriseDevice
import com.phasewake.app.core.WakeTask
import com.phasewake.app.core.WindowAdvice
import com.phasewake.app.ring.WakeTonePreview
import com.phasewake.app.ring.WakeTones
import com.phasewake.app.ui.Format
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import kotlin.math.roundToInt

// Abschnitte des Wecker-Editors; jeder liest und ändert den gemeinsamen [EditorState].

/** Montag bis Freitag. */
internal val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
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
                colors = TimePickerDefaults.colors(clockDialColor = MaterialTheme.colorScheme.surfaceContainerHighest),
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
            stringResource(R.string.editor_sleep_duration, Format.duration(context, Duration.ofMinutes(state.sleepMinutes.roundToInt().toLong()))),
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = state.sleepMinutes,
            onValueChange = { state.sleepMinutes = (it / 15f).roundToInt() * 15f },
            valueRange = SLEEP_MIN..SLEEP_MAX,
            steps = ((SLEEP_MAX - SLEEP_MIN) / 15f).roundToInt() - 1,
        )
        Text(stringResource(R.string.editor_sleep_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                res.getQuantityString(R.plurals.editor_advice_text, advice.nights, advice.deadlineNights, advice.nights, advice.windowMinutes),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = { state.window = advice.windowMinutes.toFloat() }) {
                Text(stringResource(R.string.editor_advice_apply, advice.windowMinutes))
            }
        }
    }
}

/** Weckfenster in Schritten von 5 Minuten; bei fester Zielzeit mit «von – bis». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WindowSection(state: EditorState, time: TimePickerState) {
    Section {
        val minutes = state.window.roundToInt()
        Text(stringResource(R.string.editor_window, minutes), style = MaterialTheme.typography.titleMedium)
        Slider(
            value = state.window,
            onValueChange = { state.window = (it / 5f).roundToInt() * 5f },
            valueRange = AlarmSpec.MIN_WINDOW_MINUTES.toFloat()..AlarmSpec.MAX_WINDOW_MINUTES.toFloat(),
            steps = (AlarmSpec.MAX_WINDOW_MINUTES - AlarmSpec.MIN_WINDOW_MINUTES) / 5 - 1,
        )
        if (!state.sleepMode) {
            val target = LocalTime.of(time.hour, time.minute)
            Text(
                stringResource(R.string.editor_window_hint, Format.time(target.minusMinutes(minutes.toLong())), Format.time(target)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
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
            TextButton(onClick = { state.days = DayOfWeek.entries.toSet() }) { Text(stringResource(R.string.alarm_every_day)) }
        }
        // Feiertage nur für wiederholte Wecker; ein Einmal-Wecker klingelt an seinem Tag.
        AnimatedVisibility(state.days.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().toggleable(value = state.skipHolidays, role = Role.Switch, onValueChange = { state.skipHolidays = it }).padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.editor_skip_holidays), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.editor_skip_holidays_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.skipHolidays, onCheckedChange = null)
            }
        }
    }
}

/** Weckart: Uhr (Vibration, mit Ton), Handy als Zweitwecker und die Weckaufgabe am Handy. */
@Composable
internal fun AlertSection(state: EditorState) {
    Section {
        Text(stringResource(R.string.editor_alert), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.editor_alert_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        AlertLabel(R.drawable.ic_watch, R.string.editor_alert_watch)
        Choice(
            options = listOf(false to R.string.alert_vibrate_only, true to R.string.alert_vibrate_sound),
            selected = state.watchSound,
            onSelect = { state.watchSound = it },
        )

        AlertLabel(R.drawable.ic_phone, R.string.editor_alert_phone)
        Choice(
            options = listOf(PhoneAlert.OFF to R.string.alert_off, PhoneAlert.VIBRATE to R.string.alert_vibrate, PhoneAlert.SOUND to R.string.alert_sound),
            selected = state.phone,
            onSelect = { state.phone = it },
        )
        if (state.phone != PhoneAlert.OFF) {
            Text(stringResource(R.string.editor_phone_backup_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            // Weckaufgabe: nur mit Handy-Wecker (gelöst wird am Handy; ohne ihn gilt Halten).
            Text(stringResource(R.string.wake_task_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
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
}

/** Sonnenaufgang im Haupteditor, mit Gerätewahl (Handy, Uhr oder beide). */
@Composable
internal fun SunriseSection(state: EditorState) {
    val res = LocalResources.current
    Section {
        Text(stringResource(R.string.sunrise_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.editor_sunrise_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
        Choice(
            options = listOf(Sunrise.OFF to R.string.alert_off, Sunrise.RING to R.string.sunrise_ring, Sunrise.WINDOW to R.string.sunrise_window),
            selected = state.sunrise,
            onSelect = { state.sunrise = it },
        )
        AnimatedVisibility(state.sunrise != Sunrise.OFF) {
            Column {
                Text(
                    if (state.sunrise == Sunrise.WINDOW) stringResource(R.string.editor_sunrise_window_hint)
                    else res.getQuantityString(R.plurals.editor_sunrise_ring_hint, Sunrise.RING_SECONDS / 60, Sunrise.RING_SECONDS / 60),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(stringResource(R.string.sunrise_device_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
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

/** Selten gebrauchte Optionen (Weckton, Name) zugeklappt unter «Mehr»: kürzerer Editor auf kleinen Handys. */
@Composable
internal fun ColumnScope.MoreSection(
    state: EditorState,
    preview: WakeTonePreview,
    soundPicker: ManagedActivityResultLauncher<Intent, ActivityResult>,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button) { state.more = !state.more }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.editor_more), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(
            painterResource(R.drawable.ic_expand),
            contentDescription = null,
            modifier = Modifier.size(20.dp).rotate(if (state.more) 180f else 0f),
        )
    }
    AnimatedVisibility(state.more) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AnimatedVisibility(state.phone == PhoneAlert.SOUND) { ToneSection(state, preview, soundPicker) }
            OutlinedTextField(
                value = state.label,
                onValueChange = { state.label = it.take(LABEL_MAX_LENGTH) },
                label = { Text(stringResource(R.string.editor_label)) },
                placeholder = { Text(stringResource(R.string.editor_label_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Höchstlänge des Namens eines Weckers in Zeichen. */
private const val LABEL_MAX_LENGTH = 40

/** Weckton des Handys: eigene Töne (Antippen spielt kurz an) oder ein Systemton aus der Auswahl. */
@Composable
private fun ToneSection(
    state: EditorState,
    preview: WakeTonePreview,
    soundPicker: ManagedActivityResultLauncher<Intent, ActivityResult>,
) {
    val context = LocalContext.current
    Section {
        // Zugeklappt oder ausgeblendet: Probehören endet.
        DisposableEffect(Unit) { onDispose { preview.stop() } }
        Text(stringResource(R.string.editor_sound), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
        WakeTones.ALL.forEach { tone ->
            ToneRow(stringResource(tone.label), selected = state.sound == tone.key) {
                state.sound = tone.key
                preview.play(tone)
            }
        }
        // Systemton: leer (Standard) oder eine URI aus der Auswahl des Systems.
        val system = WakeTones.find(state.sound) == null
        ToneRow(
            stringResource(R.string.wake_tone_system),
            selected = system,
            detail = if (system) soundTitle(context, state.sound) else null,
        ) {
            preview.stop()
            soundPicker.launch(
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, state.sound.takeIf { system }?.ifEmpty { null }?.toUri()),
            )
        }
    }
}
