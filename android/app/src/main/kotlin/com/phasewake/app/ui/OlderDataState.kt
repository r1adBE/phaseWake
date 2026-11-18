package com.phasewake.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.health.HealthSource
import com.phasewake.app.ui.history.OlderDataHint
import kotlinx.coroutines.launch

/**
 * Einmaliger Hinweis im Verlauf «Ältere Daten aus … freigeben» ([OlderDataHint]): verbunden, «Verlauf lesen» verfügbar,
 * aber nicht erteilt ([Insights.historyMissing]) und noch nicht beantwortet. [onAllow] fragt nur dieses Recht an;
 * danach liest der Verlauf neu (die Antwort von Health Connect zählt als Erteilung, [rememberInsights]).
 */
@Composable
internal fun rememberOlderDataHint(app: PhaseWakeApp, insights: Insights, onAllow: () -> Unit): OlderDataHint? {
    // Bis gelesen als beantwortet: kein kurzes Aufblitzen bei jedem Öffnen.
    val answered by app.settings.olderDataAnswered.collectAsStateWithLifecycle(initialValue = true)
    val context = LocalContext.current
    val source = remember(insights.sessions) {
        OlderDataHint.source(insights.sessions.map { HealthSource.name(context, it.source) })
    }
    return OlderDataHint.of(insights.historyMissing, answered, source, onAllow) {
        app.appScope.launch { app.settings.setOlderDataAnswered() }
    }
}
