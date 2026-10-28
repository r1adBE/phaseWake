package com.phasewake.app.wear.wake

import android.content.Context
import android.os.SystemClock
import com.phasewake.app.core.SleepOnset
import java.time.Duration
import java.time.Instant

/**
 * Wartet nach «Gute Nacht» stromsparend auf das Einschlafen (Regel, Abschnitt 6) und meldet die
 * Einschlafzeit genau einmal.
 *
 * @param giveUpAt Ab dann wird nicht mehr gewartet: [onGiveUp] statt [onOnset]. Geprüft nach Uhrzeit
 *   bei jeder Lieferung des Sensors, denn ein Zeitgeber steht im Tiefschlaf der Uhr still.
 */
class OnsetMonitor(
    private val context: Context,
    private val threshold: Double,
    private val giveUpAt: Instant,
    private val onGiveUp: () -> Unit,
    private val onOnset: (Instant) -> Unit,
) {
    private val started = Instant.now()
    private val motions = mutableListOf<Double?>()
    private var session: SensorSession? = null
    private var reported = false

    fun start() {
        session = SensorSession(context, SystemClock.elapsedRealtimeNanos(), SystemClock::elapsedRealtimeNanos, lowPower = true) { epochs ->
            if (reported) return@SensorSession
            if (!Instant.now().isBefore(giveUpAt)) {
                reported = true
                onGiveUp()
                return@SensorSession
            }
            epochs.forEach { motions += it.motion }
            val index = SleepOnset.find(motions, threshold) ?: return@SensorSession
            reported = true
            onOnset(started.plus(Duration.ofSeconds(index.toLong() * EPOCH_SECONDS)))
        }.also { it.start() }
    }

    fun stop() {
        session?.stop()
        session = null
    }

    private companion object {
        const val EPOCH_SECONDS = 30L
    }
}
