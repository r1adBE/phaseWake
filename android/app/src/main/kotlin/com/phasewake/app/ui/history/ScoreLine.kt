package com.phasewake.app.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.SleepScoreResult
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.theme.PhaseWakeTheme

// Schlafwert als Kurzzeile in der Karte des Verlaufs.

/**
 * Kurzzeile in der Karte des Verlaufs: kleiner Ring mit dem Wert, daneben «Schlafwert» und die Bewertung. Vorläufiger
 * Wert (ohne Schlafphasen): Ring gestrichelt und hohl statt voll.
 */
@Composable
internal fun ScoreLine(result: SleepScoreResult, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val brand = PhaseWakeTheme.brand
    val scheme = MaterialTheme.colorScheme
    val description = scoreDescription(context, result)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(top = 10.dp).clearAndSetSemantics { contentDescription = description },
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 4.dp.toPx()
                val inset = stroke / 2
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(brand.chartTrack, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                val sweep = 360f * result.score / 100f
                if (result.provisional) {
                    // Gestrichelt in halber Stärke: «noch nicht ganz gemessen», ohne neue Farbe.
                    val dash = 3.dp.toPx()
                    val thin = Stroke(
                        stroke / 2, cap = StrokeCap.Butt,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.8f)),
                    )
                    drawArc(brand.chartActive, -90f, sweep, false, Offset(inset, inset), arc, style = thin)
                } else {
                    drawArc(
                        brand.chartActive, -90f, sweep, false, Offset(inset, inset), arc,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Text(Format.number(result.score), style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(
                stringResource(R.string.score_title), style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Text(bandName(context, result.band), style = MaterialTheme.typography.titleSmall)
        }
    }
}
