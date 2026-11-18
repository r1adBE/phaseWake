package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.HistoryGroup
import com.phasewake.app.core.HistoryGroupSummary
import com.phasewake.app.core.HistoryGroups
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.components.skyCardColors
import com.phasewake.app.ui.components.skyCardEdge
import com.phasewake.app.ui.components.skyCardLift
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Liste im Verlauf nach Woche und Jahr: die neusten Nächte einzeln, ältere zusammen in einer Karte mit
// aufklappbaren Zeilen; aufgeklappt folgen eingerückt kompakte Zeilen der Nächte bzw. die Wochen des Jahres.

/** Schlüssel einer Wochenzeile; [year] = Jahreszeile, in der sie steht (`null`: in den Wochen des letzten Jahres). */
internal fun weekKey(start: LocalDate, year: Int? = null): String =
    (year?.let { "${yearKey(it)}/" } ?: "") + "week@$start"

/** Schlüssel einer Jahreszeile. */
internal fun yearKey(year: Int): String = "year@$year"

/** Aufgeklappte Zeilen ([open], Schlüssel [weekKey] und [yearKey]) und Umschalten einer Zeile ([onToggle]). */
internal data class GroupFolding(val open: Set<String>, val onToggle: (String) -> Unit)

/** Einrücken je Stufe (Woche im Jahr, Nacht in der Woche). */
private val STEP = 16.dp

/**
 * Die Liste aus [groups]: einzelne Einträge über [entry]; alle Wochen und Jahre zusammen in einer Karte ([OlderCard]).
 * [today] = das Jahr, das eine Wochenzeile nicht nennen muss.
 */
internal fun LazyListScope.groupedItems(
    groups: List<HistoryGroup<HistoryEntry>>,
    folding: GroupFolding,
    rows: CompactRows,
    today: LocalDate,
    entry: @Composable LazyItemScope.(HistoryEntry) -> Unit,
) {
    val older = groups.filter { it !is HistoryGroup.Single }
    groups.filterIsInstance<HistoryGroup.Single<HistoryEntry>>().forEach { group ->
        item(key = group.entry.key) { entry(group.entry) }
    }
    if (older.isNotEmpty()) {
        item(key = "older") { OlderCard(older, folding, rows, today, Modifier.animateItem()) }
    }
}

/**
 * Hinweis zur Quelle der Nächte aus Health Connect, einmal unter der Liste statt auf jeder Karte («Aus Samsung
 * Health. Änderungen und Löschungen dort übernimmt Phase Wake automatisch.»); aus mehreren Quellen einmal die
 * allgemeine Fassung («Aus deinen Gesundheitsdaten. …»).
 */
internal fun LazyListScope.healthHintItem(entries: List<HistoryEntry>) {
    val sessions = entries.mapNotNull { (it as? HistoryEntry.Health)?.item?.session }
    if (sessions.isEmpty()) return
    item(key = "health-hint") {
        val context = LocalContext.current
        val general = stringResource(R.string.history_health_live_unknown)
        val hint = remember(sessions, general) {
            sessions.map { healthLiveHint(context, it) }.distinct().singleOrNull() ?: general
        }
        Text(
            hint, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        )
    }
}

/** Alle Wochen und Jahre in einer Karte, mit feinen Linien zwischen den Zeilen. */
@Composable
private fun OlderCard(
    groups: List<HistoryGroup<HistoryEntry>>,
    folding: GroupFolding,
    rows: CompactRows,
    today: LocalDate,
    modifier: Modifier,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = skyCardColors(),
        border = skyCardEdge(),
        elevation = skyCardLift(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            groups.forEachIndexed { index, group ->
                if (index > 0) RowLine(0.dp)
                when (group) {
                    is HistoryGroup.Week -> WeekBlock(group, null, folding, rows, today)
                    is HistoryGroup.Year -> YearBlock(group, folding, rows, today)
                    is HistoryGroup.Single -> Unit
                }
            }
        }
    }
}

/** Jahreszeile und, aufgeklappt, ihre Wochen eine Stufe eingerückt. */
@Composable
private fun YearBlock(
    year: HistoryGroup.Year<HistoryEntry>,
    folding: GroupFolding,
    rows: CompactRows,
    today: LocalDate,
) {
    val key = yearKey(year.year)
    val open = key in folding.open
    GroupRow(Format.number(year.year), year.entries, rows.state, open, 0.dp) { folding.onToggle(key) }
    AnimatedVisibility(open) {
        Column {
            year.weeks.forEach { week ->
                RowLine(STEP)
                WeekBlock(week, year.year, folding, rows, today)
            }
        }
    }
}

