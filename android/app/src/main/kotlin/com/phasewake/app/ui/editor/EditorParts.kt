package com.phasewake.app.ui.editor

import android.content.Context
import android.media.RingtoneManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.phasewake.app.R
import kotlin.math.roundToInt

// Bausteine des Wecker-Editors: Abschnitt, Auswahl, Tag-Schalter, Weckton-Zeile, verkleinerter Uhrzeit-Wähler.

@Composable
internal fun AlertLabel(icon: Int, label: Int) {
    Row(Modifier.padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> Choice(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(stringResource(label), maxLines = 1) }
        }
    }
}

/** Eine Zeile der Weckton-Auswahl (Optionsfeld, Name, rechts optional der gewählte Systemton). */
@Composable
internal fun ToneRow(label: String, selected: Boolean, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp).weight(1f))
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Anzeigename des gewählten Systemtons; leer = Standard-Weckton. */
internal fun soundTitle(context: Context, sound: String): String {
    if (sound.isEmpty()) return context.getString(R.string.editor_sound_default)
    return runCatching { RingtoneManager.getRingtone(context, sound.toUri())?.getTitle(context) }.getOrNull()
        ?: context.getString(R.string.editor_sound_default)
}

@Composable
internal fun Section(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
    }
}

/** Kleinster und grösster Wert des Schlafdauer-Schiebers (Minuten); AlarmSpec erlaubt 1–12 h. */
internal const val SLEEP_MIN = 180f
internal const val SLEEP_MAX = 720f

/** Runder Tag-Schalter; alle sieben passen in eine Zeile. */
@Composable
internal fun DayToggle(label: String, description: String, selected: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(if (selected) colors.primary else colors.surfaceContainerHighest)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = description },
    ) {
        // Gewählt zusätzlich mit Punkt unter dem Kürzel: nicht nur die Füllfarbe zeigt es (Farbfehlsichtigkeit).
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) colors.onPrimary else colors.onSurface,
                maxLines = 1,
                softWrap = false,
            )
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.onPrimary else Color.Transparent),
            )
        }
    }
}

/** Uhrzeit-Wähler etwas kleiner als die Vorgabe von Material: Er soll die Seite nicht beherrschen. */
internal const val TIME_PICKER_SCALE = 0.82f

/**
 * Verkleinert den Inhalt samt Platzbedarf (anders als `scale`, das nur zeichnet). Antippen und
 * Ziehen bleiben richtig, weil die Ebene die Eingaben mit umrechnet.
 */
internal fun Modifier.scaled(factor: Float): Modifier = layout { measurable, constraints ->
    val inner = constraints.copy(
        minWidth = 0,
        minHeight = 0,
        maxWidth = if (constraints.hasBoundedWidth) (constraints.maxWidth / factor).toInt() else constraints.maxWidth,
        maxHeight = if (constraints.hasBoundedHeight) (constraints.maxHeight / factor).toInt() else constraints.maxHeight,
    )
    val placeable = measurable.measure(inner)
    val width = (placeable.width * factor).roundToInt()
    val height = (placeable.height * factor).roundToInt()
    layout(width, height) {
        placeable.placeWithLayer((width - placeable.width) / 2, (height - placeable.height) / 2) {
            scaleX = factor
            scaleY = factor
        }
    }
}
