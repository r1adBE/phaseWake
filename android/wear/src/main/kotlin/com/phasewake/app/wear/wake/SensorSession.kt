package com.phasewake.app.wear.wake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import com.phasewake.app.core.Epoch
import com.phasewake.app.core.EpochAssembler

/**
 * Liest Beschleunigung und Puls während des Weckfensters und liefert fertige Epochen.
 *
 * Alles läuft auf dem Hauptthread: Sensor-Rückrufe, Puls-Rückrufe und der Zeitgeber. Damit gibt es
 * keine Nebenläufigkeit im [EpochAssembler]. Die Last ist gering (gebündelte Werte alle 10 s).
 *
 * @param startNanos Fensterbeginn auf der Uhr `SystemClock.elapsedRealtimeNanos()`.
 * @param lowPower Für die lange Einschlaf-Messung: nur Bewegung, Werte bis 5 Minuten bündeln und
 *   bevorzugt den aufweckenden Sensor nutzen, der die Uhr nur bei vollem Puffer weckt.
 * @param onEpochs Erhält abgeschlossene Epochen in zeitlicher Reihenfolge.
 */
class SensorSession(
    private val context: Context,
    startNanos: Long,
    private val nowNanos: () -> Long,
    private val lowPower: Boolean = false,
    private val onEpochs: (List<Epoch>) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val assembler = EpochAssembler(startNanos)
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val measureClient by lazy { HealthServices.getClient(context).measureClient }
    private var heartRateRegistered = false
    private var running = false

    /**
     * Nachfrist bis zum Abschliessen einer Epoche = Bündelungs-Verzögerung + 2 s (Regel, Abschnitt 7).
     * Im Stromsparmodus liefert der Sensor bis zu 5 Minuten gesammelt; früher abschliessen hiesse
     * diese Werte verwerfen.
     */
    private val graceNanos = ((if (lowPower) LOW_POWER_LATENCY_US else MAX_LATENCY_US).toLong() + GRACE_EXTRA_US) * 1_000L

    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            deliver(assembler.addMotion(event.timestamp, v[0].toDouble(), v[1].toDouble(), v[2].toDouble()))
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val heartRateCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            Log.d(TAG, "Puls verfügbar: $availability")
        }

        override fun onDataReceived(data: DataPointContainer) {
            for (point in data.getData(DataType.HEART_RATE_BPM)) {
                deliver(assembler.addHeartRate(point.timeDurationFromBoot.toNanos(), point.value))
            }
        }
    }

    /** Schliesst Epochen ab, auch wenn der Sensor schweigt (Abschnitt 7 der Regel). */
    private val ticker = object : Runnable {
        override fun run() {
            deliver(assembler.advance(nowNanos(), graceNanos))
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    fun start() {
        if (running) return
        running = true
        val accelerometer = (if (lowPower) sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) else null)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) {
            Log.w(TAG, "Kein Beschleunigungssensor")
        } else {
            val latency = if (lowPower) LOW_POWER_LATENCY_US else MAX_LATENCY_US
            sensorManager.registerListener(motionListener, accelerometer, SAMPLING_US, latency, handler)
        }
        if (!lowPower) registerHeartRate()
        handler.postDelayed(ticker, TICK_MILLIS)
    }

    private fun registerHeartRate() {
        try {
            measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, ContextCompat.getMainExecutor(context), heartRateCallback)
            heartRateRegistered = true
        } catch (e: SecurityException) {
            // Ohne Pulsberechtigung entscheidet die Bewegung allein (Regel, Abschnitt 3).
            Log.i(TAG, "Puls nicht erlaubt, nur Bewegung", e)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(ticker)
        sensorManager.unregisterListener(motionListener)
        if (heartRateRegistered) {
            measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, heartRateCallback)
            heartRateRegistered = false
        }
    }

    private fun deliver(epochs: List<Epoch>) {
        if (running && epochs.isNotEmpty()) onEpochs(epochs)
    }

    private companion object {
        const val TAG = "SensorSession"

        /** 25 Hz, wie in der Regel vorgesehen. */
        const val SAMPLING_US = 40_000

        /** Werte bis 10 s bündeln: spart Strom, Entscheidung kommt trotzdem rechtzeitig. */
        const val MAX_LATENCY_US = 10_000_000
        const val LOW_POWER_LATENCY_US = 300_000_000
        const val TICK_MILLIS = 10_000L
        const val GRACE_EXTRA_US = 2_000_000L
    }
}
