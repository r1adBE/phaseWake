package com.phasewake.app.wear.wake

import com.phasewake.app.core.WakeReason
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Was der Weckbildschirm zeigt. Der [WakeService] schreibt, die Oberfläche liest. */
sealed interface RingUi {
    data object Idle : RingUi

    /** [taskOnPhone]: Weckaufgabe am Handy statt Halten (nur, solange das Handy erreichbar ist). */
    data class Ringing(val label: String, val reason: WakeReason, val since: Instant, val snoozesLeft: Int, val taskOnPhone: Boolean = false) : RingUi

    data class Snoozed(val label: String, val until: Instant, val snoozesLeft: Int, val taskOnPhone: Boolean = false) : RingUi
}

class RingState {
    private val state = MutableStateFlow<RingUi>(RingUi.Idle)
    val ui: StateFlow<RingUi> = state.asStateFlow()

    internal fun publish(value: RingUi) {
        state.value = value
    }
}
