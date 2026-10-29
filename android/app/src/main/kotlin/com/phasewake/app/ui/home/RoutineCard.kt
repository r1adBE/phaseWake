package com.phasewake.app.ui.home

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.Routine
import com.phasewake.app.core.RoutineItem

/** Anzeigename eines Punkts der Abendroutine: Vorschläge übersetzt, eigene mit ihrem Text. */
fun routineName(context: Context, item: RoutineItem): String = when (item.id) {
    Routine.PHONE -> context.getString(R.string.routine_item_phone)
    Routine.LIGHT -> context.getString(R.string.routine_item_light)
    Routine.WATER -> context.getString(R.string.routine_item_water)
    else -> item.text
}

/** Startseite am Abend: die Abendroutine zum Abhaken (gilt für die kommende Nacht). Ohne Punkte nichts. */
@Composable
fun RoutineCard(items: List<RoutineItem>, done: List<String>, containerColor: Color, onToggle: (String) -> Unit) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(stringResource(R.string.routine_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            items.forEach { item ->
                val checked = item.id in done
                // Die ganze Zeile hakt ab; das Kästchen zeigt nur den Zustand.
                Row(
                    Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(item.id) }).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text(routineName(context, item), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
