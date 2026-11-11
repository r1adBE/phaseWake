package com.phasewake.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/**
 * Zeile einer Liste (Gestaltung 3, Audit N-7): Titel, optional Erklärung, Pfeil am Ende; mindestens [LIST_ROW_HEIGHT]
 * hoch, die ganze Zeile ist das Tippziel. [color]: Farbe des Titels (Vorgabe: Akzent; «Verlauf löschen»: Fehlerfarbe).
 */
@Composable
internal fun ListRow(
    title: String,
    hint: String? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = LIST_ROW_HEIGHT)
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(
            painterResource(R.drawable.ic_chevron_end),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Mindesthöhe einer Listenzeile (Material: einzeilige Liste 56 dp). */
internal val LIST_ROW_HEIGHT = 56.dp

/** Mindesthöhe jeder antippbaren Zeile (Tippziel). */
internal val MIN_TOUCH = 48.dp

/** Zeile mit Titel, Erklärung und Schalter; die ganze Zeile schaltet. */
@Composable
internal fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH).toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(top = 8.dp),
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
        ListRow(stringResource(R.string.settings_export), stringResource(R.string.settings_export_hint), enabled = canExport, onClick = onExport)
        extras.backup(this)
        var confirm by remember { mutableStateOf(false) }
        ListRow(stringResource(R.string.settings_clear_history), color = MaterialTheme.colorScheme.error) { confirm = true }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text(stringResource(R.string.settings_clear_history)) },
                // Nur Android: Die Lernphase gibt es am iPhone noch nicht, darum ein eigener Satz statt eines geänderten Texts.
                text = { Text(stringResource(R.string.settings_clear_history_text) + " " + stringResource(R.string.settings_clear_history_learn)) },
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
        ListRow(stringResource(R.string.settings_open_source)) { uri.openUri(REPO_URL) }
        ListRow(stringResource(R.string.settings_privacy)) { uri.openUri(PRIVACY_URL) }
        // Mit Lizenz-Seite: Zeile mit Pfeil, die Kurzfassung darunter; sonst nur die Kurzfassung.
        val licenses = listOf(
            stringResource(R.string.settings_licenses_text),
            stringResource(R.string.settings_license_holidays),
        ).joinToString("\n")
        val onLicenses = extras.onLicenses
        if (onLicenses != null) {
            ListRow(stringResource(R.string.settings_licenses), licenses, onClick = onLicenses)
        } else {
            Text(stringResource(R.string.settings_licenses), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            Text(licenses, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        extras.onOnboarding?.let { ListRow(stringResource(R.string.settings_onboarding), onClick = it) }
        if (extras.diagnostics) ListRow(stringResource(R.string.diag_title), onClick = extras.onDiagnostics)
        VersionLine(version, extras)
    }
}
