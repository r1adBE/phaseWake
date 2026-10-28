package ch.r1ad.phasewake.ring

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.r1ad.phasewake.R
import ch.r1ad.phasewake.core.Snooze
import ch.r1ad.phasewake.ui.Format
import ch.r1ad.phasewake.ui.components.PulsingDot
import ch.r1ad.phasewake.ui.theme.PhaseWakeTheme
import ch.r1ad.phasewake.ui.theme.ThemeMode
import java.time.Instant
import kotlinx.coroutines.delay

/** Weckbildschirm des Handys: immer im Nachtblau, unabhängig vom gewählten Design. */
class PhoneRingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            PhaseWakeTheme(ThemeMode.DARK) {
                val state by PhoneRingService.ringState.collectAsStateWithLifecycle()
                LaunchedEffect(state) { if (state is PhoneRingService.State.Idle) finish() }
                RingScreen(
                    state = state,
                    onStop = { PhoneRingService.command(this, PhoneRingService.ACTION_STOP) },
                    onSnooze = { PhoneRingService.command(this, PhoneRingService.ACTION_SNOOZE) },
                )
            }
        }
    }
}

private val Night = Color(0xFF0B1A4A)

@Composable
private fun RingScreen(state: PhoneRingService.State, onStop: () -> Unit, onSnooze: () -> Unit) {
    val brand = PhaseWakeTheme.brand
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = Instant.now()
        }
    }
    Column(
        Modifier.fillMaxSize().background(Night).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PulsingDot(Modifier.size(180.dp), color = brand.wave, dot = brand.dot)
        Spacer(Modifier.height(24.dp))
        Text(Format.time(now), style = MaterialTheme.typography.displayLarge, color = Color.White)
        Text(stringResource(R.string.ring_greeting), style = MaterialTheme.typography.titleLarge, color = brand.onHeroMuted)
        Spacer(Modifier.height(48.dp))
        when (state) {
            is PhoneRingService.State.Ringing -> {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Night),
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                ) { Text(stringResource(R.string.ring_stop), style = MaterialTheme.typography.titleMedium) }
                if (state.snoozesLeft > 0) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(stringResource(R.string.ring_snooze, Snooze.MINUTES, state.snoozesLeft), color = Color.White)
                    }
                }
            }
            is PhoneRingService.State.Snoozed ->
                Text(stringResource(R.string.ring_snoozed, Snooze.MINUTES), style = MaterialTheme.typography.titleMedium, color = Color.White)
            PhoneRingService.State.Idle -> Unit
        }
    }
}
