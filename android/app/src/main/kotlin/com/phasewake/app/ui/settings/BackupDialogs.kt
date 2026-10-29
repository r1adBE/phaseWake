package com.phasewake.app.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.backup.BackupManager
import com.phasewake.app.backup.BackupPreview
import com.phasewake.app.core.BackupException
import com.phasewake.app.core.BackupFile
import com.phasewake.app.phoneApp
import com.phasewake.app.security.DeviceAuth
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * «Sichern» und «Wiederherstellen» in der Gruppe «Daten»: Passwort wählen, Datei über das System speichern;
 * Datei wählen, Passwort, Vorschau, Ersetzen (nach Rückfrage und Gerätesperre). Falsches Passwort: nichts ändert sich.
 */
@Composable
internal fun ColumnScope.BackupRows() {
    val context = LocalContext.current
    val res = LocalResources.current
    val app = context.phoneApp
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    // Bewusst nicht gesichert (kein Passwort im gespeicherten Zustand): Nach Drehen oder Prozessende fehlt es.
    var password by remember { mutableStateOf("") }
    // Datei schon gewählt, Passwort dabei verloren: noch einmal fragen, nie mit leerem Passwort verschlüsseln.
    var saveTo by rememberSaveable { mutableStateOf<Uri?>(null) }
    // Inhalt der gewählten Datei, schon gelesen: Nicht lesbar oder zu gross meldet «nicht lesbar», nie «Falsches Passwort».
    var restoreFrom by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun toast(text: Int) = Toast.makeText(context, res.getString(text), Toast.LENGTH_SHORT).show()
    fun write(uri: Uri, chosen: String) {
        scope.launch {
            busy = true
            val ok = BackupManager.create(context, app, uri, chosen)
            busy = false
            toast(if (ok) R.string.backup_done else R.string.backup_failed)
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME)) { uri ->
        val chosen = password
        password = ""
        if (uri == null) return@rememberLauncherForActivityResult
        if (BackupFile.passwordOk(chosen)) write(uri, chosen) else saveTo = uri
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val bytes = BackupManager.read(context, uri)
            busy = false
            if (bytes == null) toast(R.string.restore_unreadable) else restoreFrom = bytes
        }
    }

    DataRow(R.string.settings_backup, R.string.settings_backup_hint) { creating = true }
    DataRow(R.string.settings_restore, null) { pick.launch(arrayOf("*/*")) }
    if (busy) Text(stringResource(R.string.backup_busy), style = MaterialTheme.typography.bodySmall)

    if (creating) {
        NewPasswordDialog(
            onSave = { chosen ->
                creating = false
                password = chosen
                save.launch(res.getString(R.string.backup_file_name, LocalDate.now().toString()))
            },
            onDismiss = { creating = false },
        )
    }
    saveTo?.let { uri ->
        NewPasswordDialog(
            onSave = { chosen ->
                saveTo = null
                write(uri, chosen)
            },
            onDismiss = {
                saveTo = null
                // Das System hat die Datei schon angelegt; ohne Passwort bliebe sie leer liegen.
                app.appScope.launch { BackupManager.discard(app, uri) }
            },
        )
    }
    restoreFrom?.let { bytes ->
        OpenDialog(
            onOpen = { entered, onWrong ->
                scope.launch {
                    busy = true
                    try {
                        preview = BackupManager.open(bytes, entered)
                        restoreFrom = null
                    } catch (_: BackupException) {
                        // Grund bewusst nicht unterschieden: Falsches Passwort und fremde Datei sehen gleich aus.
                        onWrong()
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = { restoreFrom = null },
        )
    }
    preview?.let { found ->
        RestoreConfirmDialog(
            alarms = found.alarms,
            nights = found.nights,
            noteDays = found.noteDays,
            onConfirm = {
                preview = null
                DeviceAuth.confirm(context, res.getString(R.string.auth_restore)) {
                    app.appScope.launch {
                        BackupManager.restore(app, found.payload)
                        scope.launch { toast(R.string.restore_done) }
                    }
                }
            },
            onDismiss = { preview = null },
        )
    }
}

/** Rückfrage vor dem Ersetzen mit der Vorschau: Anzahl Wecker, Nächte und Tage mit Notizen der Sicherung. */
@Composable
internal fun RestoreConfirmDialog(alarms: Int, nights: Int, noteDays: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_confirm_title)) },
        text = {
            Column {
                Text(stringResource(R.string.restore_preview, alarms, nights, noteDays), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.restore_confirm_text), modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.restore_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Eine Zeile der Gruppe «Daten» (wie «Verlauf teilen»). */
@Composable
private fun DataRow(title: Int, hint: Int?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Passwort zweimal; mindestens [BackupFile.MIN_PASSWORD] Zeichen ([BackupFile.passwordOk]). */
@Composable
internal fun NewPasswordDialog(onSave: (String) -> Unit, onDismiss: () -> Unit, initial: String = "", initialRepeat: String = "") {
    // [initial] und [initialRepeat]: nur für Bildschirmfotos; in der App beginnt der Dialog leer.
    var first by remember { mutableStateOf(initial) }
    var second by remember { mutableStateOf(initialRepeat) }
    val tooShort = !BackupFile.passwordOk(first)
    val mismatch = second.isNotEmpty() && second != first
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup)) },
        text = {
            Column {
                PasswordField(first, R.string.backup_password) { first = it }
                PasswordField(second, R.string.backup_password_repeat) { second = it }
                val message = when {
                    first.isNotEmpty() && tooShort -> R.string.backup_password_short
                    mismatch -> R.string.backup_password_mismatch
                    else -> R.string.backup_password_hint
                }
                Text(
                    stringResource(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message == R.string.backup_password_hint) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !tooShort && second == first, onClick = { onSave(first) }) { Text(stringResource(R.string.backup_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Passwort der Sicherung eingeben; [onOpen] meldet ein falsches Passwort über den zweiten Rückruf. */
@Composable
private fun OpenDialog(onOpen: (String, () -> Unit) -> Unit, onDismiss: () -> Unit) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_restore)) },
        text = {
            Column {
                PasswordField(entered, R.string.backup_password) {
                    entered = it
                    wrong = false
                }
                if (wrong) {
                    Text(stringResource(R.string.restore_wrong_password), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = entered.isNotEmpty(), onClick = { onOpen(entered) { wrong = true } }) { Text(stringResource(R.string.restore_open)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PasswordField(value: String, label: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/** Dateityp der Sicherung beim Speichern (eigene Endung `.pwbak`, binär). */
private const val MIME = "application/octet-stream"
