package com.phasewake.app.diag

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phasewake.app.R
import com.phasewake.app.core.DiagEntry
import com.phasewake.app.core.DiagLog
import com.phasewake.app.phoneApp
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Versteckte Diagnose (7× auf die Versionszeile): Zustand, Ereignisse von Handy und Uhr, Teilen und Leeren.
 * Nur lokal; geteilt wird nur, was der Nutzer selbst über das Teilen-Menü weitergibt.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, onHide: () -> Unit) {
    val context = LocalContext.current
    val app = context.phoneApp
    val scope = rememberCoroutineScope()
    val report by WatchReport.report.collectAsStateWithLifecycle()
    val changes by app.diag.changes.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf(emptyList<StatusLine>()) }
    var phoneEntries by remember { mutableStateOf(emptyList<DiagEntry>()) }
    var noAnswer by remember { mutableStateOf(false) }
    LaunchedEffect(report, changes) {
        status = PhoneStatus.lines(context, app, report)
        phoneEntries = withContext(Dispatchers.IO) { app.diag.entries() }
    }
    val entries = (phoneEntries + report?.entries.orEmpty()).sortedByDescending { it.at }
    DiagnosticsContent(
        status = status,
        entries = entries,
        noAnswer = noAnswer,
        actions = DiagnosticsActions(
            onBack = onBack,
            onHide = onHide,
            onShare = { share(context, status, entries) },
            onFetchWatch = { scope.launch { noAnswer = WatchReport.fetch(app) == null } },
            onClear = { scope.launch(Dispatchers.IO) { app.diag.clear() } },
        ),
    )
}

/** Knöpfe der Diagnose-Seite. */
internal class DiagnosticsActions(
    val onBack: () -> Unit,
    val onHide: () -> Unit,
    val onShare: () -> Unit,
    val onFetchWatch: () -> Unit,
    val onClear: () -> Unit,
)

/**
 * Diagnose-Seite ohne eigenen Zustand (auch für Bildschirmfotos): [status] als «Bezeichnung: Wert», [entries]
 * neueste zuerst; [noAnswer]: Die Uhr hat auf «Von der Uhr holen» nicht geantwortet.
 */
@Composable
internal fun DiagnosticsContent(status: List<StatusLine>, entries: List<DiagEntry>, noAnswer: Boolean, actions: DiagnosticsActions) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = actions.onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.onb_back)) }
            Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text(stringResource(R.string.diag_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                Row(
                    Modifier.fillMaxWidth().toggleable(value = true, role = Role.Switch, onValueChange = { actions.onHide() }).padding(vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.diag_show), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Switch(checked = true, onCheckedChange = null)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = actions.onShare) { Text(stringResource(R.string.diag_share)) }
                    OutlinedButton(onClick = actions.onFetchWatch) { Text(stringResource(R.string.diag_fetch_watch)) }
                    OutlinedButton(onClick = actions.onClear) { Text(stringResource(R.string.diag_clear)) }
                }
            }
            if (noAnswer) {
                item { Text(stringResource(R.string.diag_watch_no_answer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            item { Text(stringResource(R.string.diag_status), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(status) { line ->
                Text("${line.label}: ${line.value}", style = MaterialTheme.typography.bodyMedium)
            }
            item { Text(stringResource(R.string.diag_events), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            if (entries.isEmpty()) {
                item { Text(stringResource(R.string.diag_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            items(entries) { entry ->
                // Gerät als Wort (nicht nur als Farbe): «phone» bzw. «watch» steht in jeder Zeile.
                Text(DiagLog.format(listOf(entry), ZoneId.systemDefault()), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Teilt Zustand und Protokoll als Text über das Teilen-Menü. */
private fun share(context: Context, status: List<StatusLine>, entries: List<DiagEntry>) {
    val text = status.joinToString("\n") { "${it.label}: ${it.value}" } + "\n\n" + DiagLog.format(entries, ZoneId.systemDefault())
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diag_title))
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
