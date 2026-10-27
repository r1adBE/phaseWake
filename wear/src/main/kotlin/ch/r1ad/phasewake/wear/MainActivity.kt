package ch.r1ad.phasewake.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/** Einstieg der Watch-App. Platzhalter bis Sensorik und Weckung stehen. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Text("PhaseWake") }
        }
    }
}
