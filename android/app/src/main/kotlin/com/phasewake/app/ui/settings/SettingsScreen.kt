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
import com.phasewake.app.ui.theme.ThemeMode

/** Einstellungen: Darstellung, Daten, Über. */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    version: String,
    contentPadding: PaddingValues,
    onThemeMode: (ThemeMode) -> Unit,
    onExport: () -> Unit,
    onClearHistory: () -> Unit,
    health: HealthState,
    onConnectHealth: () -> Unit,
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

        Group(stringResource(R.string.settings_data)) {
            Text(stringResource(R.string.settings_data_text), style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.fillMaxWidth().clickable(onClick = onExport).padding(vertical = 10.dp)) {
                Text(stringResource(R.string.settings_export), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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

/** Zustand der Verbindung zu Health Connect. */
enum class HealthState { UNAVAILABLE, AVAILABLE, CONNECTED }

private const val SPONSOR_URL = "https://github.com/sponsors/r1adBE"
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
