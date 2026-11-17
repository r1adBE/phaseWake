package com.phasewake.app.ui.home

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.DeepLink
import com.phasewake.app.core.Finding
import com.phasewake.app.core.FindingKind
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.StageStats
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.Duration

/**
 * Text einer Auffälligkeit: was auffiel, bei wenig Tiefschlaf der belegte Zusammenhang, und
 * ein Tipp. Gleich auf der Startseite ([FindingCard]) und in den Trends des Verlaufs.
 */
fun findingText(context: Context, finding: Finding): String {
    fun minutes(value: Int) = Format.duration(context, Duration.ofMinutes(value.toLong()))
    return when (finding.kind) {
        FindingKind.LONG_LATENCY -> context.getString(R.string.finding_latency, minutes(finding.value))
        FindingKind.SHORT_NIGHTS -> context.getString(R.string.finding_short_nights, finding.value)
        FindingKind.LOW_DEEP -> {
            val typical = StageStats.TYPICAL.getValue(SleepStage.DEEP)
            listOfNotNull(
                context.getString(R.string.finding_low_deep, finding.value, typical.low, typical.high),
                when (finding.link) {
                    DeepLink.SHORT_NIGHTS -> context.getString(R.string.finding_deep_short, finding.withPercent, finding.withoutPercent)
                    DeepLink.LATE_BEDTIME -> context.getString(R.string.finding_deep_late, finding.withPercent, finding.withoutPercent)
                    null -> null
                },
                context.getString(R.string.finding_tip_deep),
            ).joinToString(" ")
        }
        FindingKind.MUCH_AWAKE -> context.getString(R.string.finding_awake, finding.value)
        FindingKind.SOCIAL_JETLAG -> context.getString(R.string.finding_jetlag, minutes(finding.value))
        FindingKind.BEDTIME_VARIES -> context.getString(R.string.finding_bedtime, minutes(finding.value))
    }
}

/**
 * Höchstens eine Auffälligkeit auf der Startseite, die wichtigste ([com.phasewake.app.core.Findings.home]);
 * «Ausblenden» verbirgt sie 7 Tage. Darunter leise «Keine medizinische Bewertung.».
 */
@Composable
internal fun FindingCard(finding: Finding, onHide: () -> Unit) {
    val context = LocalContext.current
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardColor()),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp)) {
            Text(stringResource(R.string.finding_title), style = MaterialTheme.typography.titleMedium)
            Text(findingText(context, finding), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            Text(
                stringResource(R.string.stages_not_medical),
                style = MaterialTheme.typography.bodySmall,
                color = PhaseWakeTheme.brand.mutedSoft,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onHide, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.finding_hide)) }
        }
    }
}
