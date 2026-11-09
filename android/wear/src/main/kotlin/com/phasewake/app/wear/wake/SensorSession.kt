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
 * Puls ([heartRateSource]): zuerst der Pulssensor der Uhr über den [SensorManager] (etwa jede Sekunde, gebündelt wie
 * die Bewegung), sonst die Health Services ([MeasureClient][androidx.health.services.client.MeasureClient]). Diese
 * liefern bei dunklem Bildschirm nur etwa alle 10 Minuten (Galaxy Watch, Befund 08./09.11.2026): Sie sind für die
 * Messung in der sichtbaren App gedacht. Den Sensor hält der Vordergrunddienst («health») mit seinem Wakelock wach.
 *
 * @param startNanos Fensterbeginn auf der Uhr `SystemClock.elapsedRealtimeNanos()`.
 * @param lowPower Für die Einschlaf-Messung ([OnsetMonitor]): nur Bewegung. Mit aufweckendem Sensor, dessen Puffer
 *   mindestens [MIN_WAKE_UP_FIFO] Werte fasst ([continuous]), bündelt er bis 5 Minuten und weckt die Uhr nur bei
 *   vollem Puffer. Sonst misst [OnsetMonitor] in Stichproben ([pause], [resume]) und hält dafür selbst einen kurzen
 *   Wakelock: Die Sitzung hält nie einen Wakelock (früher bis 16 Std., Akku-Befund 03.11.2026).
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
    private var heartRateSensorRegistered = false

    /** Pulssensor der Uhr (bevorzugt der aufweckende); `null` in [lowPower] oder ohne Sensor. */
    private val heartRateSensor: Sensor? = if (lowPower) {
        null
    } else {
        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true) ?: sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    }

    /**
     * Woher der Puls kommt, fürs Diagnose-Protokoll: «sensor» ([SensorManager]), «measure» (Health Services, Rückfall)
     * oder «none» (keine Pulsmessung: [lowPower], kein Recht, kein Sensor). Gilt ab [start].
     */
    var heartRateSource: String = SOURCE_NONE
        private set
    private var running = false
    private var listening = false

    /** Aufweckender Beschleunigungssensor (nur [lowPower]), oder `null`. */
    private val wakeUpSensor = if (lowPower) sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) else null

    /** Gewählter Beschleunigungssensor, oder `null`, wenn die Uhr keinen hat. */
    private val accelerometer: Sensor? = wakeUpSensor?.takeIf { it.fifoMaxEventCount >= MIN_WAKE_UP_FIFO }
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /**
     * Ob die Sitzung ohne Wakelock durchgehend messen kann: aufweckender Sensor mit grossem Puffer (nur [lowPower]).
     * Ohne ihn liefe der Puffer des gewöhnlichen Sensors im Tiefschlaf über; ein kleiner Puffer weckte die Uhr fast
     * dauernd. Dann misst [OnsetMonitor] in Stichproben.
     */
    val continuous: Boolean get() = accelerometer != null && accelerometer === wakeUpSensor

    /** Puffergrösse des aufweckenden Sensors (für das Diagnose-Protokoll); -1 ohne solchen Sensor. */
    val wakeUpFifo: Int get() = wakeUpSensor?.fifoMaxEventCount ?: -1

    /** Bisher erhaltene Bewegungswerte (für das Diagnose-Protokoll). */
    var samples: Long = 0L
        private set

    /** Anzahl abgeschlossener Epochen; die nächste gelieferte Epoche hat diesen Index. */
    val closedEpochs: Long get() = assembler.currentIndex

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
            samples++
            releaseHeartRateUntil(event.timestamp)
            lastMotionNanos = maxOf(lastMotionNanos, event.timestamp)
            deliver(assembler.addMotion(event.timestamp, v[0].toDouble(), v[1].toDouble(), v[2].toDouble()))
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val heartRateListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            heartRateValue(event.accuracy, event.values.firstOrNull())?.let { queueHeartRate(event.timestamp, it) }
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
            heartRateSource = SOURCE_NONE
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

    /**
     * Startet die Messung. [listen] `false`: Sensor erst mit [resume] anmelden (Stichproben des [OnsetMonitor]).
     */
    fun start(listen: Boolean = true) {
        if (running) return
        running = true
        graceNanos = (latencyUs().toLong() + GRACE_EXTRA_US) * 1_000L
        if (accelerometer == null) Log.w(TAG, "Kein Beschleunigungssensor")
        if (listen) resume()
        if (!lowPower) registerHeartRate()
        handler.postDelayed(ticker, TICK_MILLIS)
    }

    /** Meldet den Bewegungssensor (wieder) an; für die Stichproben des [OnsetMonitor]. */
    fun resume() {
        val sensor = accelerometer ?: return
        if (!running || listening) return
        listening = sensorManager.registerListener(motionListener, sensor, SAMPLING_US, latencyUs(), handler)
    }

    /** Meldet den Bewegungssensor ab, ohne die Epochen zu verlieren; [resume] misst weiter. */
    fun pause() {
        if (!listening) return
        listening = false
        sensorManager.unregisterListener(motionListener)
    }

    /** Schliesst alle Epochen ab, die bis [nanos] enden (Ende einer Stichprobe), und liefert sie. */
    fun closeUntil(nanos: Long) {
        if (running) deliver(assembler.advance(nanos, 0L))
    }

    /** Bündelung: lang durchgehend, gar nicht in Stichproben (sonst kämen Werte erst nach deren Ende), sonst 10 s. */
    private fun latencyUs(): Int = when {
        continuous -> LOW_POWER_LATENCY_US
        lowPower -> 0
        else -> MAX_LATENCY_US
    }

    /** Puls über den Sensor der Uhr; fehlt er oder lässt er sich nicht anmelden, über die Health Services. */
    private fun registerHeartRate() {
        heartRateSensor?.let { sensor ->
            heartRateSensorRegistered = try {
                sensorManager.registerListener(heartRateListener, sensor, HEART_RATE_SAMPLING_US, MAX_LATENCY_US, handler)
            } catch (e: SecurityException) {
                Log.i(TAG, "Pulssensor nicht erlaubt, versuche Health Services", e)
                false
            }
            if (heartRateSensorRegistered) {
                heartRateSource = SOURCE_SENSOR
                return
            }
            Log.w(TAG, "Pulssensor nicht angemeldet, versuche Health Services")
        }
        try {
            measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, ContextCompat.getMainExecutor(context), heartRateCallback)
            heartRateRegistered = true
            heartRateSource = SOURCE_MEASURE
        } catch (e: SecurityException) {
            // Ohne Pulsberechtigung entscheidet die Bewegung allein (Regel, Abschnitt 3).
            Log.i(TAG, "Puls nicht erlaubt, nur Bewegung", e)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(ticker)
        listening = false
        sensorManager.unregisterListener(motionListener)
        if (heartRateSensorRegistered) {
            sensorManager.unregisterListener(heartRateListener)
            heartRateSensorRegistered = false
        }
        if (heartRateRegistered) {
            measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, heartRateCallback)
            heartRateRegistered = false
        }
        pendingHeartRate.clear()
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

    companion object {
        private const val TAG = "SensorSession"

        const val SOURCE_SENSOR = "sensor"
        const val SOURCE_MEASURE = "measure"
        const val SOURCE_NONE = "none"

        /**
         * Pulswert eines Sensor-Ereignisses, oder `null`, wenn er nichts taugt: Genauigkeit «unzuverlässig» oder «kein
         * Hautkontakt» (Uhr nicht am Handgelenk) oder kein positiver Wert (ohne Kontakt meldet die Uhr 0).
         */
        internal fun heartRateValue(accuracy: Int, bpm: Float?): Double? = when {
            accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT -> null
            bpm == null || bpm.isNaN() || bpm <= 0f -> null
            else -> bpm.toDouble()
        }

        /** Puls etwa jede Sekunde (ein Wunsch an den Sensor; er meldet bei Änderung). */
        private const val HEART_RATE_SAMPLING_US = 1_000_000

        /**
         * Kleinster Puffer des aufweckenden Sensors für die durchgehende Messung: 60 s bei 25 Hz. Ein kleinerer weckte
         * die Uhr öfter als jede Minute (ohne Puffer bei jedem Wert); dann misst [OnsetMonitor] in Stichproben.
         */
        const val MIN_WAKE_UP_FIFO = 1_500

        /** 25 Hz, wie in der Regel vorgesehen. */
        private const val SAMPLING_US = 40_000

        /** Werte bis 10 s bündeln: spart Strom, Entscheidung kommt trotzdem rechtzeitig. */
        private const val MAX_LATENCY_US = 10_000_000
        private const val LOW_POWER_LATENCY_US = 300_000_000
        private const val TICK_MILLIS = 10_000L
        private const val GRACE_EXTRA_US = 2_000_000L
    }
}
