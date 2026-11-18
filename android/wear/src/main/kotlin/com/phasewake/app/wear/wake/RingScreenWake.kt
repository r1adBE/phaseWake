package com.phasewake.app.wear.wake

import android.content.Context
import android.os.PowerManager

/**
 * Schaltet das Display ein (auch aus Always-on), damit Wear OS die Vollbild-Absicht einer Mitteilung ausführt: Bei
 * dunklem Display tut die Uhr das nicht (Gerätetest 16.–18.11.2026, siehe docs/ENTSCHEIDE.md). Für das Klingeln, den
 * Notfallweg ohne Dienst ([StartRefused]) und den Sonnenaufgang ([WatchSunrise]), je mit eigenem [tag]. Ein Wakelock
 * mit [PowerManager.ACQUIRE_CAUSES_WAKEUP], befristet auf [MILLIS]; danach hält der geöffnete Bildschirm das Display
 * selbst an (`FLAG_KEEP_SCREEN_ON`).
 */
internal class RingScreenWake(
    private val tag: String = "phasewake:ringscreen",
    /** `false`: Display nur gedimmt einschalten (Sonnenaufgang, damit es im dunklen Zimmer nicht hell aufblitzt). */
    private val bright: Boolean = true,
) {
    private var lock: PowerManager.WakeLock? = null

    /** Ob gerade ein Display-Wakelock gehalten wird. */
    val held: Boolean get() = lock?.isHeld == true

    /** Display einschalten bzw. anlassen; ersetzt einen schon gehaltenen Wakelock (zuerst neu nehmen, dann lösen). */
    fun wake(context: Context) {
        lock = swapHeld(lock, {
            // Die Stufen sind veraltet, aber die einzigen, mit denen ein Wakelock das Display einschaltet; die Activity
            // kann es erst, wenn sie startet – genau das verhindert das dunkle Display.
            @Suppress("DEPRECATION")
            val level = (if (bright) PowerManager.SCREEN_BRIGHT_WAKE_LOCK else PowerManager.SCREEN_DIM_WAKE_LOCK) or
                PowerManager.ACQUIRE_CAUSES_WAKEUP
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(level, tag)
                .apply { acquire(MILLIS) }
        }) { old -> if (old.isHeld) old.release() }
    }

    /** Ende: Das Display darf wieder ausgehen. */
    fun release() {
        lock?.takeIf { it.isHeld }?.release()
        lock = null
    }

    companion object {
        /** So lange bleibt das Display höchstens an, ohne dass ein geöffneter Bildschirm es übernimmt. */
        private const val MILLIS = 10_000L

        /** Nach so vielen Millisekunden kommt die Mitteilung neu, falls ihr Bildschirm nicht zu sehen ist. */
        const val RETRY_MS = 2000L

        /** «on» oder «off» für die Diagnose (Always-on zählt als aus). */
        fun screen(context: Context): String =
            if (context.getSystemService(PowerManager::class.java).isInteractive) "on" else "off"
    }
}
