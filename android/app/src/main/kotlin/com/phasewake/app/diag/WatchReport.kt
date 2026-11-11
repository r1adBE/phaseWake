package com.phasewake.app.diag

import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.DiagReport
import com.phasewake.app.core.DiagReports
import com.phasewake.app.shared.DataLayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Diagnose-Bericht der Uhr ([DataLayer.PATH_DIAG_LOG]), nur im Speicher: Er wird auf Wunsch geholt und nicht abgelegt.
 * Ein voller Bericht (500 Einträge) ist grösser als eine Nachricht der Wear-Dienste; die Uhr schickt ihn dann über einen
 * Kanal (`Outbox.sendOnce`), den [com.phasewake.app.sync.PhoneListenerService] hierher weitergibt.
 */
object WatchReport {
    /** Wie lange auf die Antwort der Uhr gewartet wird (ein Kanal braucht länger als eine Nachricht). */
    internal const val TIMEOUT_MS = 15_000L

    private val latest = MutableStateFlow<DiagReport?>(null)

    /** Zuletzt empfangener Bericht; `null`, solange keiner kam. */
    val report: StateFlow<DiagReport?> = latest.asStateFlow()

    /** Bericht der Uhr empfangen (Nachricht oder Kanal). */
    fun received(payload: ByteArray?) {
        DiagReports.decode(DataLayer.text(payload))?.let { latest.value = it }
    }

    /**
     * Fragt die Uhr nach ihrem Bericht. Ohne Antwort in [timeoutMs] steht im Protokoll des Handys «watch report missing».
     * @return der Bericht oder `null` ohne Antwort.
     */
    suspend fun fetch(app: PhaseWakeApp, timeoutMs: Long = TIMEOUT_MS): DiagReport? {
        val before = latest.value
        // Auch die Anfrage zählt zur Frist: Hängen die Wear-Dienste, bleibt die Seite nicht stehen.
        val answer = withTimeoutOrNull(timeoutMs) {
            app.sync.message(DataLayer.CAPABILITY_WATCH, DataLayer.PATH_DIAG_REQUEST)
            report.first { it != null && it !== before }
        }
        if (answer == null) app.diag.log(DiagKind.SYNC, "watch report missing")
        return answer
    }
}
