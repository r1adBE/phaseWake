package com.phasewake.app.wear.wake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
 *   bevorzugt den aufweckenden Sensor nutzen, der die Uhr nur bei vollem Puffer weckt. Hat die Uhr
 *   keinen solchen, wird wie im Fenster kurz gebündelt und die Uhr per Wakelock wach gehalten: Der
 *   gewöhnliche Sensor weckt sie nicht, sein Puffer liefe im Tiefschlaf über (Lücken, nie «eingeschlafen»).
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
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Nachfrist bis zum Abschliessen einer Epoche = Bündelungs-Verzögerung + 2 s (Regel, Abschnitt 7).
     * Im Stromsparmodus liefert der Sensor bis zu 5 Minuten gesammelt; früher abschliessen hiesse
     * diese Werte verwerfen. Wird in [start] nach dem gewählten Sensor gesetzt.
     */
    private var graceNanos = (MAX_LATENCY_US.toLong() + GRACE_EXTRA_US) * 1_000L

    /**
     * Pulswerte, die auf die Bewegung warten (Zeitstempel in ns, Schläge pro Minute), nach Zeit geordnet.
     *
     * Die Bewegung kommt gebündelt bis zu 10 s verspätet, der Puls fast sofort. Ginge ein Pulswert
     * gleich an den [EpochAssembler], schlösse er die vorige Epoche, und die nachkommende Bewegung
     * dieser Epoche fiele als «verspätet» weg. Darum gibt die Uhr einen Pulswert erst weiter, wenn
     * Bewegung mit gleichem oder späterem Zeitstempel da ist – oder nach der Nachfrist, damit kein
     * Puls verloren geht, wenn keine Bewegung mehr kommt.
     */
    private val pendingHeartRate = ArrayDeque<Pair<Long, Double>>()

    /** Zeitstempel des letzten weitergegebenen Bewegungswerts. */
    private var lastMotionNanos = Long.MIN_VALUE

    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            releaseHeartRateUntil(event.timestamp)
            lastMotionNanos = maxOf(lastMotionNanos, event.timestamp)
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
                queueHeartRate(point.timeDurationFromBoot.toNanos(), point.value)
            }
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            // Meldet Fehler (z. B. fehlendes Hintergrundrecht) asynchron; dann entscheidet die Bewegung allein.
            Log.w(TAG, "Puls nicht verfügbar, nur Bewegung", throwable)
            heartRateRegistered = false
        }
    }

    /** Schliesst Epochen ab, auch wenn der Sensor schweigt (Abschnitt 7 der Regel). */
    private val ticker = object : Runnable {
        override fun run() {
            val now = nowNanos()
            releaseHeartRateUntil(now - graceNanos)
            deliver(assembler.advance(now, graceNanos))
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    fun start() {
        if (running) return
        running = true
        val wakeUp = if (lowPower) sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) else null
        val accelerometer = wakeUp ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // Lange bündeln nur mit dem aufweckenden Sensor; sonst kurz bündeln und die Uhr wach halten.
        val latency = if (wakeUp != null) LOW_POWER_LATENCY_US else MAX_LATENCY_US
        graceNanos = (latency.toLong() + GRACE_EXTRA_US) * 1_000L
        if (accelerometer == null) {
            Log.w(TAG, "Kein Beschleunigungssensor")
        } else {
            if (lowPower && wakeUp == null) {
                Log.i(TAG, "Kein aufweckender Beschleunigungssensor: kurz bündeln mit Wakelock")
                wakeLock = context.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phasewake:onset")
                    .apply { acquire(FALLBACK_WAKE_LOCK_MS) }
            }
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
        pendingHeartRate.clear()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    /** Puls einreihen (nach Zeit geordnet); schon vorhandene Bewegung bis dahin gibt ihn sofort frei. */
    private fun queueHeartRate(timestampNanos: Long, bpm: Double) {
        var i = pendingHeartRate.size
        while (i > 0 && pendingHeartRate[i - 1].first > timestampNanos) i--
        pendingHeartRate.add(i, timestampNanos to bpm)
        releaseHeartRateUntil(lastMotionNanos)
    }

    /** Gibt alle wartenden Pulswerte bis [timestampNanos] (einschliesslich) an den [EpochAssembler]. */
    private fun releaseHeartRateUntil(timestampNanos: Long) {
        while (pendingHeartRate.isNotEmpty() && pendingHeartRate.first().first <= timestampNanos) {
            val (t, bpm) = pendingHeartRate.removeFirst()
            deliver(assembler.addHeartRate(t, bpm))
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

        /** Obergrenze des Wakelocks ohne aufweckenden Sensor: so lange darf «Gute Nacht» höchstens warten. */
        const val FALLBACK_WAKE_LOCK_MS = 16 * 60 * 60_000L
    }
}
