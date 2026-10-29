package com.phasewake.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.DiagUnlock
import com.phasewake.app.ui.Format
import java.time.LocalDate
import java.util.Locale

/** Neues der Einstellungen (Runde 3); Vorgaben = nichts davon (Bildschirmfotos). */
class SettingsExtras(
    /** «In 2 Minuten testen»; `null` = keine Gruppe. */
    val onTest: (() -> Unit)? = null,
    /** Feiertage: Länder, Wahl und nächster Tag; `null` = keine Gruppe. */
    val holidays: HolidaysUi? = null,
    /** Zeilen «Sichern» und «Wiederherstellen» samt Dialogen (in der Gruppe «Daten»). */
    val backup: @Composable ColumnScope.() -> Unit = {},
    /** «Einrichtung wiederholen»; `null` = keine Zeile. */
    val onOnboarding: (() -> Unit)? = null,
    /** Diagnose freigeschaltet: Zeile «Diagnose» unter «Über». */
    val diagnostics: Boolean = false,
    val onDiagnostics: () -> Unit = {},
    /** 7× auf die Versionszeile getippt. */
    val onUnlockDiagnostics: () -> Unit = {},
)

/** Feiertage in den Einstellungen: [countries] als (Code, Name), [regions] des gewählten Landes als (Code, Name). */
class HolidaysUi(
    val countries: List<Pair<String, String>>,
    val country: String,
    val regions: List<Pair<String, String>>,
    val region: String,
    val next: LocalDate?,
    val onChoose: (country: String, region: String) -> Unit,
)

/** Landesname in der Sprache des Geräts; unbekannter Code bleibt, wie er ist. */
internal fun countryName(code: String, locale: Locale): String =
    runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(locale) }.getOrNull()?.takeIf { it.isNotBlank() } ?: code

/** Gruppe «Wecker testen». */
@Composable
internal fun TestGroup(onTest: () -> Unit) {
    Group(stringResource(R.string.test_title)) {
        Text(stringResource(R.string.test_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onTest) { Text(stringResource(R.string.test_button)) }
    }
}

/** Gruppe «Feiertage»: Land und Region (je eine Auswahl), Hinweis auf lokale Feiertage, nächster Feiertag. */
@Composable
internal fun HolidaysGroup(ui: HolidaysUi) {
    val locale = LocalConfiguration.current.locales[0]
    var picking by remember { mutableStateOf<String?>(null) }
    Group(stringResource(R.string.settings_holidays)) {
        val countryText = if (ui.country.isEmpty()) stringResource(R.string.holidays_off) else countryName(ui.country, locale)
        ChoiceRow(stringResource(R.string.holidays_country), countryText) { picking = "country" }
        if (ui.country.isNotEmpty() && ui.regions.isNotEmpty()) {
            val regionText = ui.regions.firstOrNull { it.first == ui.region }?.second ?: stringResource(R.string.holidays_whole_country)
            ChoiceRow(stringResource(R.string.holidays_region), regionText) { picking = "region" }
        }
        ui.next?.let { Text(stringResource(R.string.holidays_next, Format.shortDay(it, locale)), style = MaterialTheme.typography.bodyMedium) }
        Text(stringResource(R.string.holidays_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    when (picking) {
        "country" -> PickDialog(
            title = stringResource(R.string.holidays_country),
            options = listOf("" to stringResource(R.string.holidays_off)) + ui.countries.sortedBy { it.second },
            selected = ui.country,
            onPick = { ui.onChoose(it, "") },
            onDismiss = { picking = null },
        )
        "region" -> PickDialog(
            title = stringResource(R.string.holidays_region),
            options = listOf("" to stringResource(R.string.holidays_whole_country)) + ui.regions.sortedBy { it.second },
            selected = ui.region,
            onPick = { ui.onChoose(ui.country, it) },
            onDismiss = { picking = null },
        )
    }
}

/** Zeile «Bezeichnung – Wert»; antippen öffnet die Auswahl. */
@Composable
private fun ChoiceRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** Auswahl aus einer langen Liste mit Suchfeld (Länder, Regionen). */
@Composable
private fun PickDialog(title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (options.size > SEARCH_FROM) {
                    OutlinedTextField(value = filter, onValueChange = { filter = it }, label = { Text(title) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(options.filter { it.second.contains(filter.trim(), ignoreCase = true) }, key = { it.first }) { (code, name) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = code == selected, role = Role.RadioButton) {
                                onPick(code)
                                onDismiss()
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = code == selected, onClick = null)
                            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Ab so vielen Einträgen hat die Auswahl ein Suchfeld. */
private const val SEARCH_FROM = 12

/**
 * Versionszeile; 7× antippen schaltet die Diagnose frei (ab dem 4. Mal «Noch N Mal»), wie die
 * Entwickleroptionen von Android. Ist sie schon frei, öffnet ein Tippen sie.
 */
@Composable
internal fun VersionLine(version: String, extras: SettingsExtras) {
    val context = LocalContext.current
    val res = LocalResources.current
    var taps by remember { mutableIntStateOf(0) }
    Text(
        stringResource(R.string.settings_version, version),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable {
            if (extras.diagnostics) {
                extras.onDiagnostics()
                return@clickable
            }
            taps++
            DiagUnlock.remaining(taps)?.let { Toast.makeText(context, res.getQuantityString(R.plurals.diag_taps_left, it, it), Toast.LENGTH_SHORT).show() }
            if (DiagUnlock.unlocked(taps)) {
                Toast.makeText(context, res.getString(R.string.diag_unlocked), Toast.LENGTH_SHORT).show()
                extras.onUnlockDiagnostics()
            }
        }.padding(top = 12.dp, bottom = 4.dp),
    )
}
