package com.phasewake.app.wear.record

import android.content.Context
import android.os.PowerManager

/**
 * Wakelocks von «Nacht aufzeichnen» ([RecordService], Audit F-01): ohne aufweckenden Sensor ein langer über die ganze
 * Aufzeichnung ([hold]), mit ihm nur ein kurzer je Bündel ([batch]); in der Lernphase nur je Stichprobe ([short],
 * [LearnSampler]).
 */
internal class RecordWakeLocks(private val context: Context) {
    private var longLock: PowerManager.WakeLock? = null
    private var shortLock: PowerManager.WakeLock? = null

    /** Ob der lange Wakelock gehalten wird. */
    val holding: Boolean get() = longLock?.isHeld == true

    /** Langer Wakelock, höchstens [millis] (Zeitlimit als Sicherung). */
    fun hold(millis: Long) {
        longLock = newLock("phasewake:record").apply { acquire(millis) }
    }

    /** Kurzer Wakelock für ein Bündel; löst sich spätestens nach [BATCH_MS], ein neues Bündel verlängert ihn. */
    fun batch() = short(BATCH_MS)

    /** Kurzer Wakelock, löst sich spätestens nach [millis]; ein neuer Aufruf verlängert ihn. */
    fun short(millis: Long) {
        val lock = shortLock ?: newLock("phasewake:record-short").apply { setReferenceCounted(false) }.also { shortLock = it }
        lock.acquire(millis)
    }

    /** Kurzen Wakelock lösen (Ende der Stichprobe). */
    fun releaseShort() {
        shortLock?.takeIf { it.isHeld }?.release()
    }

    /** Alle Wakelocks lösen (Ende der Aufzeichnung). */
    fun release() {
        longLock?.takeIf { it.isHeld }?.release()
        longLock = null
        shortLock?.takeIf { it.isHeld }?.release()
        shortLock = null
    }

    private fun newLock(tag: String): PowerManager.WakeLock =
        context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)

    companion object {
        /** Kurzer Wakelock je Bündel (Anhängen, Grenzen prüfen). */
        const val BATCH_MS = 10_000L
    }
}