/** Wochenzeile und, aufgeklappt, ihre Einträge als kompakte Zeilen eine Stufe weiter eingerückt. */
@Composable
private fun WeekBlock(
    week: HistoryGroup.Week<HistoryEntry>,
    year: Int?,
    folding: GroupFolding,
    rows: CompactRows,
    today: LocalDate,
) {
    val key = weekKey(week.start, year)
    val open = key in folding.open
    val indent = if (year != null) STEP else 0.dp
    // In einer Jahreszeile nennt die Woche kein Jahr; sonst nur, wenn es nicht das laufende ist.
    val withYear = year == null && (week.start.year != today.year || week.end.year != today.year)
    GroupRow(weekRange(week, withYear), week.entries, rows.state, open, indent) { folding.onToggle(key) }
    AnimatedVisibility(open) {
        Column {
            week.entries.forEach { entry ->
                RowLine(indent + STEP)
                CompactEntry(entry, rows, indent + STEP)
            }
        }
    }
}

/** Feine Linie zwischen zwei Zeilen der Karte, ab [indent] (bündig mit dem Text der Zeile darunter). */
@Composable
private fun RowLine(indent: Dp) {
    HorizontalDivider(
        thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        modifier = Modifier.padding(start = indent + 16.dp),
    )
}

/** «9. Nov. – 15. Nov.» bzw. mit Jahr «29. Dez. 2025 – 4. Jan. 2026» (Datum des Morgens, Sprache des Geräts). */
private fun weekRange(week: HistoryGroup.Week<*>, withYear: Boolean): String {
    val locale = Locale.getDefault()
    val skeleton = if (withYear) "dMMMy" else "dMMM"
    val format = Format.digits(
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale),
        locale,
    )
    return format.format(week.start) + " – " + format.format(week.end)
}

/** Kennzahlen von [entries]: Nächte, Ø Schlaf aus Health Connect, Ø Befinden (eigenes und zu Fremd-Nächten). */
internal fun groupSummary(entries: List<HistoryEntry>, state: HistoryState): HistoryGroupSummary =
    HistoryGroups.summary(entries, { it.nightDate }, state.asleep) { entry ->
        when (entry) {
            is HistoryEntry.Night -> entry.item.night?.feeling
            is HistoryEntry.Health -> state.healthFeelings[entry.item.date]
            is HistoryEntry.Sounds, is HistoryEntry.Recording -> null
        }
    }

/**
 * «5 Nächte · Ø 7 Std. 5 Min Schlaf · Befinden: 3,8/5»; Schlaf und Befinden nur mit Daten. Ein «Ø» für die Zeile,
 * das Befinden als Wert von 5 mit dem Dezimalzeichen der Sprache ([Format.decimal]).
 */
internal fun groupSummaryText(context: Context, summary: HistoryGroupSummary): String = listOfNotNull(
    summary.nights.takeIf { it > 0 }?.let {
        context.resources.getQuantityString(R.plurals.history_group_nights, it, it)
    },
    summary.asleepMinutes?.let {
        context.getString(R.string.trends_asleep, Format.duration(context, Duration.ofMinutes(it)))
    },
    summary.feeling?.let {
        context.getString(R.string.feeling_value, Format.decimal(context.resources, it) + "/" + Format.number(5))
    },
).joinToString(" · ")

/**
 * Aufklappbare Zeile einer Woche oder eines Jahres, um [indent] eingerückt: Titel, darunter der Schnitt der [entries];
 * der Pfeil dreht sich beim Aufklappen. Für Bildschirmleser ein Knopf mit Zustand («Aufgeklappt» / «Zugeklappt»).
 */
@Composable
private fun GroupRow(
    title: String,
    entries: List<HistoryEntry>,
    state: HistoryState,
    open: Boolean,
    indent: Dp,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val summary = remember(entries, state.asleep, state.healthFeelings) {
        groupSummaryText(context, groupSummary(entries, state))
    }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "turn")
    val stateText = stringResource(if (open) R.string.history_group_expanded else R.string.history_group_collapsed)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = stateText }
            .padding(start = 16.dp + indent, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (summary.isNotEmpty()) {
                Text(
                    summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            painterResource(R.drawable.ic_expand), contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.rotate(turn),
        )
    }
}
