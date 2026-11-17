package com.phasewake.app.wear.wake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler

/**
 * Off-Body-Sensor der Einschlaf-Messung ([OnsetMonitor]): führt [times] und meldet jeden Wechsel über [onChange]
 * (`true`: nicht am Handgelenk). Ausgelagert aus OnsetMonitor.kt.
 */
internal class OffBodyWatch(private val context: Context, private val onChange: (off: Boolean) -> Unit) {
    /**
     * Zeiten nicht am Handgelenk (elapsedRealtime in ns, Ende `null` = noch nicht getragen), wie die Zeiten am
     * Ladegerät ([OnsetRules.charging]). Ohne Sensor oder ohne Meldung leer: Dann gilt nur die Ladeerkennung.
     */
    val times = mutableListOf<Pair<Long, Long?>>()
    private val sensorManager by lazy { context.getSystemService(SensorManager::class.java) }

    /** Aufweckender Off-Body-Sensor (stromsparend, meldet nur Wechsel), sonst der gewöhnliche; `null` ohne. */
    private val offBodySensor: Sensor? by lazy {
        sensorManager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
    }
    private val offBodyListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor?.type?.let { it != Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT } == true) return
            onOffBody(OnsetRules.offBody(event.values.firstOrNull()), event.timestamp)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    /** Anmelden (Meldungen über [handler]): Der Sensor meldet den Stand bei der Anmeldung und danach jeden Wechsel. */
    fun start(handler: Handler) {
        offBodySensor?.let {
            sensorManager?.registerListener(offBodyListener, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
    }

    /** Off-Body-Sensor: nicht am Handgelenk ([off]) ab [atNanos] bzw. wieder getragen. */
    private fun onOffBody(off: Boolean?, atNanos: Long) {
        if (off == null) return
        if (markAway(times, off, atNanos)) onChange(off)
    }

    /** Abmelden. */
    fun stop() {
        if (offBodySensor != null) sensorManager?.unregisterListener(offBodyListener)
    }
}

/**
 * Beginnt ([away]) oder beendet in [times] eine Zeit ohne Tragen bei [atNanos]; `true`, wenn sich etwas änderte.
 */
internal fun markAway(times: MutableList<Pair<Long, Long?>>, away: Boolean, atNanos: Long): Boolean {
    val open = times.lastOrNull()?.takeIf { it.second == null }
    if (away && open == null) times += atNanos to null
    if (!away && open != null) times[times.size - 1] = open.first to atNanos
    return away == (open == null)
}
