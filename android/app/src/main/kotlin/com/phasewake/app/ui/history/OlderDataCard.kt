package com.phasewake.app.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.ui.components.skyCardColors
import com.phasewake.app.ui.components.skyCardEdge
import com.phasewake.app.ui.components.skyCardLift

/**
 * Einmaliger Hinweis oben im Verlauf (nur Android, Apple Health kennt die Grenze nicht): Health Connect ist verbunden,
 * ohne «Verlauf lesen» liest es aber nur ab 30 Tagen vor dem ersten Zugriff. [source]: Name der Quelle
 * ([com.phasewake.app.health.HealthSource.name]), `null`: allgemein «Gesundheitsdaten». [onAllow] fragt nur dieses
 * Recht an, [onLater] blendet aus; beide merken die Antwort ([of]).
 */
class OlderDataHint(val source: String?, val onAllow: () -> Unit, val onLater: () -> Unit) {
    companion object {
        /**
         * Hinweis, solange das Recht fehlt ([missing], [com.phasewake.app.health.HealthSleep.historyMissing]) und er
         * nicht beantwortet ist ([answered]); sonst `null`. Beide Knöpfe merken zuerst die Antwort ([markAnswered]),
         * «Freigeben» unabhängig davon, ob das Recht danach erteilt ist.
         */
        fun of(
            missing: Boolean,
            answered: Boolean,
            source: String?,
            onAllow: () -> Unit,
            markAnswered: () -> Unit,
        ): OlderDataHint? = if (!missing || answered) null else OlderDataHint(
            source,
            onAllow = {
                markAnswered()
                onAllow()
            },
            onLater = markAnswered,
        )

        /**
         * Quelle im Text: der Name, wenn alle gelesenen Nächte aus derselben App kommen (wie der Hinweis unter der
         * Liste), sonst `null`.
         */
        fun source(names: List<String?>): String? = names.distinct().singleOrNull()
    }
}

/** Karte des Hinweises [hint]: eine Zeile, darunter «Nicht jetzt» und «Freigeben». */
@Composable
internal fun OlderDataCard(hint: OlderDataHint, modifier: Modifier = Modifier) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = skyCardColors(),
        border = skyCardEdge(),
        elevation = skyCardLift(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp)) {
            Text(
                hint.source?.let { stringResource(R.string.history_older_title, it) }
                    ?: stringResource(R.string.history_older_title_unknown),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = hint.onLater) { Text(stringResource(R.string.history_older_later)) }
                TextButton(onClick = hint.onAllow) { Text(stringResource(R.string.history_older_allow)) }
            }
        }
    }
}
