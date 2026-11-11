package com.phasewake.app.wear.ui

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import com.phasewake.app.wear.ui.components.rememberSkyInk
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.phasewake.app.core.Snooze
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.components.SunriseSky
import com.phasewake.app.wear.ui.theme.Brand
import com.phasewake.app.wear.wake.RingUi
import java.time.ZoneId

// Teile des Weckbildschirms der Uhr (RingScreen): Anordnung, Halten, Name und Grund, Schlummern.

/** Mindesthöhe des Schlummer-Knopfs (Wear-Richtlinie: Tippziel 48 dp). */
private val SNOOZE_MIN_HEIGHT = 48.dp

/** Mindesthöhe des Stopp-Knopfs; zum Halten genügt weniger als beim Tippen, der Punkt darüber hält ebenfalls. */
private val STOP_MIN_HEIGHT = 40.dp

/** Was bei Platzmangel zuerst wegfällt ([FitColumn]): der Punkt (Halten geht auch am Knopf), dann der Grund. */
internal enum class Optional { DOT, REASON }

/**
 * Spalte, mittig im verfügbaren Platz ([heightShare] der Höhe). Ist der Inhalt zu hoch, fallen die mit
 * [Optional] markierten Teile weg: zuerst [Optional.DOT], dann [Optional.REASON]. So bleiben Stopp und Schlummern
 * immer ganz sichtbar, auch mit grosser Schrift.
 */
@Composable
internal fun FitColumn(heightShare: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.layoutId to it.measure(loose) }
        val budget = (constraints.maxHeight * heightShare).toInt()
        var shown = placeables
        for (drop in Optional.entries) {
            if (shown.sumOf { it.second.height } <= budget) break
            shown = shown.filter { it.first != drop }
        }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            var y = (height - shown.sumOf { it.second.height }) / 2
            shown.forEach { (_, p) ->
                p.placeRelative((width - p.width) / 2, y)
                y += p.height
            }
        }
    }
}

/**
 * «Halten zum Stoppen» als gefüllter Hauptknopf in Lavendel (Audit U-4), wie auf dem Handy: beim Halten läuft
 * von links eine helle Füllung darüber. Dunkle Schrift auf Lavendel und auf der Füllung: Kontrast ≥ 4.5:1.
 */
@Composable
internal fun HoldToStop(label: String, progress: Float, compact: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Box(
        contentAlignment = Alignment.Center,
        // [modifier]: das Halten (vor dem Innenabstand, damit der ganze Knopf zählt).
        modifier = modifier
            .heightIn(min = STOP_MIN_HEIGHT)
            // Auch mit Aufgabe beendet die Bedienungshilfe (Notausgang, wie langes Halten).
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                onClick(label = label) { onDismiss(); true }
            }
            .clip(RoundedCornerShape(50))
            .background(Brand.Wave)
            .drawBehind { drawRect(Brand.Moon, size = Size(size.width * progress, size.height)) }
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 4.dp),
    ) {
        // Lange Übersetzungen und der Hinweis zur Aufgabe brechen um statt abgeschnitten zu werden.
        Text(
            label,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = Brand.NightBottom,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Schrift auf dem Nachthimmel. */
internal val NIGHT_INK = SunriseSky.Ink(Brand.Moon, Brand.Mist)

/** Name und Grund; [light]: Sonnenaufgang (Schrift nach der Stelle), `null` = Nachthimmel. Leerer Grund: keine Zeile. */
@Composable
internal fun Ringing(ui: RingUi.Ringing, reason: String, fit: ReasonFit, light: Float?) {
    // Der Name einzeilig: Lange Namen dürfen den Schlummer-Knopf nicht nach unten schieben. Der Grund nach [ReasonFit].
    val (titleInk, titlePlace) = rememberSkyInk(light, NIGHT_INK)
    Text(
        ui.label.ifBlank { stringResource(R.string.ring_greeting) },
        style = MaterialTheme.typography.titleMedium,
        color = titleInk.content,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = titlePlace,
    )
    if (reason.isEmpty()) return
    val (reasonInk, reasonPlace) = rememberSkyInk(light, NIGHT_INK)
    Text(
        reason,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = fit.fontSize, lineHeight = fit.lineHeight),
        color = reasonInk.muted,
        textAlign = TextAlign.Center,
        maxLines = fit.lines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.layoutId(Optional.REASON).then(reasonPlace),
    )
}

/**
 * «Schlummern 5 Min» (Audit F-10), darunter klein «noch N» (r1AD 10.11.2026), beide Zeilen mittig; auch auf kleinen
 * Uhren mindestens 48 dp hoch (Ziel für den Finger). «Schlummern 5 Min» bricht bei Bedarf auf zwei Zeilen um, «noch N»
 * bleibt einzeilig; dass der Knopf mit grosser Schrift ganz sichtbar bleibt, sichern [MAX_FONT_SCALE] und [FitColumn].
 */
@Composable
internal fun SnoozeButton(snoozesLeft: Int, onSnooze: () -> Unit, compact: Boolean, modifier: Modifier = Modifier) {
    val res = LocalResources.current
    Button(
        onClick = onSnooze,
        modifier = modifier.heightIn(min = SNOOZE_MIN_HEIGHT),
        colors = ButtonDefaults.buttonColors(containerColor = Brand.Deep, contentColor = Brand.Moon, secondaryContentColor = Brand.Mist),
        contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 16.dp, vertical = 2.dp),
        secondaryLabel = {
            Text(
                res.getQuantityString(R.plurals.ring_snoozes_left, snoozesLeft, snoozesLeft),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        label = {
            // Auf kleinen Uhren etwas kleiner; zu lang bricht um (Knopf wird höher) statt abgeschnitten zu werden.
            Text(
                stringResource(R.string.ring_snooze_button, Snooze.MINUTES),
                modifier = Modifier.fillMaxWidth(),
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
internal fun Snoozed(ui: RingUi.Snoozed, light: Float?) {
    val until = timeFormatter().format(ui.until.atZone(ZoneId.systemDefault()))
    val (ink, place) = rememberSkyInk(light, NIGHT_INK)
    Text(
        stringResource(R.string.ring_snoozed_until, until),
        style = MaterialTheme.typography.titleMedium,
        color = ink.content,
        textAlign = TextAlign.Center,
        modifier = place,
    )
    // Beenden auch hier durch Halten (Punkt oder Knopf darunter), auch mit Weckaufgabe.
}
