package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.Hypnogram
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.NightStages
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.WakeReason
import java.time.ZoneId

// --- 4. Schlafphasen der Nacht ---

/** Wie der Abgleich in der Nachtkarte: «Gesundheitsdaten: leichter Schlaf ✓». */
fun hypnogramTakeaway(context: Context, h: Hypnogram): String? = when (h.stageAtWake) {
    SleepStage.LIGHT -> context.getString(R.string.verdict_good, context.getString(R.string.stage_light))
    SleepStage.REM -> context.getString(R.string.verdict_good, context.getString(R.string.stage_rem))
    SleepStage.AWAKE -> context.getString(R.string.verdict_good, context.getString(R.string.stage_awake))
    SleepStage.DEEP -> context.getString(R.string.verdict_deep)
    SleepStage.UNKNOWN, null -> null
}

/** Platz über den Zeilen für die Blase in der Karte (kleiner als auf der Detailseite). */
private val CARD_BUBBLE = 28.dp

/**
 * Schlafprofil der jüngsten Uhr-Nacht mit Phasen: gestuftes Hypnogramm in den Phasenfarben, Weckfenster, Zielzeit und
 * Weckpunkt; Antippen oder Ziehen zeigt die Phase zur Uhrzeit, Antippen der Blase öffnet die Nacht ([onOpen]).
 */
@Composable
internal fun HypnogramCard(h: Hypnogram, stats: NightStages, night: NightRecord, modifier: Modifier, onOpen: (() -> Unit)? = null) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val date = morning(SleepNotes.nightDate(night, zone))
    val model = HypnoModel(h.start, h.end, stats.blocks, HypnoWindow(night.windowStart, night.target, night.wokeAt, night.reason == WakeReason.EXACT))
    val trailing = @Composable { Text(date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    ChartCard(stringResource(R.string.chart_stages_title), hypnogramTakeaway(context, h), modifier, trailing = trailing) {
        StageHypnogram(model, height = CHART_HEIGHT + CARD_BUBBLE, bubbleSpace = CARD_BUBBLE, onOpen = onOpen)
        HypnoLegend(model)
        Text(
            stageTotalsLine(context, stats),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
