package com.phasewake.app.ring

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phasewake.app.ui.components.Night
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Wie auf der Uhr: so lange halten, bis sich der Knopf gefüllt hat. */
internal const val HOLD_MILLIS = 1200

/** Abstand der Fortschritts-Schritte beim Halten (etwa ein Bild bei 60 Hz). */
private const val HOLD_TICK_MILLIS = 16L

/**
 * Misst das Halten an der Uhr ([now], Millisekunden), nicht an einer Animation: Mit «Animationen entfernen»
 * (Animationsdauer 0) liefe eine Animation sofort durch, und ein Tipp würde den Wecker beenden (Audit UI-1).
 * Meldet den Fortschritt 0–1 an [onProgress] und kehrt erst zurück, wenn [holdMillis] vergangen sind; wird die
 * Coroutine vorher abgebrochen (Loslassen), kehrt sie nie normal zurück. [pause] wartet (Tests: ohne echte Zeit).
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

/**
 * «Halten zum Stoppen»: Ein Tipp im Halbschlaf stoppt den Wecker nicht. Der Knopf füllt sich beim
 * Halten; loslassen vor dem Ende setzt ihn zurück. Mit Bildschirmleser genügt ein Doppeltipp.
 *
 * Gefüllter Hauptknopf in Lavendel (Audit U-4), beim Halten läuft von links eine helle Füllung [fill] darüber.
 * Die dunkle Schrift [content] hat auf beiden Farben Kontrast ≥ 4.5:1 (auf Lavendel etwa 10:1).
 */
@Composable
internal fun HoldToStopButton(
    label: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Night.Wave,
    fill: Color = Night.Moon,
    content: Color = Night.Bottom,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(50)
    Box(
        contentAlignment = Alignment.Center,
        // Mindestens 48 dp hoch (Tippziel), mit grosser Schrift höher statt abgeschnitten.
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(container)
            .semantics {
                role = Role.Button
                onClick(label = label) { onStop(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    var held = false
                    val hold = scope.launch {
                        // Gemessen an der Uhr; die Füllung zeigt den Fortschritt nur an.
                        awaitHold(HOLD_MILLIS, onProgress = { progress.snapTo(it) })
                        held = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStop()
                    }
                    // Auch wenn die Geste abbricht: kein Beenden ohne volles Halten.
                    try {
                        tryAwaitRelease()
                    } finally {
                        if (!held) {
                            hold.cancel()
                            scope.launch { progress.animateTo(0f, tween(250)) }
                        }
                    }
                })
            },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).align(Alignment.CenterStart).background(fill))
        Text(label, style = MaterialTheme.typography.titleMedium, color = content, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}
