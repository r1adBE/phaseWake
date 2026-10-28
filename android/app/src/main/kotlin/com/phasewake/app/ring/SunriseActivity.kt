package com.phasewake.app.ring

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phasewake.app.R
import com.phasewake.app.core.Sunrise
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.components.SunriseBackground
import com.phasewake.app.ui.components.SunriseSky
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ui.theme.ThemeMode
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * Sonnenaufgang ab Weckfenster: Bildschirm und Helligkeit steigen bis zur Zielzeit an.
 * Klingelt der Wecker, übernimmt [PhoneRingActivity].
 */
class SunriseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val active = SunriseLight.active.value ?: SunriseLight.Active(
            alarmId = intent.getStringExtra(PhoneRingService.EXTRA_ALARM_ID),
            start = Instant.ofEpochMilli(intent.getLongExtra(SunriseLight.EXTRA_START, 0L)),
            end = Instant.ofEpochMilli(intent.getLongExtra(SunriseLight.EXTRA_END, 0L)),
        )
        SunriseLight.resume(active)
        setContent {
            PhaseWakeTheme(ThemeMode.DARK) {
                val ring by PhoneRingService.ringState.collectAsStateWithLifecycle()
                val current by SunriseLight.active.collectAsStateWithLifecycle()
                LaunchedEffect(ring, current) {
                    // Der Weckbildschirm übernimmt, oder das Licht wurde ausgeschaltet.
                    if (ring !is PhoneRingService.State.Idle || current == null) finish()
                }
                var now by remember { mutableStateOf(Instant.now()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        now = Instant.now()
                        if (now.isAfter(active.end.plus(MAX_AFTER_END))) SunriseLight.stop(this@SunriseActivity)
                        delay(1_000)
                    }
                }
                val progress = active.progress(now)
                LaunchedEffect(progress) { setBrightness(progress) }
                SunriseScreen(progress, now, onOff = { SunriseLight.stop(this@SunriseActivity) })
            }
        }
    }

    private fun setBrightness(progress: Float) {
        window.attributes = window.attributes.apply { screenBrightness = Sunrise.brightness(progress) }
    }

    private companion object {
        /** Ohne Wecken (Uhr nicht verbunden, Handy-Ton aus) bleibt das Licht noch so lange an. */
        val MAX_AFTER_END: Duration = Duration.ofMinutes(15)
    }
}

@Composable
internal fun SunriseScreen(progress: Float, now: Instant, onOff: () -> Unit) {
    val colors = SunriseSky.colors(progress)
    SunriseBackground(progress, Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(Format.time(now), style = MaterialTheme.typography.displayLarge, color = colors.content)
            Text(stringResource(R.string.sunrise_title), style = MaterialTheme.typography.titleLarge, color = colors.contentMuted)
            Spacer(Modifier.height(48.dp))
            TextButton(onClick = onOff) {
                Text(stringResource(R.string.sunrise_off), style = MaterialTheme.typography.titleMedium, color = colors.content)
            }
        }
    }
}
