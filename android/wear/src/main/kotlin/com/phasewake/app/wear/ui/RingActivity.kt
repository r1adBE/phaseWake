package com.phasewake.app.wear.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.phasewake.app.core.Snooze
import com.phasewake.app.core.WakeReason
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.components.PulsingDot
import com.phasewake.app.wear.ui.theme.Brand
import com.phasewake.app.wear.ui.theme.PhaseWakeTheme
import com.phasewake.app.wear.ui.theme.nightGlow
import com.phasewake.app.wear.wake.RingUi
import com.phasewake.app.wear.wake.WakeService
import com.phasewake.app.wear.wearApp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** Weckbildschirm: erscheint über dem Sperrbildschirm, solange es klingelt; beim Schlummern schliesst er sich, ausser er wurde über die Schlummer-Mitteilung geöffnet. */
class RingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ringState = wearApp.ringState
        setContent {
            PhaseWakeTheme {
                val ui by ringState.ui.collectAsStateWithLifecycle()
                var rang by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(ui) {
                    when (ui) {
                        RingUi.Idle -> finish()
                        is RingUi.Ringing -> {
                            rang = true
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        // Beim Schlummern geht das Display aus. Wurde hier geschlummert, schliesst sich der
                        // Bildschirm; beim nächsten Klingeln öffnet ihn die Weck-Mitteilung wieder. Über die
                        // Schlummer-Mitteilung geöffnet, bleibt er (Beenden durch Halten), ohne das Display wach zu halten.
                        is RingUi.Snoozed -> {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            if (rang) finish()
                        }
                    }
                }
                RingScreen(
                    ui = ui,
                    onDismiss = { WakeService.command(this, WakeService.ACTION_DISMISS) },
                    onSnooze = { WakeService.command(this, WakeService.ACTION_SNOOZE) },
                )
            }
        }
    }
}

private const val HOLD_MILLIS = 1200

/** Darunter gilt das Display als klein (kleine runde Uhren haben etwa 192 dp). */
private const val COMPACT_HEIGHT_DP = 210

@Composable
internal fun RingScreen(ui: RingUi, onDismiss: () -> Unit, onSnooze: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val holdLabel = stringResource(R.string.ring_hold_to_stop)
    // Kleine runde Displays (um 192 dp): kleinerer Punkt, knappere Abstände, damit unten nichts abgeschnitten wird.
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_DP

    Box(Modifier.fillMaxSize().nightGlow(), contentAlignment = Alignment.Center) {
        // Fortschritt des Haltens als Ring am Rand.
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2 + 1.dp.toPx()
            drawArc(
                Brand.Wave, -90f, 360f * progress.value, false,
                Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = if (compact) 22.dp else 18.dp),
        ) {
            PulsingDot(
                Modifier
                    .size(if (compact) 48.dp else 76.dp)
                    .semantics {
                        contentDescription = holdLabel
                        onClick(label = holdLabel) { onDismiss(); true }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val hold = scope.launch {
                                progress.animateTo(1f, tween(HOLD_MILLIS, easing = LinearEasing))
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDismiss()
                            }
                            tryAwaitRelease()
                            if (progress.value < 1f) {
                                hold.cancel()
                                scope.launch { progress.animateTo(0f, tween(250)) }
                            }
                        })
                    },
            )
            Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
            when (ui) {
                is RingUi.Ringing -> Ringing(ui, onSnooze, compact)
                is RingUi.Snoozed -> Snoozed(ui)
                RingUi.Idle -> Unit
            }
        }
    }
}

@Composable
private fun Ringing(ui: RingUi.Ringing, onSnooze: () -> Unit, compact: Boolean = false) {
    // Jede Zeile einzeilig: Lange Namen oder Übersetzungen dürfen den Schlummer-Knopf nicht nach unten schieben.
    Text(
        ui.label.ifBlank { stringResource(R.string.ring_greeting) },
        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        stringResource(
            when (ui.reason) {
                WakeReason.LIGHT_SLEEP -> R.string.ring_reason_light
                WakeReason.AWAKE -> R.string.ring_reason_awake
                WakeReason.DEADLINE -> R.string.ring_reason_deadline
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = Brand.Mist,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        stringResource(R.string.ring_hold_to_stop),
        style = MaterialTheme.typography.labelSmall,
        color = Brand.Muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (ui.snoozesLeft > 0) {
        Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
        Button(
            onClick = onSnooze,
            modifier = if (compact) Modifier.height(36.dp) else Modifier,
            colors = ButtonDefaults.buttonColors(containerColor = Brand.Deep, contentColor = Brand.Moon),
            contentPadding = if (compact) PaddingValues(horizontal = 12.dp, vertical = 2.dp) else ButtonDefaults.ContentPadding,
            label = {
                Text(
                    LocalContext.current.resources.getQuantityString(R.plurals.ring_snooze, ui.snoozesLeft, Snooze.MINUTES, ui.snoozesLeft),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
}

@Composable
private fun Snoozed(ui: RingUi.Snoozed) {
    val until = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(ui.until.atZone(ZoneId.systemDefault()))
    Text(
        stringResource(R.string.ring_snoozed_until, until),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    // Beenden auch hier nur durch Halten (Punkt oben): ein Tipp im Halbschlaf beendet nicht die Nacht.
    Text(
        stringResource(R.string.ring_hold_to_stop),
        style = MaterialTheme.typography.labelSmall,
        color = Brand.Muted,
    )
}
