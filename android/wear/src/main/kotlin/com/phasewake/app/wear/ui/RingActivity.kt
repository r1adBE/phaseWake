package com.phasewake.app.wear.ui

import android.content.Intent
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Snooze
import com.phasewake.app.core.Sunrise
import com.phasewake.app.core.WakeReason
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.components.PulsingDot
import com.phasewake.app.wear.ui.components.SunriseSky
import com.phasewake.app.wear.ui.components.sunriseSky
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
                // Sonnenaufgang auf der Uhr als Hintergrund: beim Wecken in zwei Minuten von dunkel zu hell,
                // ab Fenster in einer Minute vom erreichten Stand aus (wie auf dem Handy).
                val ringing = ui as? RingUi.Ringing
                val sunrise = ringing?.sunrise ?: Sunrise.OFF
                val light = remember { Animatable(0f) }
                LaunchedEffect(sunrise, ringing?.lightFrom) {
                    val from = ringing?.lightFrom ?: return@LaunchedEffect
                    if (sunrise == Sunrise.OFF) return@LaunchedEffect
                    // Nur heller werden, nie zurück.
                    if (from > light.value) light.snapTo(from)
                    val millis = if (from > 0f) Sunrise.CATCH_UP_SECONDS * 1000 else (Sunrise.RING_SECONDS * 1000 * (1f - light.value)).toInt()
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
                    ui = ui,
                    onDismiss = { WakeService.command(this, WakeService.ACTION_DISMISS) },
                    onSnooze = { WakeService.command(this, WakeService.ACTION_SNOOZE) },
                    light = if (sunrise == Sunrise.OFF) null else light.value,
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
        wearApp.diag.log(DiagKind.RING_SCREEN, "watch shown via ${intent?.getStringExtra(EXTRA_VIA) ?: "unknown"}")
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

        /** Ob der Weckbildschirm gerade zu sehen ist; sonst erinnert der Dienst ([com.phasewake.app.wear.wake.onRealert]). */
        @Volatile
        var shown: Boolean = false
            private set
    }
}

private const val HOLD_MILLIS = 1200

/** Notausgang bei der Weckaufgabe am Handy: so lange halten beendet immer. */
private const val EMERGENCY_HOLD_MILLIS = 5000

/** Darunter gilt das Display als klein (kleine runde Uhren haben etwa 192 dp). */
private const val COMPACT_HEIGHT_DP = 210

/** [light]: Fortschritt des Sonnenaufgangs (0–1) als Hintergrund, `null` = Nachthimmel. */
@Composable
internal fun RingScreen(ui: RingUi, onDismiss: () -> Unit, onSnooze: () -> Unit, light: Float? = null) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // Kleine runde Displays (um 192 dp): kleinerer Punkt, knappere Abstände, damit unten nichts abgeschnitten wird.
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_DP
    // Weckaufgabe am Handy (vom Handy bestätigt): Halten nur noch als Notausgang, dafür lange.
    // Bleibt die Bestätigung aus, schaltet der Dienst auf normales Halten zurück; beim Schlummern gilt immer Halten.
    val taskOnPhone = (ui as? RingUi.Ringing)?.taskOnPhone == true
    val holdMillis = if (taskOnPhone) EMERGENCY_HOLD_MILLIS else HOLD_MILLIS
    val holdLabel = stringResource(if (taskOnPhone) R.string.wake_task_on_phone else R.string.ring_hold_to_stop)
    // Mit Sonnenaufgang: Licht statt Nachthimmel, Schrift passt sich der Helligkeit an.
    val sky = light?.let { SunriseSky.colors(it) }
    val content = sky?.content ?: Brand.Moon
    val accent = sky?.content?.copy(alpha = 0.6f) ?: Brand.Wave
    val background = if (light != null) Modifier.sunriseSky(light) else Modifier.nightGlow()

    Box(Modifier.fillMaxSize().then(background), contentAlignment = Alignment.Center) {
        // Fortschritt des Haltens als Ring am Rand.
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2 + 1.dp.toPx()
            drawArc(
                if (sky != null) content else Brand.Wave, -90f, 360f * progress.value, false,
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
                color = accent,
                dot = content,
                modifier = Modifier
                    .size(if (compact) 48.dp else 76.dp)
                    // Auch mit Aufgabe beendet die Bedienungshilfe (Notausgang, wie langes Halten).
                    .semantics {
                        contentDescription = holdLabel
                        onClick(label = holdLabel) { onDismiss(); true }
                    }
                    .pointerInput(holdMillis) {
                        detectTapGestures(onPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val hold = scope.launch {
                                progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDismiss()
                            }
                            // Auch wenn die Geste abbricht (Wechsel zwischen Halten und Aufgabe): kein Beenden ohne Halten.
                            try {
                                tryAwaitRelease()
                            } finally {
                                if (progress.value < 1f) {
                                    hold.cancel()
                                    scope.launch { progress.animateTo(0f, tween(250)) }
                                }
                            }
                        })
                    },
            )
            Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
            when (ui) {
                is RingUi.Ringing -> Ringing(ui, onSnooze, compact, content = content, muted = sky?.contentMuted)
                is RingUi.Snoozed -> Snoozed(ui)
                RingUi.Idle -> Unit
            }
        }
    }
}

/** [content]/[muted]: Schriftfarben; [muted] `null` = Farben des Nachthimmels. */
@Composable
private fun Ringing(ui: RingUi.Ringing, onSnooze: () -> Unit, compact: Boolean = false, content: Color = Brand.Moon, muted: Color? = null) {
    // Jede Zeile einzeilig: Lange Namen oder Übersetzungen dürfen den Schlummer-Knopf nicht nach unten schieben.
    Text(
        ui.label.ifBlank { stringResource(R.string.ring_greeting) },
        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
        color = content,
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
        color = muted ?: Brand.Mist,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        stringResource(if (ui.taskOnPhone) R.string.wake_task_on_phone else R.string.ring_hold_to_stop),
        style = MaterialTheme.typography.labelSmall,
        color = muted ?: Brand.Muted,
        textAlign = TextAlign.Center,
        // Der Hinweis zur Aufgabe ist länger: zwei Zeilen.
        maxLines = if (ui.taskOnPhone) 2 else 1,
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
                    LocalResources.current.getQuantityString(R.plurals.ring_snooze, ui.snoozesLeft, Snooze.MINUTES, ui.snoozesLeft),
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
    // Beenden auch hier durch Halten (Punkt oben), auch mit Weckaufgabe.
    Text(
        stringResource(R.string.ring_hold_to_stop),
        style = MaterialTheme.typography.labelSmall,
        color = Brand.Muted,
        textAlign = TextAlign.Center,
    )
}
