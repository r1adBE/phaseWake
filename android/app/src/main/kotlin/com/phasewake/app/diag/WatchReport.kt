package com.phasewake.app.diag

import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.core.DiagReport
import com.phasewake.app.core.DiagReports
import com.phasewake.app.shared.DataLayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Diagnose-Bericht der Uhr ([DataLayer.PATH_DIAG_LOG]), nur im Speicher: Er wird auf Wunsch geholt und nicht abgelegt. */
object WatchReport {
    /** Wie lange auf die Antwort der Uhr gewartet wird. */
    private const val TIMEOUT_MS = 10_000L

    private val latest = MutableStateFlow<DiagReport?>(null)

    /** Zuletzt empfangener Bericht; `null`, solange keiner kam. */
    val report: StateFlow<DiagReport?> = latest.asStateFlow()

    /** Bericht der Uhr empfangen. */
    fun received(payload: ByteArray?) {
        DiagReports.decode(DataLayer.text(payload))?.let { latest.value = it }
    }

    /** Fragt die Uhr nach ihrem Bericht. @return der Bericht oder `null` ohne Antwort in [TIMEOUT_MS]. */
    suspend fun fetch(app: PhaseWakeApp): DiagReport? {
        val before = latest.value
        app.sync.message(DataLayer.CAPABILITY_WATCH, DataLayer.PATH_DIAG_REQUEST)
        return withTimeoutOrNull(TIMEOUT_MS) { report.first { it != null && it !== before } }
    }
}
