package com.phasewake.app.diag

import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.core.Validation
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/**
 * Daten des Validierungs-Modus ([Validation]) für Diagnose-Bericht und Export: eigene Nächte,
 * abgeschlossene Aufzeichnungen, gelernte Werte und die Schlafphasen aus Health Connect (nur gelesen, nur im Speicher;
 * ohne Zugriff keine, dann hat keine Nacht Referenz). Rechnet nur, ändert nichts am Wecken.
 */
internal object ValidationData {
    /** So weit vor der ältesten Nacht bzw. Aufzeichnung werden Sitzungen gelesen (Bettzeit vor dem Fenster). */
    private val LOOK_BACK: Duration = Duration.ofDays(1)

    /** Alle Entscheide mit Referenzphase; leer ohne Nächte und Aufzeichnungen. */
    suspend fun entries(app: PhaseWakeApp): List<Validation.Entry> {
        val nights = app.nights.load()
        val recordings = app.recordings.load().map { it.toLearnNight() }
        val oldest = (nights.map { it.windowStart } + recordings.map { it.start }).minOrNull() ?: return emptyList()
        val sessions = app.health.sessions(oldest.minus(LOOK_BACK), Instant.now())
        val clearedAt = app.settings.historyClearedAt.first()
        return Validation.evaluate(nights, recordings, sessions, clearedAt, app.learning.current().values, ZoneId.systemDefault())
    }
}
