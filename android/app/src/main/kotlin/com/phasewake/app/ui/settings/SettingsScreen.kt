package com.phasewake.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.Bedtime
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.SleepGoal
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.core.Routine
import com.phasewake.app.core.RoutineItem
import com.phasewake.app.ui.home.routineName
import com.phasewake.app.ui.Format
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import java.time.Duration
import kotlin.math.roundToInt
import com.phasewake.app.ui.theme.ThemeMode

/** Einstellungen: Darstellung, Schlafziel und Bettzeit-Erinnerung, eigene Notizen, Daten, Über. */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    version: String,
    contentPadding: PaddingValues,
    onThemeMode: (ThemeMode) -> Unit,
    onExport: () -> Unit,
    /** Ohne Nächte gibt es nichts zu exportieren (nur eine Kopfzeile): dann ausgegraut. */
    canExport: Boolean = true,
    onClearHistory: () -> Unit,
    health: HealthState,
    onConnectHealth: () -> Unit,
    goalMinutes: Int = SleepGoal.DEFAULT_MINUTES,
    onGoal: (Int) -> Unit = {},
    bedtimeReminder: Boolean = false,
    onBedtimeReminder: (Boolean) -> Unit = {},
    customNotes: List<CustomNote> = emptyList(),
    onAddCustomNote: (String) -> Unit = {},
    onRenameCustomNote: (String, String) -> Unit = { _, _ -> },
    onDeleteCustomNote: (String) -> Unit = {},
    weeklyReview: Boolean = false,
    onWeeklyReview: (Boolean) -> Unit = {},
    soundRecDefault: Boolean = false,
    onSoundRecDefault: (Boolean) -> Unit = {},
    routineItems: List<RoutineItem> = emptyList(),
    onAddRoutineItem: (String) -> Unit = {},
    onRemoveRoutineItem: (String) -> Unit = {},
) {
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))

        Group(stringResource(R.string.settings_theme)) {
            val modes = listOf(
                ThemeMode.SYSTEM to R.string.settings_theme_system,
                ThemeMode.LIGHT to R.string.settings_theme_light,
                ThemeMode.DARK to R.string.settings_theme_dark,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { onThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    ) { Text(stringResource(label)) }
                }
            }
        }

        Group(stringResource(R.string.goal_title)) {
            val context = LocalContext.current
            // Beim Ziehen nur anzeigen, gespeichert (und die Erinnerung neu geplant) beim Loslassen.
            var dragging by remember(goalMinutes) { mutableFloatStateOf(goalMinutes.toFloat()) }
            Text(
                stringResource(R.string.goal_value, Format.duration(context, Duration.ofMinutes(dragging.roundToInt().toLong()))),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Slider(
                value = dragging,
                onValueChange = { dragging = SleepGoal.clamp(it.roundToInt()).toFloat() },
                onValueChangeFinished = { onGoal(dragging.roundToInt()) },
                valueRange = SleepGoal.MIN_MINUTES.toFloat()..SleepGoal.MAX_MINUTES.toFloat(),
                steps = (SleepGoal.MAX_MINUTES - SleepGoal.MIN_MINUTES) / SleepGoal.STEP_MINUTES - 1,
            )
            Text(stringResource(R.string.goal_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                Modifier.fillMaxWidth().toggleable(value = bedtimeReminder, role = Role.Switch, onValueChange = onBedtimeReminder).padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.bedtime_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.bedtime_text, Bedtime.LEAD_MINUTES),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Die ganze Zeile schaltet; der Schalter zeigt nur den Zustand.
                Switch(checked = bedtimeReminder, onCheckedChange = null)
            }
            SwitchRow(stringResource(R.string.weekly_title), stringResource(R.string.weekly_toggle), weeklyReview, onWeeklyReview)
        }

        CustomNotesGroup(customNotes, onAddCustomNote, onRenameCustomNote, onDeleteCustomNote)

        RoutineGroup(routineItems, onAddRoutineItem, onRemoveRoutineItem)

        Group(stringResource(R.string.sound_rec_title)) {
            SwitchRow(stringResource(R.string.sound_rec_default), stringResource(R.string.sound_rec_default_hint), soundRecDefault, onSoundRecDefault)
        }

        Group(stringResource(R.string.settings_data)) {
            Text(stringResource(R.string.settings_data_text), style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.fillMaxWidth().clickable(enabled = canExport, onClick = onExport).padding(vertical = 10.dp)) {
                Text(
                    stringResource(R.string.settings_export),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (canExport) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.settings_export_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            var confirm by remember { mutableStateOf(false) }
            Text(
                stringResource(R.string.settings_clear_history),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().clickable { confirm = true }.padding(vertical = 10.dp),
            )
            if (confirm) {
                AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text(stringResource(R.string.settings_clear_history)) },
                    text = { Text(stringResource(R.string.settings_clear_history_text)) },
                    confirmButton = {
                        TextButton(onClick = { confirm = false; onClearHistory() }) {
                            Text(stringResource(R.string.settings_clear_history_confirm), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
                )
            }
        }

        Group(stringResource(R.string.health_connect)) {
            Text(stringResource(R.string.health_connect_hint), style = MaterialTheme.typography.bodyMedium)
            when (health) {
                HealthState.UNAVAILABLE -> Text(
                    stringResource(R.string.health_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HealthState.CONNECTED -> Text(
                    stringResource(R.string.health_connected),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                HealthState.AVAILABLE -> Button(onClick = onConnectHealth) { Text(stringResource(R.string.health_connect)) }
                HealthState.UPDATE_REQUIRED -> Button(onClick = { uri.openUri(HEALTH_CONNECT_STORE) }) { Text(stringResource(R.string.health_update)) }
            }
        }

        Group(stringResource(R.string.settings_about)) {
            Text(
                stringResource(R.string.settings_open_source),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable { uri.openUri(REPO_URL) }.padding(vertical = 8.dp),
            )
            Text(
                stringResource(R.string.settings_privacy),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable { uri.openUri(PRIVACY_URL) }.padding(vertical = 8.dp),
            )
            Text(
                stringResource(R.string.settings_support),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable { uri.openUri(SPONSOR_URL) }.padding(top = 8.dp),
            )
            Text(stringResource(R.string.settings_support_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            Text(stringResource(R.string.settings_licenses), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            Text(stringResource(R.string.settings_licenses_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.settings_version, version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** Eigene Schlafnotizen: hinzufügen, umbenennen, löschen (höchstens [SleepNotes.MAX_CUSTOM]). */
@Composable
private fun CustomNotesGroup(
    notes: List<CustomNote>,
    onAdd: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    // Bearbeitete Notiz: `null` = keine, leere Kennung = neue Notiz.
    var editing by remember { mutableStateOf<CustomNote?>(null) }
    Group(stringResource(R.string.notes_custom_title)) {
        Text(
            stringResource(R.string.notes_custom_hint, SleepNotes.MAX_CUSTOM),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        notes.forEach { note ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(note.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { editing = note }) { Text(stringResource(R.string.notes_custom_rename)) }
                IconButton(onClick = { onDelete(note.id) }) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.notes_custom_delete, note.name))
                }
            }
        }
        if (notes.size < SleepNotes.MAX_CUSTOM) {
            TextButton(onClick = { editing = CustomNote("", "") }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.notes_custom_add))
            }
        }
    }
    editing?.let { note ->
        var name by remember(note) { mutableStateOf(note.name) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(if (note.id.isEmpty()) R.string.notes_custom_add else R.string.notes_custom_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("\n", "").take(SleepNotes.MAX_NAME_LENGTH) },
                    label = { Text(stringResource(R.string.editor_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = SleepNotes.cleanName(name).isNotEmpty(),
                    onClick = {
                        if (note.id.isEmpty()) onAdd(name) else onRename(note.id, name)
                        editing = null
                    },
                ) { Text(stringResource(R.string.editor_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Zeile mit Titel, Erklärung und Schalter; die ganze Zeile schaltet. */
@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Abendroutine: Punkte hinzufügen und entfernen (höchstens [Routine.MAX_ITEMS]). */
@Composable
private fun RoutineGroup(items: List<RoutineItem>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    Group(stringResource(R.string.routine_title)) {
        items.forEach { item ->
            val name = routineName(context, item)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemove(item.id) }) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.notes_custom_delete, name))
                }
            }
        }
        if (items.size < Routine.MAX_ITEMS) {
            TextButton(onClick = { adding = true }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.routine_add))
            }
        }
    }
    if (adding) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.routine_add)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.replace("\n", "").take(Routine.MAX_TEXT_LENGTH) },
                    label = { Text(stringResource(R.string.editor_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = Routine.cleanText(text).isNotEmpty(),
                    onClick = {
                        onAdd(text)
                        adding = false
                    },
                ) { Text(stringResource(R.string.editor_save)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Health Connect: fehlt, muss aktualisiert werden, ist bereit oder schon verbunden. */
enum class HealthState { UNAVAILABLE, UPDATE_REQUIRED, AVAILABLE, CONNECTED }

private const val HEALTH_CONNECT_STORE = "https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"
private const val SPONSOR_URL = "https://github.com/sponsors/r1adBE"
private const val PRIVACY_URL = "https://github.com/r1adBE/phaseWake/blob/main/docs/DATENSCHUTZ.md"
private const val REPO_URL = "https://github.com/r1adBE/phaseWake"

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
