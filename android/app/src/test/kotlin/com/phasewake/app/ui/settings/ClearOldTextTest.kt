package com.phasewake.app.ui.settings

import com.phasewake.app.R
import com.phasewake.app.core.Learning
import com.phasewake.app.learn.LearningState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Rückfrage «Älter als 90 Tage löschen»: Nur bei offener Lernphase ([LearningState.openSince]) kommt der Zusatz, dass
 * ihre Aufzeichnungen bleiben; sonst nur der Text, was gelöscht wird. Wie `SettingsClearOldPhoneTests` (iPhone).
 */
class ClearOldTextTest {
    private val since = Instant.parse("2026-11-10T20:00:00Z")

    @Test
    fun addsLearningNoteOnlyWhileLearningIsOpen() {
        assertEquals(listOf(R.string.settings_clear_old_text), clearOldTexts(learningOpen = false))
        assertEquals(
            listOf(R.string.settings_clear_old_text, R.string.settings_clear_old_learn),
            clearOldTexts(learningOpen = true),
        )
    }

    /** Offen: begonnen ohne Werte oder «Neu lernen» läuft; fertig gelernt oder nie begonnen: nicht offen. */
    @Test
    fun openLearningPhaseFollowsOpenSince() {
        fun state(since: Instant?, learned: Boolean, relearning: Boolean = false) = LearningState(
            since = since, values = if (learned) Learning.Values(null, null, null, 7) else null,
            seen = false, updatedAt = 0, relearning = relearning, consent = true,
        )
        assertEquals(true, state(since, learned = false).openSince != null)
        assertEquals(true, state(since, learned = true, relearning = true).openSince != null)
        assertEquals(false, state(since, learned = true).openSince != null)
        assertEquals(false, state(null, learned = false).openSince != null)
    }
}
