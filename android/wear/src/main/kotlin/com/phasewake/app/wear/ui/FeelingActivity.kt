package com.phasewake.app.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.phasewake.app.core.FeelingAsk
import com.phasewake.app.core.NightRecord
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.theme.PAGE_GLOW
import com.phasewake.app.wear.ui.theme.PhaseWakeTheme
import com.phasewake.app.wear.ui.theme.nightGlow
import com.phasewake.app.wear.wake.FeelingPrompt
import com.phasewake.app.wear.wearApp
import java.text.NumberFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * «Wie fühlst du dich?» direkt nach dem Stopp auf der Uhr ([FeelingAsk], Entscheid 23.11.2026): eigener Bildschirm,
 * den der Weckbildschirm nach dem Ende des Klingelns öffnet; der Stopp ist dann schon ganz abgeschlossen. Still (kein
 * Ton, keine Vibration, hält das Display nicht wach). Ohne Eingabe schliesst er sich [FeelingAsk.SECONDS] nach dem
 * Stopp; «Später» und Zurückwischen schliessen ihn sofort. Die Wahl speichert [FeelingPrompt.choose] im App-Bereich,
 * damit sie auch nach dem Schliessen ans Handy geht.
 */
class FeelingActivity : ComponentActivity() {
    private var ask: FeelingPrompt.Ask? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keine offene Frage mehr (abgelaufen, schon beantwortet, Prozess neu): nichts zeigen.
        val open = FeelingPrompt.current(System.currentTimeMillis())
        if (open == null) {
            finish()
            return
        }
        ask = open
        setContent { PhaseWakeTheme { FeelingRoot(open) } }
    }

    @Composable
    private fun FeelingRoot(open: FeelingPrompt.Ask) {
        LaunchedEffect(open) {
            delay((open.askedAtMs + FeelingAsk.MILLIS - System.currentTimeMillis()).coerceAtLeast(0L))
            FeelingPrompt.close(open)
            finish()
        }
        // Wie die übrigen Bildschirme der Uhr mit der Uhrzeit oben.
        AppScaffold {
            FeelingScreen(
                onPick = { value ->
                    val app = wearApp
                    app.appScope.launch { FeelingPrompt.choose(app, open, value) }
                    finish()
                },
                onLater = {
                    FeelingPrompt.close(open)
                    finish()
                },
            )
        }
    }

    override fun onDestroy() {
        // Zurückgewischt oder geschlossen: nicht noch einmal fragen (beim Drehen o. ä. bleibt die Frage).
        if (isFinishing) ask?.let(FeelingPrompt::close)
        super.onDestroy()
    }
}

/** Textschlüssel des Befindens 1–5, wie am Handy («Erschöpft» … «Erholt»). */
internal fun feelingRes(value: Int): Int = when (value) {
    1 -> R.string.feeling_1
    2 -> R.string.feeling_2
    3 -> R.string.feeling_3
    4 -> R.string.feeling_4
    else -> R.string.feeling_5
}

/**
 * Abfrage des Befindens: Frage, fünf grosse Knöpfe 1–5 untereinander (auf runden Uhren passen fünf Ziele à 48 dp nicht
 * nebeneinander), die Enden beschriftet wie am Handy («Erschöpft», «Erholt»), darunter «Später». Jeder Knopf mindestens
 * 52 dp hoch (Wear-Knopf) und für Bildschirmleser «Befinden 3 von 5: Okay».
 */
@Composable
internal fun FeelingScreen(onPick: (Int) -> Unit, onLater: () -> Unit) {
    val res = LocalResources.current
    val digits = NumberFormat.getIntegerInstance()
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = padding,
            modifier = Modifier.fillMaxSize().nightGlow(strength = PAGE_GLOW),
        ) {
            item {
                ListHeader { Text(stringResource(R.string.feeling_question), textAlign = TextAlign.Center) }
            }
            for (value in NightRecord.FEELING_MIN..NightRecord.FEELING_MAX) item {
                val label = res.getString(R.string.feeling_rate, value, res.getString(feelingRes(value)))
                // Beschriftung nur an den Enden, wie am Handy.
                val end = value == NightRecord.FEELING_MIN || value == NightRecord.FEELING_MAX
                Button(
                    onClick = { onPick(value) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    // Ziffern der Sprache (Arabisch: ٠–٩), wie am Handy.
                    label = { Text(digits.format(value)) },
                    secondaryLabel = if (end) {
                        { Text(stringResource(feelingRes(value))) }
                    } else {
                        null
                    },
                )
            }
            item {
                OutlinedButton(
                    onClick = onLater,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.feeling_later)) },
                )
            }
        }
    }
}
