package com.phasewake.app.wear.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.phasewake.app.core.Sunrise
import com.phasewake.app.wear.ui.components.SunriseSky
import com.phasewake.app.wear.ui.components.sunriseSky
import com.phasewake.app.wear.ui.theme.PhaseWakeTheme
import com.phasewake.app.wear.wake.RingUi
import com.phasewake.app.wear.wake.WatchSunrise
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay

/**
 * Sonnenaufgang ab Weckfenster auf der Uhr: Farbe und Helligkeit steigen bis zur Zielzeit an
 * (Kurve wie auf dem Handy). Der Bildschirm bleibt nur an, solange dieser Bildschirm offen ist.
 * Tippen (oder Wischen) schliesst ihn, die Messung läuft weiter. Klingelt es, übernimmt [RingActivity].
 */
class SunriseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ringState = wearApp.ringState
        setContent {
            PhaseWakeTheme {
                val current by WatchSunrise.active.collectAsStateWithLifecycle()
                val ring by ringState.ui.collectAsStateWithLifecycle()
                // Licht beendet, oder der Weckbildschirm übernimmt.
                LaunchedEffect(current, ring) { if (current == null || ring !is RingUi.Idle) finish() }
                val active = current ?: return@PhaseWakeTheme
                var now by remember { mutableStateOf(Instant.now()) }
                LaunchedEffect(active) {
                    while (true) {
                        now = Instant.now()
                        // Sicherheitsnetz, falls der Weckdienst nicht mehr läuft.
                        if (now.isAfter(active.end.plus(MAX_AFTER_END))) WatchSunrise.stop(this@SunriseActivity)
                        delay(1_000)
                    }
                }
                val progress = active.progress(now)
                LaunchedEffect(progress) {
                    window.attributes = window.attributes.apply { screenBrightness = Sunrise.brightness(progress) }
                }
                SunriseScreen(progress, now, onOff = { WatchSunrise.stop(this@SunriseActivity) })
            }
        }
    }

    override fun onDestroy() {
        // Vom Nutzer geschlossen (Tippen, Wischen): Licht aus, die Mitteilung verschwindet.
        if (isFinishing) WatchSunrise.stop(this)
        super.onDestroy()
    }

    private companion object {
        val MAX_AFTER_END: Duration = Duration.ofMinutes(15)
    }
}

/** Nur Himmel und Uhrzeit, ohne Text: Tippen irgendwo schliesst. */
@Composable
internal fun SunriseScreen(progress: Float, now: Instant, onOff: () -> Unit) {
    val colors = SunriseSky.colors(progress)
    Box(
        Modifier
            .fillMaxSize()
            .sunriseSky(progress)
            .semantics { onClick { onOff(); true } }
            .pointerInput(Unit) { detectTapGestures(onTap = { onOff() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(now.atZone(ZoneId.systemDefault())),
            style = MaterialTheme.typography.displayMedium,
            color = colors.content,
        )
    }
}
