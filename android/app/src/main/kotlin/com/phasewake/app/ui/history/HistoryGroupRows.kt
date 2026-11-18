package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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

// Liste im Verlauf nach Woche und Jahr: die neusten Nächte einzeln, ältere in aufklappbaren Zeilen.

/** Schlüssel einer Wochenzeile; [year] = Jahreszeile, in der sie steht (`null`: in den Wochen des letzten Jahres). */
internal fun weekKey(start: LocalDate, year: Int? = null): String =
    (year?.let { "${yearKey(it)}/" } ?: "") + "week@$start"

/** Schlüssel einer Jahreszeile. */
internal fun yearKey(year: Int): String = "year@$year"

/** Aufgeklappte Zeilen ([open], Schlüssel [weekKey] und [yearKey]) und Umschalten einer Zeile ([onToggle]). */
internal data class GroupFolding(val open: Set<String>, val onToggle: (String) -> Unit)

/**
 * Die Liste aus [groups]: einzelne Einträge über [entry], Wochen und Jahre als aufklappbare Zeilen; aufgeklappt
 * folgen darunter die Einträge der Woche bzw. die Wochen des Jahres. [state] liefert Schlaf und Befinden für den
 * Schnitt, [today] das Jahr, das eine Wochenzeile nicht nennen muss.
 */
internal fun LazyListScope.groupedItems(
    groups: List<HistoryGroup<HistoryEntry>>,
    folding: GroupFolding,
    state: HistoryState,
    today: LocalDate,
    entry: @Composable LazyItemScope.(HistoryEntry) -> Unit,
) {
    for (group in groups) {
        when (group) {
            is HistoryGroup.Single -> item(key = group.entry.key) { entry(group.entry) }
            is HistoryGroup.Week -> weekItems(group, null, folding, state, today, entry)
            is HistoryGroup.Year -> {
                val key = yearKey(group.year)
                val open = key in folding.open
                item(key = key) {
                    GroupRow(
                        Format.number(group.year), group.entries, state, open, { folding.onToggle(key) },
                        Modifier.animateItem(),
                    )
                }
                if (open) group.weeks.forEach { weekItems(it, group.year, folding, state, today, entry) }
            }
        }
    }
}

/** Wochenzeile und, aufgeklappt, ihre Einträge; [year] = umgebende Jahreszeile. */
private fun LazyListScope.weekItems(
    week: HistoryGroup.Week<HistoryEntry>,
    year: Int?,
    folding: GroupFolding,
    state: HistoryState,
    today: LocalDate,
    entry: @Composable LazyItemScope.(HistoryEntry) -> Unit,
) {
    val key = weekKey(week.start, year)
    val open = key in folding.open
    item(key = key) {
        // In einer Jahreszeile nennt die Woche kein Jahr; sonst nur, wenn es nicht das laufende ist.
        val withYear = year == null && (week.start.year != today.year || week.end.year != today.year)
        GroupRow(
            weekRange(week, withYear), week.entries, state, open, { folding.onToggle(key) },
            Modifier.animateItem().padding(start = if (year != null) 16.dp else 0.dp),
        )
    }
    if (open) items(week.entries, key = { it.key }) { entry(it) }
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

/** «5 Nächte · Ø 7 Std. 5 Min Schlaf · Ø Befinden 3,8 von 5»; Schlaf und Befinden nur mit Daten. */
internal fun groupSummaryText(context: Context, summary: HistoryGroupSummary): String = listOfNotNull(
    summary.nights.takeIf { it > 0 }?.let {
        context.resources.getQuantityString(R.plurals.history_group_nights, it, it)
    },
    summary.asleepMinutes?.let {
        context.getString(R.string.trends_asleep, Format.duration(context, Duration.ofMinutes(it)))
    },
    summary.feeling?.let { context.getString(R.string.trends_feeling, String.format(Locale.getDefault(), "%.1f", it)) },
).joinToString(" · ")

/**
 * Aufklappbare Zeile einer Woche oder eines Jahres: [title], darunter der Schnitt der [entries]; der Pfeil dreht
 * sich beim Aufklappen. Für Bildschirmleser ein Knopf mit Zustand («Aufgeklappt» / «Zugeklappt»).
 */
@Composable
private fun GroupRow(
    title: String,
    entries: List<HistoryEntry>,
    state: HistoryState,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val summary = remember(entries, state.asleep, state.healthFeelings) {
        groupSummaryText(context, groupSummary(entries, state))
    }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "turn")
    val stateText = stringResource(if (open) R.string.history_group_expanded else R.string.history_group_collapsed)
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = skyCardColors(),
        border = skyCardEdge(),
        elevation = skyCardLift(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = stateText }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
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
}
