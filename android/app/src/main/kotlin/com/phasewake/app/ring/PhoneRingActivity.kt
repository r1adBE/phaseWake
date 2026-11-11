package com.phasewake.app.ring

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import com.phasewake.app.ui.components.rememberSkyInk
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.R
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Snooze
import com.phasewake.app.core.Sunrise
import com.phasewake.app.core.MathChallenge
import com.phasewake.app.core.WakeTask
import com.phasewake.app.phoneApp
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

/** Weckbildschirm des Handys: Nachthimmel oder – wenn gewählt – Sonnenaufgang, unabhängig vom gewählten Design. */
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
                // Weckaufgabe einer Nacht der Uhr: Solange dieser Bildschirm sichtbar ist und die Aufgabe zeigt,
                // der Uhr bestätigen (sofort, dann regelmässig). Ohne Bestätigung gilt auf der Uhr wieder Halten.
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                val showsWatchTask = (state as? PhoneRingService.State.Ringing)?.let { it.fromWatch && it.wakeTask == WakeTask.MATH } == true
                LaunchedEffect(showsWatchTask) {
                    if (!showsWatchTask) return@LaunchedEffect
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (true) {
                            PhoneRingService.command(this@PhoneRingActivity, PhoneRingService.ACTION_TASK_SHOWN)
                            delay(DataLayer.TASK_SHOWN_REFRESH_MS)
                        }
                    }
                }
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
                    onTyping = { PhoneRingService.command(this, PhoneRingService.ACTION_TYPING) },
                )
            }
        }
    }

    // singleInstance: Ein weiterer Start (Vollbild, Alarm, Erinnerung) kommt hier an, ohne zweiten Bildschirm;
    // Ton und Vibration steuert der Dienst, sie beginnen dabei nicht neu.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        shown = true
        // Für die Diagnose: auf welchem Weg der Weckbildschirm zu sehen ist.
        phoneApp.diag.log(DiagKind.RING_SCREEN, "phone shown via ${intent?.getStringExtra(EXTRA_VIA) ?: "unknown"}")
    }

    override fun onStop() {
        shown = false
        super.onStop()
    }

    companion object {
        /** Weg, auf dem der Bildschirm geöffnet wurde (nur für die Diagnose). */
        const val EXTRA_VIA = "via"
        const val VIA_FULL_SCREEN = "fullscreen"
        const val VIA_TAP = "tap"
        const val VIA_ALARM = "alarm"
        const val VIA_DIRECT = "direct"

        /** Direkter Start als Begleit-App der Uhr (auch aus dem Hintergrund erlaubt). */
        const val VIA_COMPANION = "companion"

        /** Ob der Weckbildschirm gerade zu sehen ist; sonst erinnert [PhoneRingAlert]. */
        @Volatile
        var shown: Boolean = false
            private set
    }
}

/** Darunter gilt die Höhe als knapp (kleine Handys, grosse Schrift): kleinerer Punkt, knappere Abstände. */
private val SHORT_HEIGHT = 700.dp

/** Darunter sehr knapp (Querformat, Teilbildschirm): ohne Punkt, kleinste Abstände. */
private val TINY_HEIGHT = 480.dp

/** Höhe einer Taste der Weckaufgabe je nach Platz; nie unter 48 dp (Tippziel). */
private val KEY_TALL = 60.dp
private val KEY_SHORT = 52.dp
private val KEY_TINY = 48.dp

@Composable
internal fun RingScreen(
    state: PhoneRingService.State,
    onStop: () -> Unit,
    onSnooze: () -> Unit,
    light: Float? = null,
    clock: () -> Instant = Instant::now,
    onTyping: () -> Unit = {},
) {
    var now by remember { mutableStateOf(clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = clock()
        }
    }
    // Mit Sonnenaufgang: Licht statt Nachthimmel; jeder Text nimmt die Schrift, die an seiner Stelle lesbar ist.
    val background: @Composable (@Composable () -> Unit) -> Unit = { inner ->
        if (light != null) SunriseBackground(light, Modifier.fillMaxSize(), inner) else AnimatedNight(Modifier.fillMaxSize()) { inner() }
    }
    val task = when (state) {
        is PhoneRingService.State.Ringing -> state.wakeTask
        is PhoneRingService.State.Snoozed -> state.wakeTask
        PhoneRingService.State.Idle -> WakeTask.NONE
    }
    // Über Schlummern hinweg dieselbe Aufgabe: gelöste Schritte bleiben gelöst.
    val challenge = remember { MathChallenge() }
    val math = task == WakeTask.MATH
    background {
        // Höhe und Breite bestimmen die Anordnung (Audit UI-2): Querformat zweispaltig, knappe Höhe kleiner;
        // alles scrollt, damit Stopp, Schlummern und die Tastatur immer erreichbar bleiben. Keine feste Ausrichtung.
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val landscape = maxWidth > maxHeight
            val size = when {
                maxHeight < TINY_HEIGHT -> RingSize.TINY
                maxHeight < SHORT_HEIGHT -> RingSize.SHORT
                else -> RingSize.TALL
            }
            val header: @Composable () -> Unit = { RingHeader(now, light, math, size) }
            val actions: @Composable () -> Unit = {
                RingActions(state, light, math, size, challenge, onStop, onSnooze, onTyping)
            }
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { header() }
                    Spacer(Modifier.width(24.dp))
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) { actions() }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(horizontal = 24.dp, vertical = if (size == RingSize.TALL) 24.dp else 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    header()
                    Spacer(Modifier.height(if (size == RingSize.TALL) (if (math) 16.dp else 48.dp) else 12.dp))
                    actions()
                }
            }
        }
    }
}

