package com.phasewake.app.wear.system

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Kurzer Wakelock zwischen einem System-Alarm und dem Dienst, den er startet (Befund 13.11.2026): Sobald `onReceive`
 * zurückkehrt, löst das System den Wakelock des Alarms; ohne eigenen schlief die Uhr bis zu 2 Min, bevor der Dienst lief.
 *
 * Die Empfänger nehmen ihn vor dem Start ([acquire]), der Dienst löst ihn, sobald er seinen eigenen Wakelock hält oder
 * nichts mehr zu tun ist ([release]); ebenso ein verweigerter Start. Ein einziger Wakelock ohne Zählung: Ein zweiter
 * Start verlängert ihn nur. Überschneiden sich zwei Starts, löst der erste Dienst ihn für beide; dann trägt der eigene
 * Wakelock des zweiten oder das Zeitlimit [TIMEOUT_MS], das auch jeden vergessenen Weg begrenzt.
 */
internal object StartLock {
    /** Name des Wakelocks (in `dumpsys power` sichtbar). */
    const val TAG = "phasewake:start"

    /** Höchstdauer: Danach löst das System den Wakelock in jedem Fall. */
    const val TIMEOUT_MS = 60_000L

    private var lock: PowerManager.WakeLock? = null

    /** Nimmt den Wakelock für höchstens [TIMEOUT_MS] (oder verlängert ihn). Fehler dabei halten den Start nicht auf. */
    @Synchronized
    fun acquire(context: Context) {
        try {
            val current = lock ?: context.applicationContext.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
                .apply { setReferenceCounted(false) }
                .also { lock = it }
            current.acquire(TIMEOUT_MS)
        } catch (e: RuntimeException) {
            Log.w("StartLock", "Start-Wakelock nicht möglich", e)
        }
    }

    /** Löst den Wakelock; ohne gehaltenen Wakelock nichts. */
    @Synchronized
    fun release() {
        lock?.takeIf { it.isHeld }?.release()
    }

    /** Ob der Wakelock gerade gehalten wird (für Tests). */
    val held: Boolean
        @Synchronized get() = lock?.isHeld == true

    /** Lösen und vergessen: Jeder Robolectric-Test hat einen eigenen PowerManager. Nur für Tests. */
    @Synchronized
    internal fun resetForTest() {
        release()
        lock = null
    }
}
