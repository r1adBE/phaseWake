package ch.r1ad.phasewake.ui.editor

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.r1ad.phasewake.R
import ch.r1ad.phasewake.core.AlarmSpec
import ch.r1ad.phasewake.ui.Format
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/** Wecker anlegen oder bearbeiten. [existing] = `null` für einen neuen Wecker. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    existing: AlarmSpec?,
    onBack: () -> Unit,
    onSave: (AlarmSpec) -> Unit,
    onDelete: (AlarmSpec) -> Unit,
) {
    val context = LocalContext.current
    val base = existing ?: AlarmSpec(id = UUID.randomUUID().toString(), hour = 7, minute = 0, days = WEEKDAYS)
    val time = rememberTimePickerState(base.hour, base.minute, is24Hour = DateFormat.is24HourFormat(context))
    var window by remember { mutableFloatStateOf(base.windowMinutes.toFloat()) }
    var days by remember { mutableStateOf(base.days) }
    var sleepMode by remember { mutableStateOf(base.sleepDurationMinutes != null) }
    var sleepMinutes by remember { mutableFloatStateOf((base.sleepDurationMinutes ?: 450).toFloat()) }
    var phoneBackup by remember { mutableStateOf(base.phoneBackup) }
    var label by remember { mutableStateOf(base.label) }

    fun result() = base.copy(
        hour = time.hour,
        minute = time.minute,
        days = days,
        windowMinutes = window.roundToInt(),
        sleepDurationMinutes = if (sleepMode) sleepMinutes.roundToInt() else null,
        phoneBackup = phoneBackup,
        label = label.trim(),
        enabled = true,
    )

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = null) }
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
                        valueRange = 240f..600f,
                        steps = (600 - 240) / 15 - 1,
                    )
                    Text(stringResource(R.string.editor_sleep_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day in days,
                            onClick = { days = if (day in days) days - day else days + day },
                            label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault()).trimEnd('.')) },
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_phone), contentDescription = null, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(stringResource(R.string.editor_phone_backup), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.editor_phone_backup_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = phoneBackup, onCheckedChange = { phoneBackup = it })
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

            if (existing != null) {
                TextButton(onClick = { onDelete(existing) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.editor_delete), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
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
