package com.phasewake.app.ui.notes

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.NoteEffect
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.util.Locale

/** Anzeigename einer Notiz; `null` für eine gelöschte eigene Notiz (vergangene Nächte zeigen sie nicht). */
fun noteName(context: Context, id: String, custom: List<CustomNote>): String? = when (id) {
    SleepNotes.CAFFEINE -> context.getString(R.string.note_caffeine)
    SleepNotes.ALCOHOL -> context.getString(R.string.note_alcohol)
    SleepNotes.EXERCISE -> context.getString(R.string.note_exercise)
    SleepNotes.STRESS -> context.getString(R.string.note_stress)
    SleepNotes.LATE_MEAL -> context.getString(R.string.note_late_meal)
    SleepNotes.SCREEN -> context.getString(R.string.note_screen)
    SleepNotes.NAP -> context.getString(R.string.note_nap)
    SleepNotes.SICK -> context.getString(R.string.note_sick)
    else -> custom.firstOrNull { it.id == id }?.name
}

/** Alle wählbaren Notizen als umschaltbare Chips: die festen, dann die eigenen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteChips(selected: List<String>, custom: List<CustomNote>, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ids = SleepNotes.FIXED + custom.map { it.id }
    val brand = PhaseWakeTheme.brand
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ids.forEach { id ->
            val name = noteName(context, id, custom) ?: return@forEach
            val on = id in selected
            // Farbwelt B: Rand Lavendel, gewählt gefüllt statt umrandet.
            FilterChip(
                selected = on,
                onClick = { onToggle(id) },
                label = { Text(name) },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = brand.chipSelected,
                    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = on,
                    borderColor = brand.chipBorder,
                    selectedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

/** Startseite am Abend: «Notizen für heute Nacht». */
@Composable
fun TonightNotesCard(selected: List<String>, custom: List<CustomNote>, containerColor: Color, onToggle: (String) -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.notes_title), style = MaterialTheme.typography.titleMedium)
            NoteChips(selected, custom, onToggle, Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * Notizen einer vergangenen Nacht (Morgenbericht, Verlauf): eine Zeile mit den gesetzten Notizen,
 * antippen klappt die Chips zum Ändern auf.
 */
@Composable
fun NightNotes(selected: List<String>, custom: List<CustomNote>, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    val names = selected.mapNotNull { noteName(context, it, custom) }
    Column(modifier.fillMaxWidth()) {
        Text(
            if (names.isEmpty()) stringResource(R.string.notes_label) else stringResource(R.string.notes_value, names.joinToString(", ")),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(vertical = 8.dp),
        )
        AnimatedVisibility(open) { NoteChips(selected, custom, onToggle) }
    }
}

/** «Was deinen Schlaf beeinflusst»: Notizen mit deutlichem Unterschied im Befinden. */
@Composable
fun NoteEffectsCard(effects: List<NoteEffect>, custom: List<CustomNote>) {
    val context = LocalContext.current
    val res = LocalResources.current
    // Gelöschte eigene Notizen erscheinen nicht.
    val known = effects.mapNotNull { effect -> noteName(context, effect.noteId, custom)?.let { effect to it } }
    val notable = known.filter { it.first.isNotable }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.notes_effects_title), style = MaterialTheme.typography.titleSmall)
            when {
                notable.isNotEmpty() -> notable.forEach { (effect, name) ->
                    Text(
                        res.getQuantityString(R.plurals.notes_effect_line, effect.nightsWithout, name, signed(effect.delta), effect.nightsWith, effect.nightsWithout),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                known.isNotEmpty() -> Text(stringResource(R.string.notes_effect_none), style = MaterialTheme.typography.bodyMedium)
                else -> Text(
                    stringResource(R.string.notes_effect_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** «+0,5» bzw. «−0,8» (echtes Minuszeichen), eine Nachkommastelle nach Sprache. */
private fun signed(value: Double): String =
    String.format(Locale.getDefault(), "%+.1f", value).replace('-', '−')

/** Eine Zeile «Nach ‹Alkohol› Befinden −0,8 (3 vs. 7 Nächte)»; `null` für eine gelöschte eigene Notiz. */
fun noteEffectLine(context: Context, effect: NoteEffect, custom: List<CustomNote>): String? {
    val name = noteName(context, effect.noteId, custom) ?: return null
    return context.resources.getQuantityString(R.plurals.notes_effect_line, effect.nightsWithout, name, signed(effect.delta), effect.nightsWith, effect.nightsWithout)
}
