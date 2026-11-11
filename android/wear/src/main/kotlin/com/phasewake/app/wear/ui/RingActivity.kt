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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.phasewake.app.wear.ui.components.rememberSkyInk
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
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
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.components.PulsingDot
import com.phasewake.app.wear.ui.components.SunriseSky
import com.phasewake.app.wear.ui.components.sunriseSky
import com.phasewake.app.wear.ui.theme.Brand
import com.phasewake.app.wear.ui.theme.PhaseWakeTheme
import com.phasewake.app.wear.ui.theme.nightGlow
import com.phasewake.app.wear.wake.RingUi
import com.phasewake.app.wear.wake.StartRefused
import com.phasewake.app.wear.wake.WakeService
import com.phasewake.app.wear.wake.ringReasonRes
import com.phasewake.app.wear.wearApp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Weckbildschirm: erscheint über dem Sperrbildschirm, solange es klingelt; beim Schlummern schliesst er sich, ausser er wurde über die Schlummer-Mitteilung geöffnet. */
class RingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Zeiten nach der 12/24-h-Einstellung der Uhr (Audit N-10).
        WearClock.update(this)
        val ringState = wearApp.ringState
        // Über die Weck-Mitteilung ohne laufenden Dienst (Start verweigert, Audit N-05): Dienst jetzt nachholen.
        val rescue = savedInstanceState == null && StartRefused.resume(this, intent)
        setContent {
            PhaseWakeTheme {
                val ui by ringState.ui.collectAsStateWithLifecycle()
                var rang by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(ui) {
                    when (ui) {
                        RingUi.Idle -> {
                            // Beim Nachholen kurz auf den Dienst warten, sonst schlösse sich der Bildschirm sofort.
                            if (rescue && !rang) delay(RESCUE_WAIT_MS)
                            finish()
                        }
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

        /** Weck-Mitteilung ohne laufenden Dienst ([StartRefused]). */
        const val VIA_RESCUE = "rescue"

        /** So lange wartet der nachgeholte Weckbildschirm auf das Klingeln des Dienstes. */
        private const val RESCUE_WAIT_MS = 10_000L

        /** Ob der Weckbildschirm gerade zu sehen ist; sonst erinnert der Dienst ([com.phasewake.app.wear.wake.onRealert]). */
        @Volatile
        var shown: Boolean = false
            private set
    }
}

internal const val HOLD_MILLIS = 1200

/** Notausgang bei der Weckaufgabe am Handy: so lange halten beendet immer. */
internal const val EMERGENCY_HOLD_MILLIS = 5000

/** Darunter gilt das Display als klein (kleine runde Uhren haben etwa 192 dp). */
private const val COMPACT_HEIGHT_DP = 210

/**
 * Grösste Schriftskalierung auf dem Weckbildschirm (Audit UI-3): Mit grosser Systemschrift wurde der Schlummer-Knopf
 * abgeschnitten. Darüber bleibt die Schrift so gross; reicht der Platz trotzdem nicht, fallen Punkt und Grund weg.
 */
private const val MAX_FONT_SCALE = 1.15f

/**
 * Anteil der Displayhöhe, den der Inhalt auf runden Uhren nutzen darf: Oben und unten wird der Kreis schmal. Mit
 * grosser Schrift sind die Zeilen breiter und stossen früher an den Rand, darum dann weniger.
 */
private const val ROUND_HEIGHT_SHARE = 0.95f
private const val ROUND_HEIGHT_SHARE_LARGE_FONT = 0.85f

/** Ab dieser Schriftskalierung gilt [ROUND_HEIGHT_SHARE_LARGE_FONT]. */
private const val LARGE_FONT_SCALE = 1.05f

/** [light]: Fortschritt des Sonnenaufgangs (0–1) als Hintergrund, `null` = Nachthimmel. */
@Composable
internal fun RingScreen(ui: RingUi, onDismiss: () -> Unit, onSnooze: () -> Unit, light: Float? = null) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(MAX_FONT_SCALE))) {
        RingContent(ui, onDismiss, onSnooze, light)
    }
}

