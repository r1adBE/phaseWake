package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.HealthSession
import com.phasewake.app.core.NightStages
import com.phasewake.app.core.SoundClip
import com.phasewake.app.core.StageStats
import com.phasewake.app.health.HealthSource
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.notes.NightNotes
import com.phasewake.app.ui.sounds.soundSummary
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** «Wach 20 Min · REM 1 Std. 20 Min · Leicht 3 Std. · Tief 1 Std.» (nur Phasen mit Zeit), wie unter dem Hypnogramm. */
internal fun stageTotalsLine(context: Context, stats: NightStages): String = stats.totals.filter { it.minutes > 0 }
    .joinToString(" · ") { stageName(context, it.stage) + " " + Format.duration(context, Duration.ofMinutes(it.minutes)) }

/** Karte im Verlauf antippbar: führt zur Detailseite; [label] für Bildschirmleser («Details anzeigen»). */
internal fun Modifier.opensDetail(shape: Shape, label: String, onOpen: () -> Unit): Modifier =
    clip(shape).clickable(onClickLabel = label, onClick = onOpen)

/** Sichtbarer Hinweis unten rechts in der Karte: «Details ›». */
@Composable
internal fun DetailsLink() {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.End) {
        Text(stringResource(R.string.night_details) + " ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Mini-Hypnogramm mit einer Zeile Summen; für Bildschirmleser die Summen als Beschreibung. */
@Composable
internal fun MiniStages(model: HypnoModel, stats: NightStages) {
    val context = LocalContext.current
    val totals = stageTotalsLine(context, stats)
    val title = stringResource(R.string.chart_stages_title)
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).semantics(mergeDescendants = true) { contentDescription = "$title. $totals" }) {
        MiniHypnogram(model)
        Text(totals, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp).clearAndSetSemantics { })
    }
}

/** «Ohne Phase-Wake-Wecker»; die Quelle nennt nur der Hinweis darunter (einmal statt zweimal). */
internal fun healthMark(context: Context): String = context.getString(R.string.history_health_mark)

/**
 * Ausführlicher Hinweis: «Aus Samsung Health. Änderungen und Löschungen dort übernimmt Phase Wake automatisch.»
 * Die Nacht ist ein Spiegel der Quelle (bei jedem Öffnen neu gelesen, nie gespeichert).
 */
internal fun healthLiveHint(context: Context, session: HealthSession): String =
    HealthSource.name(context, session.source)?.let { context.getString(R.string.history_health_live, it) }
        ?: context.getString(R.string.history_health_live_unknown)

/** Zeiten und Dauern einer Sitzung: «Im Bett 23:10 · eingeschlafen 23:24 · auf 06:52» und «8 Std. im Bett · …». */
private fun sessionLines(context: Context, session: HealthSession, zone: ZoneId): Pair<String, String> {
    fun dur(minutes: Long) = Format.duration(context, Duration.ofMinutes(minutes))
    val awake = session.awakeMinutes
    return if (awake != null) {
        context.getString(R.string.history_health_times, Format.time(session.start, zone), Format.time(session.asleepAt, zone), Format.time(session.end, zone)) to
            context.getString(R.string.history_health_amounts, dur(session.durationMinutes), dur(session.asleepMinutes), dur(awake))
    } else {
        context.getString(R.string.history_health_times_simple, Format.time(session.start, zone), Format.time(session.end, zone)) to
            context.getString(R.string.history_health_amount_simple, dur(session.durationMinutes))
    }
}

/**
 * Nacht aus Health Connect ohne Phase-Wake-Wecker, kompakt: Datum und Aufwachzeit, Vermerk mit Herz-Symbol (nicht nur
 * Farbe), Zeiten, Dauern, Mini-Hypnogramm mit Summen, Hinweis zur Quelle, Befinden, Notizen und Geräusche je eine
 * Zeile. Antippen öffnet die Detailseite ([onOpen]); dort Befinden wählen und Geräusche anhören. Kein Weckmoment.
 */
@Composable
internal fun HealthNightCard(
    session: HealthSession,
    feeling: Int?,
    notes: List<String>,
    customNotes: List<CustomNote>,
    onToggleNote: (String) -> Unit,
    clips: List<SoundClip>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val (times, amounts) = sessionLines(context, session, zone)
    val stats = remember(session) { StageStats.forSession(session) }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth().opensDetail(MaterialTheme.shapes.medium, stringResource(R.string.night_open), onOpen),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(session.end.atZone(zone)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(Format.time(session.end, zone), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Icon(painterResource(R.drawable.ic_health), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(healthMark(context), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
            }
            Text(times, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Text(amounts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            stats?.let { MiniStages(HypnoModel(session.start, session.end, it.blocks), it) }
            Text(
                healthLiveHint(context, session),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            feeling?.let { CompactLine(stringResource(R.string.feeling_value, feelingName(context, it))) }
            NightNotes(notes, customNotes, onToggleNote, Modifier.padding(top = 4.dp))
            soundSummary(context, clips)?.let { CompactLine(it) }
            DetailsLink()
        }
    }
}

/** Eine leise Zeile in der Karte (Befinden, Geräusche). */
@Composable
internal fun CompactLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

/**
 * Befinden einer Nacht aus einer anderen App: [value] 1–5 (nur bei Phase Wake gespeichert, nie in Health Connect)
 * oder `null`; [onPick] speichert die Wahl.
 */
internal data class NightFeeling(val value: Int? = null, val onPick: (Int) -> Unit = {})

/** Befinden der Nacht als Text («Befinden: Gut»); noch keines: die fünf Knöpfe ([FeelingPicker]). */
@Composable
internal fun SessionFeeling(feeling: NightFeeling) {
    val value = feeling.value
    if (value == null) {
        FeelingPicker(onPick = feeling.onPick, modifier = Modifier.padding(top = 10.dp))
    } else {
        val context = LocalContext.current
        Text(
            stringResource(R.string.feeling_value, feelingName(context, value)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Leerer Verlauf: Grundtext; ohne Freigabe dazu der Hinweis auf die Gesundheitsdaten und der Knopf
 * «Mit Gesundheitsdaten abgleichen»; mit Freigabe, aber ohne Daten, wie Samsung Health die Daten freigibt.
 *
 * @param canConnect Health Connect ist nutzbar, die Freigabe fehlt aber noch.
 */
@Composable
internal fun HistoryEmptyCard(healthConnected: Boolean, canConnect: Boolean, onConnectHealth: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.titleMedium)
            EmptyText(stringResource(R.string.history_empty_text))
            when {
                healthConnected -> EmptyText(stringResource(R.string.history_empty_no_data))
                canConnect -> {
                    EmptyText(stringResource(R.string.history_empty_connect))
                    OutlinedButton(onClick = onConnectHealth, modifier = Modifier.padding(top = 12.dp)) {
                        Icon(painterResource(R.drawable.ic_health), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.health_connect), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}
