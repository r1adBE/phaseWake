package com.phasewake.app.ring

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phasewake.app.R
import com.phasewake.app.core.Snooze
import com.phasewake.app.core.Sunrise
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.components.AnimatedNight
import com.phasewake.app.ui.components.Night as NightColors
import com.phasewake.app.ui.components.PulsingDot
import com.phasewake.app.ui.components.SunriseBackground
import com.phasewake.app.ui.components.SunriseSky
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ui.theme.ThemeMode
import java.time.Instant
import kotlinx.coroutines.delay

/** Weckbildschirm des Handys: Nachtblau oder – wenn gewählt – Sonnenaufgang, unabhängig vom gewählten Design. */
class PhoneRingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            PhaseWakeTheme(ThemeMode.DARK) {
                val state by PhoneRingService.ringState.collectAsStateWithLifecycle()
                // Bildschirm bleibt auch beim Schlummern bewusst an: Das Aufstehen soll nicht verzögert werden.
                LaunchedEffect(state) { if (state is PhoneRingService.State.Idle) finish() }
                val sunrise = when (val s = state) {
                    is PhoneRingService.State.Ringing -> s.sunrise
                    is PhoneRingService.State.Snoozed -> s.sunrise
                    PhoneRingService.State.Idle -> Sunrise.OFF
                }
                val light = remember { Animatable(0f) }
                LaunchedEffect(sunrise, (state as? PhoneRingService.State.Ringing)?.lightFrom) {
                    val ringing = state as? PhoneRingService.State.Ringing ?: return@LaunchedEffect
                    if (sunrise == Sunrise.OFF) return@LaunchedEffect
                    // Nur heller werden, nie zurück (z. B. nach dem Schlummern).
                    if (ringing.lightFrom > light.value) light.snapTo(ringing.lightFrom)
                    // Lief schon ein Licht ab Weckfenster: in einer Minute voll. Sonst zwei Minuten von dunkel.
                    val millis = if (ringing.lightFrom > 0f) Sunrise.CATCH_UP_SECONDS * 1000 else (Sunrise.RING_SECONDS * 1000 * (1f - light.value)).toInt()
                    light.animateTo(1f, tween(millis.coerceAtLeast(1), easing = LinearEasing))
                }
                LaunchedEffect(sunrise) {
                    snapshotFlow { light.value }.collect { value ->
                        window.attributes = window.attributes.apply {
                            screenBrightness = if (sunrise == Sunrise.OFF) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else Sunrise.brightness(value)
                        }
                    }
                }
                RingScreen(
                    state = state,
                    light = if (sunrise == Sunrise.OFF) null else light.value,
                    onStop = { PhoneRingService.command(this, PhoneRingService.ACTION_STOP) },
                    onSnooze = { PhoneRingService.command(this, PhoneRingService.ACTION_SNOOZE) },
                )
            }
        }
    }
}

private val Night = NightColors.Bottom

@Composable
internal fun RingScreen(
    state: PhoneRingService.State,
    onStop: () -> Unit,
    onSnooze: () -> Unit,
    light: Float? = null,
    clock: () -> Instant = Instant::now,
) {
    var now by remember { mutableStateOf(clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = clock()
        }
    }
    // Mit Sonnenaufgang: Licht statt Nachthimmel, Farben passen sich der Helligkeit an.
    val sky = light?.let { SunriseSky.colors(it) }
    val content = sky?.content ?: Color.White
    val muted = sky?.contentMuted ?: NightColors.Muted
    val background: @Composable (@Composable () -> Unit) -> Unit = { inner ->
        if (light != null) SunriseBackground(light, Modifier.fillMaxSize(), inner) else AnimatedNight(Modifier.fillMaxSize()) { inner() }
    }
    background {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PulsingDot(Modifier.size(180.dp), color = if (light != null) content.copy(alpha = 0.6f) else NightColors.Wave, dot = content)
        Spacer(Modifier.height(24.dp))
        Text(Format.time(now), style = MaterialTheme.typography.displayLarge, color = content)
        Text(stringResource(R.string.ring_greeting), style = MaterialTheme.typography.titleLarge, color = muted)
        Spacer(Modifier.height(48.dp))
        when (state) {
            is PhoneRingService.State.Ringing -> {
                HoldToStopButton(
                    label = stringResource(R.string.ring_hold_to_stop),
                    container = content,
                    content = if (content == Color.White) Night else Color.White,
                    onStop = onStop,
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                )
                if (state.snoozesLeft > 0) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onSnooze, border = BorderStroke(1.dp, content.copy(alpha = 0.5f)), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(LocalResources.current.getQuantityString(R.plurals.ring_snooze, state.snoozesLeft, Snooze.MINUTES, state.snoozesLeft), color = content)
                    }
                }
            }
            is PhoneRingService.State.Snoozed -> {
                Text(stringResource(R.string.ring_snoozed, Snooze.MINUTES), style = MaterialTheme.typography.titleMedium, color = content)
                // Auch beim Schlummern lässt sich der Wecker durch Halten ganz beenden.
                Spacer(Modifier.height(24.dp))
                HoldToStopButton(
                    label = stringResource(R.string.ring_hold_to_stop),
                    container = content,
                    content = if (content == Color.White) Night else Color.White,
                    onStop = onStop,
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                )
            }
            PhoneRingService.State.Idle -> Unit
        }
    }
    }
}