@Composable
private fun RingContent(ui: RingUi, onDismiss: () -> Unit, onSnooze: () -> Unit, light: Float?) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current
    // Kleine runde Displays (um 192 dp): kleinerer Punkt, knappere Abstände, damit unten nichts abgeschnitten wird.
    val compact = configuration.screenHeightDp < COMPACT_HEIGHT_DP
    // Weckaufgabe am Handy (vom Handy bestätigt): Halten nur noch als Notausgang, dafür lange.
    // Bleibt die Bestätigung aus, schaltet der Dienst auf normales Halten zurück; beim Schlummern gilt immer Halten.
    val taskOnPhone = (ui as? RingUi.Ringing)?.taskOnPhone == true
    val holdMillis = if (taskOnPhone) EMERGENCY_HOLD_MILLIS else HOLD_MILLIS
    val holdLabel = stringResource(if (taskOnPhone) R.string.wake_task_on_phone else R.string.ring_hold_to_stop)
    // Mit Sonnenaufgang: Licht statt Nachthimmel, jeder Text mit der Schrift, die an seiner Stelle lesbar ist.
    val background = if (light != null) Modifier.sunriseSky(light) else Modifier.nightGlow()
    val sidePadding = if (compact) 12 else 18
    // Mit Aufgabe am Handy braucht der Hinweis «… oder 5 s halten» den Platz: kein Grund (Audit UI-4).
    val reason = (ui as? RingUi.Ringing)?.takeIf { !taskOnPhone }?.let { stringResource(ringReasonRes(it.reason)) }.orEmpty()
    val reasonFit = rememberReasonFit(reason, MaterialTheme.typography.bodySmall, configuration.screenWidthDp - 2 * sidePadding)
    // Zweizeiliger Grund: Punkt kleiner und kein Abstand darunter, damit der Schlummer-Knopf nicht in den Rand rutscht.
    val twoLines = reason.isNotEmpty() && reasonFit.lines > 1
    // Punkt und Stopp-Knopf teilen sich dasselbe Halten und denselben Fortschritt. Gemessen wird an der Uhr,
    // die Füllung zeigt den Fortschritt nur an (Audit UI-1: ohne Animationen beendete sonst ein Tipp).
    val hold = Modifier
        .pointerInput(holdMillis) {
            detectTapGestures(onPress = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                var held = false
                val job = scope.launch {
                    awaitHold(holdMillis, onProgress = { progress.snapTo(it) })
                    held = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDismiss()
                }
                // Auch wenn die Geste abbricht (Wechsel zwischen Halten und Aufgabe): kein Beenden ohne Halten.
                try {
                    tryAwaitRelease()
                } finally {
                    if (!held) {
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(250)) }
                    }
                }
            })
        }

    Box(Modifier.fillMaxSize().then(background), contentAlignment = Alignment.Center) {
        // Fortschritt des Haltens zusätzlich als Ring am Rand.
        val ringColor = if (light != null) SunriseSky.ink(light, SunriseSky.Area(0f, 0f, 1f, 1f)).content else Brand.Wave
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2 + 1.dp.toPx()
            drawArc(
                ringColor, -90f, 360f * progress.value, false,
                Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        // Inhalt etwas über der Mitte und mit Abstand zum runden Rand unten (Audit U-1): «noch N» unter
        // «Schlummern 5 Min» bleibt auch auf kleinen runden Uhren ganz sichtbar. Passt nicht alles, fallen
        // zuerst der Punkt, dann der Grund weg (Audit UI-3).
        val share = when {
            !configuration.isScreenRound -> 1f
            LocalDensity.current.fontScale >= LARGE_FONT_SCALE -> ROUND_HEIGHT_SHARE_LARGE_FONT
            else -> ROUND_HEIGHT_SHARE
        }
        FitColumn(
            heightShare = share,
            modifier = Modifier.fillMaxSize().padding(
                start = sidePadding.dp,
                end = sidePadding.dp,
                bottom = if (compact) 14.dp else 18.dp,
            ),
        ) {
            val (dotInk, dotPlace) = rememberSkyInk(light, NIGHT_INK, plaque = false)
            PulsingDot(
                color = if (light != null) dotInk.content.copy(alpha = 0.6f) else Brand.Wave,
                dot = dotInk.content,
                modifier = Modifier
                    .layoutId(Optional.DOT)
                    .padding(bottom = if (compact || twoLines) 0.dp else 2.dp)
                    .size((if (compact) 34.dp else 44.dp) - (if (twoLines) 10.dp else 0.dp))
                    .then(dotPlace)
                    .then(hold),
            )
            when (ui) {
                is RingUi.Ringing -> Ringing(ui, reason, reasonFit, light)
                is RingUi.Snoozed -> Snoozed(ui, light)
                RingUi.Idle -> Unit
            }
            if (ui != RingUi.Idle) {
                // Mit Aufgabe darf der Hinweis drei Zeilen haben, der Grund fällt dafür weg (Audit UI-4).
                HoldToStop(
                    holdLabel, progress.value, compact, onDismiss,
                    maxLines = if (taskOnPhone) 3 else 2,
                    modifier = Modifier.padding(top = if (compact) 3.dp else 5.dp).then(hold),
                )
            }
            if (ui is RingUi.Ringing && ui.snoozesLeft > 0) {
                SnoozeButton(ui.snoozesLeft, onSnooze, compact, Modifier.padding(top = if (compact) 3.dp else 5.dp))
            }
        }
    }
}
