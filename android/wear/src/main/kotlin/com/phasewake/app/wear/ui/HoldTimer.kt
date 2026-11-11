package com.phasewake.app.wear.ui

import android.os.SystemClock
import kotlinx.coroutines.delay

/** Abstand der Fortschritts-Schritte beim Halten (etwa ein Bild bei 60 Hz). */
private const val HOLD_TICK_MILLIS = 16L

/**
 * Misst das Halten an der Uhr ([now], Millisekunden), nicht an einer Animation: Mit «Animationen entfernen»
 * (Animationsdauer 0) liefe eine Animation sofort durch, und ein Tipp würde den Wecker beenden (Audit UI-1).
 * Gleich wie `awaitHold` der Handy-App. Meldet den Fortschritt 0–1 an [onProgress] und kehrt erst zurück, wenn
 * [holdMillis] vergangen sind; wird die Coroutine vorher abgebrochen (Loslassen), kehrt sie nie normal zurück.
 * [pause] wartet (Tests: ohne echte Zeit).
 */
internal suspend fun awaitHold(
    holdMillis: Int,
    onProgress: suspend (Float) -> Unit,
    now: () -> Long = SystemClock::uptimeMillis,
    pause: suspend (Long) -> Unit = { delay(it) },
) {
    val start = now()
    while (true) {
        val elapsed = now() - start
        onProgress((elapsed.toFloat() / holdMillis).coerceIn(0f, 1f))
        if (elapsed >= holdMillis) return
        pause(minOf(HOLD_TICK_MILLIS, holdMillis - elapsed))
    }
}
