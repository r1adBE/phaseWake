package com.phasewake.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.Routine
import com.phasewake.app.core.RoutineItem
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.ui.home.routineName

// Bausteine der Einstellungen: Gruppe, Schalter-Zeile, eigene Notizen, Abendroutine.

/** Eigene Schlafnotizen: hinzufügen, umbenennen, löschen (höchstens [SleepNotes.MAX_CUSTOM]). */
@Composable
internal fun CustomNotesGroup(
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
internal fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
internal fun RoutineGroup(items: List<RoutineItem>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
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

@Composable
internal fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
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

/** Gruppe «Daten»: Teilen, Sichern und Wiederherstellen, Verlauf löschen (mit Rückfrage). */
@Composable
internal fun DataGroup(onExport: () -> Unit, canExport: Boolean, onClearHistory: () -> Unit, extras: SettingsExtras) {
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
        extras.backup(this)
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
}

/** Gruppe «Über»: Links, Lizenzen, Einrichtung wiederholen, Diagnose und Version. */
@Composable
internal fun AboutGroup(version: String, extras: SettingsExtras) {
    val uri = LocalUriHandler.current
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
        Text(stringResource(R.string.settings_license_holidays), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        extras.onOnboarding?.let { onOnboarding ->
            Text(
                stringResource(R.string.settings_onboarding),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOnboarding).padding(top = 12.dp, bottom = 4.dp),
            )
        }
        if (extras.diagnostics) {
            Text(
                stringResource(R.string.diag_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable(onClick = extras.onDiagnostics).padding(vertical = 4.dp),
            )
        }
        VersionLine(version, extras)
    }
}
