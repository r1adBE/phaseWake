package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.ChartData
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.HistoryItem
import com.phasewake.app.core.Hypnogram
import com.phasewake.app.core.NightStages
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.SoundClip
import com.phasewake.app.core.StageSpan
import com.phasewake.app.core.StageStats
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.components.NightBars
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Eine Nacht für die Detailseite: der Eintrag, die Auswertung der Phasen und das Hypnogramm (beides `null` ohne Phasen),
 * bei eigener Nacht dazu das Schlafprofil für den Abgleich des Weckmoments ([hypnogramTakeaway]).
 */
internal data class NightView(val item: HistoryItem, val stats: NightStages?, val hypno: HypnoModel?, val profile: Hypnogram? = null)

/**
 * Rechnet die Ansicht einer Nacht: eigene Nacht über das Schlafprofil ([StageStats.forNight], Achse samt Weckfenster),
 * Nacht aus der Gesundheits-App über die ganze Sitzung ([StageStats.forSession]).
 */
internal fun nightView(item: HistoryItem, stages: List<StageSpan>): NightView {
    val night = item.night
    if (night != null) {
        val stats = StageStats.forNight(stages, night)
        val h = ChartData.hypnogram(stages, night)
        val model = if (h != null && stats != null) HypnoModel(h.start, h.end, stats.blocks, HypnoWindow(night.windowStart, night.target, night.wokeAt)) else null
        return NightView(item, stats, model, h)
    }
    val session = item.session ?: return NightView(item, null, null)
    val stats = StageStats.forSession(session)
    return NightView(item, stats, stats?.let { HypnoModel(session.start, session.end, it.blocks) })
}

/** Datum der Nacht als Morgen mit Wochentag, z. B. «Dienstag, 28. Oktober». */
internal fun nightTitle(item: HistoryItem, zone: ZoneId = ZoneId.systemDefault()): String {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(item.at.atZone(zone))
}

/** Was die Detailseite zeigt: alle Nächte des Verlaufs (neueste zuerst) und was zu ihnen gehört. */
internal data class NightDetailData(
    val items: List<HistoryItem>,
    /** Phasen aus der Gesundheits-App für die eigenen Nächte ([com.phasewake.app.core.HealthHistory.stages]). */
    val stages: List<StageSpan> = emptyList(),
    val notes: Map<LocalDate, List<String>> = emptyMap(),
    val customNotes: List<CustomNote> = emptyList(),
    /** Befinden zu Nächten aus anderen Apps je Nachtdatum. */
    val healthFeelings: Map<LocalDate, Int> = emptyMap(),
    val soundClips: List<SoundClip> = emptyList(),
)

/** Aktionen der Detailseite. */
internal data class NightDetailActions(
    val onBack: () -> Unit = {},
    val onToggleNote: (LocalDate, String) -> Unit = { _, _ -> },
    val onHealthFeeling: (LocalDate, Int) -> Unit = { _, _ -> },
    val soundFile: (SoundClip) -> File? = { null },
    val onDeleteClip: (SoundClip) -> Unit = {},
    val onDeleteNightClips: (LocalDate) -> Unit = {},
)

/**
 * Detailseite einer Nacht (eigene oder aus einer anderen App, gleich aufgebaut). Seitlich wischen führt zur
 * vorherigen bzw. nächsten Nacht; dieselben Schritte als Knöpfe oben für Bildschirmleser. [startKey]: die
 * angetippte Nacht ([HistoryItem.key]); unbekannt: die neueste.
 */
