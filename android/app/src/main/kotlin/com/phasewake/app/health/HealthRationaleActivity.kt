package com.phasewake.app.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phasewake.app.R
import com.phasewake.app.phoneApp
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ui.theme.ThemeMode

/** Erklärt, wofür Phase Wake die Schlafdaten liest (Pflicht für Health Connect). */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = phoneApp
        setContent {
            val mode = app.settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM).value
            PhaseWakeTheme(mode) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                        Text(stringResource(R.string.health_title), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(R.string.health_rationale),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
