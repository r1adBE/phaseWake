package com.phasewake.app.wear.wake

import com.phasewake.app.core.WakeReason
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Was der Weckbildschirm zeigt. Der [WakeService] schreibt, die Oberfläche liest. */
sealed interface RingUi {
    data object Idle : RingUi

    data class Ringing(val label: String, val reason: WakeReason, val since: Instant, val snoozesLeft: Int) : RingUi

    data class Snoozed(val label: String, val until: Instant, val snoozesLeft: Int) : RingUi
}

class RingState {
    private val state = MutableStateFlow<RingUi>(RingUi.Idle)
    val ui: StateFlow<RingUi> = state.asStateFlow()

    internal fun publish(value: RingUi) {
        state.value = value
    }
}