@Composable
internal fun NightDetailScreen(data: NightDetailData, startKey: String?, actions: NightDetailActions) {
    // Älteste links, neueste rechts: nach rechts wischen zeigt die Nacht davor.
    val pages = remember(data.items) { data.items.reversed() }
    if (pages.isEmpty()) {
        LaunchedEffect(Unit) { actions.onBack() }
        return
    }
    val start = pages.indexOfFirst { it.key == startKey }.takeIf { it >= 0 } ?: pages.lastIndex
    val pager = rememberPagerState(initialPage = start) { pages.size }
    val scope = rememberCoroutineScope()
    val current = pages[pager.currentPage.coerceIn(0, pages.lastIndex)]
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_cancel)) }
            Text(
                nightTitle(current),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.night_prev))
            }
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < pages.lastIndex) {
                Icon(painterResource(R.drawable.ic_chevron_end), contentDescription = stringResource(R.string.night_next))
            }
        }
        HorizontalPager(state = pager, key = { pages[it].key }, verticalAlignment = Alignment.Top, modifier = Modifier.weight(1f)) { page ->
            NightDetailPage(pages[page], data, actions)
        }
    }
}

/** Eine Nacht: Kopf, Hypnogramm, Phasen-Summen, Kennzahlen, dann Befinden, Notizen und Geräusche. */
@Composable
internal fun NightDetailPage(item: HistoryItem, data: NightDetailData, actions: NightDetailActions, modifier: Modifier = Modifier) {
    val view = remember(item, data.stages) { nightView(item, data.stages) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NightHeader(view)
        HypnogramSection(view)
        view.stats?.let {
            StageTotalsCard(it)
            NightTiles(it)
        }
        NightExtras(item, data, actions)
    }
}

/** Kopf: Schlafdauer gross, «Im Bett 23:10 – 06:50», bei eigener Nacht Weckmoment und Grund, sonst der Vermerk zur Quelle. */
@Composable
private fun NightHeader(view: NightView) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val scheme = MaterialTheme.colorScheme
    val session = view.item.session
    val asleep = view.stats?.asleepMinutes ?: session?.asleepMinutes
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        asleep?.let {
            Text(Format.duration(context, Duration.ofMinutes(it)), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.night_asleep), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        }
        val bed = view.stats?.let { it.start to it.end } ?: session?.let { it.start to it.end }
        bed?.let { (from, to) ->
            Text(stringResource(R.string.night_in_bed, Format.time(from, zone), Format.time(to, zone)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        val night = view.item.night
        if (night != null) {
            Text(
                stringResource(R.string.night_woke, Format.time(night.wokeAt, zone)),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(nightSummary(context, night), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            view.profile?.let { h ->
                hypnogramTakeaway(context, h)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (h.stageAtWake == SleepStage.DEEP) scheme.error else scheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } else if (session != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Icon(painterResource(R.drawable.ic_health), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(healthMark(context), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
            }
            Text(healthLiveHint(context, session), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Grosses Hypnogramm mit Legende; eigene Nacht ohne Phasen: Bewegung im Weckfenster und der Hinweis dazu. */
@Composable
private fun HypnogramSection(view: NightView) {
    val model = view.hypno
    val night = view.item.night
    if (model == null) {
        if (night == null) return
        ChartCard(stringResource(R.string.chart_window), stringResource(R.string.night_no_stages), Modifier.fillMaxWidth()) {
            NightBars(night.epochs, Modifier.fillMaxWidth().height(56.dp), totalEpochs = (Duration.between(night.windowStart, night.target).toMinutes() * 2).toInt())
        }
        return
    }
    // Der Satz zur Einordnung steht einmal, bei den Phasen-Summen.
    ChartCard(stringResource(R.string.chart_stages_title), null, Modifier.fillMaxWidth()) {
        StageHypnogram(model, height = HYPNO_HEIGHT)
        HypnoLegend(model)
        Text(
            stringResource(R.string.chart_touch_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Höhe des grossen Hypnogramms samt Platz für die Blase und die Zeitachse. */
private val HYPNO_HEIGHT = 240.dp

/** Einordnung der Nacht in einem Satz ([StageStats]); zu wenig Daten: keiner. */
internal fun verdictText(context: Context, stats: NightStages): String? = stats.verdict?.let { context.getString(verdictRes(it)) }