/** Platz für den Weckbildschirm. */
private enum class RingSize { TALL, SHORT, TINY }

/** Punkt, Uhrzeit und Gruss; mit Aufgabe oder knapper Höhe kleiner, sehr knapp ohne Punkt. */
@Composable
private fun RingHeader(now: Instant, light: Float?, math: Boolean, size: RingSize) {
    val dot = when (size) {
        RingSize.TALL -> if (math) 72.dp else 180.dp
        RingSize.SHORT -> if (math) 48.dp else 112.dp
        RingSize.TINY -> null
    }
    if (dot != null) {
        val (ink, place) = rememberSkyInk(light, NIGHT_INK, plaque = false)
        PulsingDot(Modifier.size(dot).then(place), color = if (light != null) ink.content.copy(alpha = 0.6f) else NightColors.Wave, dot = ink.content)
        Spacer(Modifier.height(if (math || size != RingSize.TALL) 8.dp else 24.dp))
    }
    val (timeInk, timePlace) = rememberSkyInk(light, NIGHT_INK)
    Text(
        Format.time(now),
        style = when {
            size == RingSize.TINY -> MaterialTheme.typography.displaySmall
            math || size == RingSize.SHORT -> MaterialTheme.typography.displayMedium
            else -> MaterialTheme.typography.displayLarge
        },
        color = timeInk.content,
        modifier = timePlace,
    )
    // Auf dem Sonnenaufgang etwas Abstand, damit sich die Schilder der beiden Zeilen nicht berühren.
    if (light != null) Spacer(Modifier.height(PLAQUE_GAP))
    val (greetInk, greetPlace) = rememberSkyInk(light, NIGHT_INK)
    Text(
        stringResource(R.string.ring_greeting),
        style = MaterialTheme.typography.titleLarge,
        color = greetInk.muted,
        textAlign = TextAlign.Center,
        modifier = greetPlace,
    )
}

/** Stopp (Halten oder Aufgabe) und Schlummern; beim Schlummern der Hinweis und Stopp. */
@Composable
private fun RingActions(
    state: PhoneRingService.State,
    light: Float?,
    math: Boolean,
    size: RingSize,
    challenge: MathChallenge,
    onStop: () -> Unit,
    onSnooze: () -> Unit,
    onTyping: () -> Unit,
) {
    val keyHeight = when (size) {
        RingSize.TALL -> KEY_TALL
        RingSize.SHORT -> KEY_SHORT
        RingSize.TINY -> KEY_TINY
    }
    val stop: @Composable () -> Unit = {
        if (math) {
            MathTaskPanel(challenge, light, onTyping, onSolved = onStop, modifier = Modifier.fillMaxWidth(), keyHeight = keyHeight)
        } else HoldToStopButton(
            label = stringResource(R.string.ring_hold_to_stop),
            onStop = onStop,
            modifier = Modifier.fillMaxWidth().heightIn(min = if (size == RingSize.TALL) 60.dp else 52.dp),
        )
    }
    when (state) {
        is PhoneRingService.State.Ringing -> {
            stop()
            if (state.snoozesLeft > 0) {
                Spacer(Modifier.height(12.dp))
                // Schild in der Form des Knopfs (ohne Überstand).
                val (ink, place) = rememberSkyInk(light, NIGHT_INK, padding = 0.dp)
                OutlinedButton(
                    onClick = onSnooze,
                    border = BorderStroke(1.dp, ink.content.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (size == RingSize.TALL) 56.dp else 48.dp).then(place),
                ) {
                    Text(
                        LocalResources.current.getQuantityString(R.plurals.ring_snooze, state.snoozesLeft, Snooze.MINUTES, state.snoozesLeft),
                        color = ink.content,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        is PhoneRingService.State.Snoozed -> {
            val (ink, place) = rememberSkyInk(light, NIGHT_INK)
            Text(
                stringResource(R.string.ring_snoozed, Snooze.MINUTES),
                style = MaterialTheme.typography.titleMedium,
                color = ink.content,
                textAlign = TextAlign.Center,
                modifier = place,
            )
            // Auch beim Schlummern lässt sich der Wecker ganz beenden: durch Halten oder mit der Aufgabe.
            Spacer(Modifier.height(if (size == RingSize.TALL) 24.dp else 12.dp))
            stop()
        }
        PhoneRingService.State.Idle -> Unit
    }
}

/** Abstand zwischen Zeilen mit Schild (Sonnenaufgang): zweimal der Überstand oben/unten und etwas Luft. */
internal val PLAQUE_GAP = 10.dp

/** Schrift auf dem Nachthimmel. */
internal val NIGHT_INK = SunriseSky.Ink(NightColors.Moon, NightColors.Muted)
