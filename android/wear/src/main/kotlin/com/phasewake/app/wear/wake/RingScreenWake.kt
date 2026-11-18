package com.phasewake.app.wear.wake

import android.content.Context
import android.os.PowerManager

/**
 * Schaltet beim Klingeln das Display ein (auch aus Always-on), damit Wear OS die Vollbild-Absicht der Weck-Mitteilung
 * ausführt: Bei dunklem Display tut die Uhr das nicht (Gerätetest 16.–18.11.2026, siehe docs/ENTSCHEIDE.md). Ein
 * Wakelock mit [PowerManager.ACQUIRE_CAUSES_WAKEUP], befristet auf [MILLIS]; danach hält der Weckbildschirm das Display
 * selbst an (`FLAG_KEEP_SCREEN_ON`).
 */
internal class RingScreenWake(private val context: Context) {
    private var lock: PowerManager.WakeLock? = null

    /** Ob gerade ein Display-Wakelock gehalten wird. */
    val held: Boolean get() = lock?.isHeld == true

    /** Display einschalten bzw. anlassen; ersetzt einen schon gehaltenen Wakelock (zuerst neu nehmen, dann lösen). */
    fun wake() {
        lock = swapHeld(lock, {
            // Die Stufe ist veraltet, aber die einzige, mit der ein Wakelock das Display einschaltet; die Activity
            // kann es erst, wenn sie startet – genau das verhindert das dunkle Display.
            @Suppress("DEPRECATION")
            val level = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(level, TAG)
                .apply { acquire(MILLIS) }
        }) { old -> if (old.isHeld) old.release() }
    }

    /** Ende der Klingel-Runde: Das Display darf wieder ausgehen. */
    fun release() {
        lock?.takeIf { it.isHeld }?.release()
        lock = null
    }

    private companion object {
        const val TAG = "phasewake:ringscreen"

        /** So lange bleibt das Display höchstens an, ohne dass der Weckbildschirm es übernimmt. */
        const val MILLIS = 10_000L
    }
}
