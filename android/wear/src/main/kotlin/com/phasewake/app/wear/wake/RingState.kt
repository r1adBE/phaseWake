package com.phasewake.app.wear.wake

import com.phasewake.app.core.Sunrise
import com.phasewake.app.core.WakeReason
import com.phasewake.app.wear.R
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Was der Weckbildschirm zeigt. Der [WakeService] schreibt, die Oberfläche liest. */
sealed interface RingUi {
    data object Idle : RingUi

    /**
     * [taskOnPhone]: Das Handy hat bestätigt, dass es die Weckaufgabe zeigt. Beenden hier dann nur noch
     * durch langes Halten (Notausgang); ohne Bestätigung normales Halten.
     * [sunrise]: Sonnenaufgang auf der Uhr als Hintergrund; [lightFrom]: Fortschritt, bei dem er weitergeht (0–1).
     */
    data class Ringing(
        val label: String,
        val reason: WakeReason,
        val since: Instant,
        val snoozesLeft: Int,
        val taskOnPhone: Boolean = false,
        val sunrise: Sunrise = Sunrise.OFF,
        val lightFrom: Float = 0f,
    ) : RingUi

    /** Beim Schlummern gilt immer Halten, auch mit Weckaufgabe (das Handy klingelt dann nicht). */
    data class Snoozed(val label: String, val until: Instant, val snoozesLeft: Int) : RingUi
}

/** Text zum Weckgrund, gleich auf Weckbildschirm, Mitteilung und Startseite. */
internal fun ringReasonRes(reason: WakeReason): Int = when (reason) {
    WakeReason.LIGHT_SLEEP -> R.string.ring_reason_light
    WakeReason.AWAKE -> R.string.ring_reason_awake
    WakeReason.DEADLINE -> R.string.ring_reason_deadline
    WakeReason.EXACT -> R.string.ring_reason_exact
    WakeReason.SHORT_NIGHT -> R.string.ring_reason_short_night
}

class RingState {
    private val state = MutableStateFlow<RingUi>(RingUi.Idle)
    val ui: StateFlow<RingUi> = state.asStateFlow()

    internal fun publish(value: RingUi) {
        state.value = value
    }
}
