package com.phasewake.app.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lizenzen der App (aus «Über» → «Lizenzen»): je Lizenz die Bestandteile, Antippen klappt den Volltext auf.
 * Die Texte kommen aus den Assets (Ordner `licenses/` des Repositorys), ohne Netz.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf<String?>(null) }
    val texts = remember { mutableStateMapOf<String, List<String>>() }
    LaunchedEffect(open) {
        val file = open ?: return@LaunchedEffect
        if (file !in texts) texts[file] = withContext(Dispatchers.IO) { Licenses.paragraphs(readLicense(context, file)) }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.onb_back)) }
            Text(stringResource(R.string.settings_licenses), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Licenses.entries.forEach { entry ->
                item(key = entry.file) {
                    LicenseHeader(entry, expanded = open == entry.file) { open = if (open == entry.file) null else entry.file }
                }
                if (open == entry.file) {
                    items(texts[entry.file].orEmpty()) { paragraph ->
                        Text(paragraph, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
        }
    }
}

/** Kopf einer Lizenz: Name, Datei und Bestandteile; der Pfeil zeigt, ob der Volltext offen ist. */
@Composable
private fun LicenseHeader(entry: LicenseEntry, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_expand), contentDescription = null, modifier = Modifier.rotate(if (expanded) 180f else 0f))
        }
        entry.components.forEach { component ->
            Text(component, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

/** Liest den Lizenztext [file] aus den Assets; fehlt er, bleibt die Liste leer statt abzustürzen. */
private fun readLicense(context: Context, file: String): String =
    runCatching { context.assets.open(file).bufferedReader().use { it.readText() } }.getOrDefault("")
