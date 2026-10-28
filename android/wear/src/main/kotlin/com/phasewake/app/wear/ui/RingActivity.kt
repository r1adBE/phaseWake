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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Weckbildschirm: erscheint über dem Sperrbildschirm, bis gestoppt oder geschlummert wird. */
class RingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ringState = wearApp.ringState
        setContent {
            PhaseWakeTheme {
                val ui by ringState.ui.collectAsStateWithLifecycle()
                LaunchedEffect(ui) { if (ui is RingUi.Idle) finish() }
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

@Composable
internal fun RingScreen(ui: RingUi, onDismiss: () -> Unit, onSnooze: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val holdLabel = stringResource(R.string.ring_hold_to_stop)

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
            modifier = Modifier.padding(horizontal = 18.dp),
        ) {
            PulsingDot(
                Modifier
                    .size(76.dp)
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
            Spacer(Modifier.height(6.dp))
            when (ui) {
                is RingUi.Ringing -> Ringing(ui, onSnooze)
                is RingUi.Snoozed -> Snoozed(ui, onDismiss)
                RingUi.Idle -> Unit
            }
        }
    }
}

@Composable
private fun Ringing(ui: RingUi.Ringing, onSnooze: () -> Unit) {
    Text(
        ui.label.ifBlank { stringResource(R.string.ring_greeting) },
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
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
    )
    Text(
        stringResource(R.string.ring_hold_to_stop),
        style = MaterialTheme.typography.labelSmall,
        color = Brand.Muted,
    )
    if (ui.snoozesLeft > 0) {
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onSnooze,
            colors = ButtonDefaults.buttonColors(containerColor = Brand.Deep, contentColor = Brand.Moon),
            label = { Text(stringResource(R.string.ring_snooze, Snooze.MINUTES, ui.snoozesLeft)) },
        )
    }
}

@Composable
private fun Snoozed(ui: RingUi.Snoozed, onDismiss: () -> Unit) {
    val until = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(ui.until.atZone(ZoneId.systemDefault()))
    Text(
        stringResource(R.string.ring_snoozed_until, until),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onDismiss,
        colors = ButtonDefaults.buttonColors(containerColor = Brand.Deep, contentColor = Brand.Moon),
        label = { Text(stringResource(R.string.ring_stop_now)) },
    )
}
