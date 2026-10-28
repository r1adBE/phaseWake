package com.phasewake.app.ui.editor

import android.app.Activity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import java.time.temporal.WeekFields
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.AlertDialog
import androidx.activity.compose.BackHandler
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.core.content.IntentCompat
import com.phasewake.app.core.PhoneAlert
import com.phasewake.app.core.Sunrise
import com.phasewake.app.core.WindowAdvice
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.ui.Format
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.UUID
import kotlin.math.roundToInt

private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/** Wecker anlegen oder bearbeiten. [existing] = `null` für einen neuen Wecker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    existing: AlarmSpec?,
    advice: WindowAdvice.Suggestion?,
    onBack: () -> Unit,
    onSave: (AlarmSpec) -> Unit,
    onDelete: (AlarmSpec) -> Unit,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    // Alle Felder überstehen Drehen und Neuaufbau (rememberSaveable), auch die Kennung eines neuen Weckers.
    val newId = rememberSaveable { UUID.randomUUID().toString() }
    val base = existing ?: AlarmSpec(id = newId, hour = 7, minute = 0, days = WEEKDAYS)
    val time = rememberTimePickerState(base.hour, base.minute, is24Hour = DateFormat.is24HourFormat(context))
    var window by rememberSaveable { mutableFloatStateOf(base.windowMinutes.toFloat()) }
    var days by rememberSaveable(stateSaver = DaysSaver) { mutableStateOf(base.days) }
    var sleepMode by rememberSaveable { mutableStateOf(base.sleepDurationMinutes != null) }
    var sleepMinutes by rememberSaveable { mutableFloatStateOf((base.sleepDurationMinutes ?: 450).toFloat()) }
    var phone by rememberSaveable { mutableStateOf(base.phone) }
    var watchSound by rememberSaveable { mutableStateOf(base.watchSound) }
    var sound by rememberSaveable { mutableStateOf(base.sound) }
    var sunrise by rememberSaveable { mutableStateOf(base.sunrise) }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
        if (result.resultCode == Activity.RESULT_OK) sound = uri?.toString().orEmpty()
    }
    var label by rememberSaveable { mutableStateOf(base.label) }
    // «Mehr» aufgeklappt: übersteht Drehen und Neuaufbau.
    var more by rememberSaveable { mutableStateOf(false) }

    fun result() = base.copy(
        hour = time.hour,
        minute = time.minute,
        days = days,
        windowMinutes = window.roundToInt(),
        sleepDurationMinutes = if (sleepMode) sleepMinutes.roundToInt() else null,
        phone = phone,
        watchSound = watchSound,
        sound = sound,
        label = label.trim(),
        sunrise = sunrise,
        enabled = true,
    )

    // Zurück ohne Speichern: bei Änderungen zuerst fragen, auch bei der System-Zurück-Geste.
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val dirty = result() != base.copy(enabled = true)
    val back = { if (dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = dirty) { confirmDiscard = true }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.editor_discard_title)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text(stringResource(R.string.editor_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.editor_keep_editing)) } },
        )
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.editor_delete_title)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(existing) }) {
                    Text(stringResource(R.string.editor_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_cancel)) }
            Text(
                stringResource(if (existing == null) R.string.editor_new else R.string.editor_edit),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onSave(result()) }, modifier = Modifier.padding(end = 12.dp)) {
                Text(stringResource(R.string.editor_save))
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !sleepMode, onClick = { sleepMode = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text(stringResource(R.string.editor_mode_clock))
                }
                SegmentedButton(selected = sleepMode, onClick = { sleepMode = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text(stringResource(R.string.editor_mode_sleep))
                }
            }

            Section {
                Text(
                    stringResource(if (sleepMode) R.string.editor_latest else R.string.editor_target),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TimePicker(
                    state = time,
                    // Senkrecht: Im 12-h-Format bricht so «vorm./nachm.» in langen Sprachen nicht um.
                    layoutType = TimePickerLayoutType.Vertical,
                    colors = TimePickerDefaults.colors(clockDialColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                )
            }

            AnimatedVisibility(sleepMode) {
                Section {
                    Text(
                        stringResource(R.string.editor_sleep_duration, Format.duration(context, Duration.ofMinutes(sleepMinutes.roundToInt().toLong()))),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Slider(
                        value = sleepMinutes,
                        onValueChange = { sleepMinutes = (it / 15f).roundToInt() * 15f },
                        valueRange = SLEEP_MIN..SLEEP_MAX,
                        steps = ((SLEEP_MAX - SLEEP_MIN) / 15f).roundToInt() - 1,
                    )
                    Text(stringResource(R.string.editor_sleep_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (advice != null && window.roundToInt() < advice.windowMinutes) {
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
                        TextButton(onClick = { window = advice.windowMinutes.toFloat() }) {
                            Text(stringResource(R.string.editor_advice_apply, advice.windowMinutes))
                        }
                    }
                }
            }

            Section {
                val minutes = window.roundToInt()
                Text(stringResource(R.string.editor_window, minutes), style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = window,
                    onValueChange = { window = (it / 5f).roundToInt() * 5f },
                    valueRange = AlarmSpec.MIN_WINDOW_MINUTES.toFloat()..AlarmSpec.MAX_WINDOW_MINUTES.toFloat(),
                    steps = (AlarmSpec.MAX_WINDOW_MINUTES - AlarmSpec.MIN_WINDOW_MINUTES) / 5 - 1,
                )
                if (!sleepMode) {
                    val target = LocalTime.of(time.hour, time.minute)
                    Text(
                        stringResource(R.string.editor_window_hint, Format.time(target.minusMinutes(minutes.toLong())), Format.time(target)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

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
                            selected = day in days,
                            onToggle = { days = if (day in days) days - day else days + day },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { days = WEEKDAYS }) { Text(stringResource(R.string.editor_weekdays)) }
                    TextButton(onClick = { days = WEEKEND }) { Text(stringResource(R.string.editor_weekend)) }
                    TextButton(onClick = { days = DayOfWeek.entries.toSet() }) { Text(stringResource(R.string.alarm_every_day)) }
                }
            }

            Section {
                Text(stringResource(R.string.editor_alert), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.editor_alert_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                AlertLabel(R.drawable.ic_watch, R.string.editor_alert_watch)
                Choice(
                    options = listOf(false to R.string.alert_vibrate_only, true to R.string.alert_vibrate_sound),
                    selected = watchSound,
                    onSelect = { watchSound = it },
                )

                AlertLabel(R.drawable.ic_phone, R.string.editor_alert_phone)
                Choice(
                    options = listOf(PhoneAlert.OFF to R.string.alert_off, PhoneAlert.VIBRATE to R.string.alert_vibrate, PhoneAlert.SOUND to R.string.alert_sound),
                    selected = phone,
                    onSelect = { phone = it },
                )
                if (phone != PhoneAlert.OFF) {
                    Text(stringResource(R.string.editor_phone_backup_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }

            // Selten gebrauchte Optionen (Weckton, Sonnenaufgang, Name) zugeklappt unter «Mehr»: kürzerer Editor auf kleinen Handys.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button) { more = !more }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.editor_more), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(
                    painterResource(R.drawable.ic_expand),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).rotate(if (more) 180f else 0f),
                )
            }

            AnimatedVisibility(more) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    AnimatedVisibility(phone == PhoneAlert.SOUND) {
                        Section {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        soundPicker.launch(
                                            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, sound.ifEmpty { null }?.let(Uri::parse)),
                                        )
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.editor_sound), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text(soundTitle(context, sound), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    Section {
                        Text(stringResource(R.string.editor_sunrise), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.editor_sunrise_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                        Choice(
                            options = listOf(Sunrise.OFF to R.string.alert_off, Sunrise.RING to R.string.sunrise_ring, Sunrise.WINDOW to R.string.sunrise_window),
                            selected = sunrise,
                            onSelect = { sunrise = it },
                        )
                        AnimatedVisibility(sunrise != Sunrise.OFF) {
                            Text(
                                if (sunrise == Sunrise.WINDOW) stringResource(R.string.editor_sunrise_window_hint)
                                else res.getQuantityString(R.plurals.editor_sunrise_ring_hint, Sunrise.RING_SECONDS / 60, Sunrise.RING_SECONDS / 60),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }

                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(40) },
                        label = { Text(stringResource(R.string.editor_label)) },
                        placeholder = { Text(stringResource(R.string.editor_label_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (existing != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.editor_delete), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AlertLabel(icon: Int, label: Int) {
    Row(Modifier.padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(stringResource(label), maxLines = 1) }
        }
    }
}

/** Anzeigename des gewählten Wecktons; leer = Standard-Weckton. */
private fun soundTitle(context: Context, sound: String): String {
    if (sound.isEmpty()) return context.getString(R.string.editor_sound_default)
    return runCatching { RingtoneManager.getRingtone(context, sound.toUri())?.getTitle(context) }.getOrNull()
        ?: context.getString(R.string.editor_sound_default)
}

@Composable
private fun Section(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** Wochentage als Zahlen speichern (1 = Montag), damit sie Drehen und Neuaufbau überstehen. */
private val DaysSaver = androidx.compose.runtime.saveable.Saver<Set<DayOfWeek>, ArrayList<Int>>(
    save = { days -> ArrayList(days.map { it.value }) },
    restore = { values -> values.map(DayOfWeek::of).toSet() },
)

/** Kleinster und grösster Wert des Schlafdauer-Schiebers (Minuten); AlarmSpec erlaubt 1–12 h. */
private const val SLEEP_MIN = 180f
private const val SLEEP_MAX = 720f

/** Runder Tag-Schalter; alle sieben passen in eine Zeile. */
@Composable
private fun DayToggle(label: String, description: String, selected: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(if (selected) colors.primary else colors.surfaceContainerHighest)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = description },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.onPrimary else colors.onSurface,
            maxLines = 1,
            softWrap = false,
        )
    }
}
