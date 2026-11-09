package com.phasewake.app.wear.wake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.phasewake.app.core.Epoch
import com.phasewake.app.wear.QuietWear
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorBuilder
import org.robolectric.shadows.SensorEventBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Puls im Weckfenster und in «Nacht aufzeichnen» (Befund 08./09.11.2026: Health Services lieferten bei dunklem
 * Bildschirm nur etwa alle 10 Min): zuerst der Pulssensor der Uhr, untaugliche Werte verworfen, Rückfall auf die
 * Health Services ohne Sensor oder wenn er sich nicht anmelden lässt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class SensorSessionHeartRateTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val sensors get() = shadowOf(context.getSystemService(SensorManager::class.java))
    private val epochs = mutableListOf<Epoch>()
    private var session: SensorSession? = null

    private val accelerometer = SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).build()

    @After
    fun tearDown() {
        session?.stop()
    }

    private fun heartRateSensor(wakeUp: Boolean = false): Sensor =
        SensorBuilder.newBuilder().setType(Sensor.TYPE_HEART_RATE).setWakeUpFlag(wakeUp).build()

    /** Sitzung ab Zeit 0 (Sensoruhr), «jetzt» bleibt bei 0: Epochen schliessen nur die Werte selbst. */
    private fun start(lowPower: Boolean = false): SensorSession =
        SensorSession(context, 0L, { 0L }, lowPower = lowPower) { epochs += it }.also {
            session = it
            it.start()
        }

    private fun heartRate(sensor: Sensor, seconds: Long, bpm: Float, accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH) =
        sensors.sendSensorEventToListeners(
            SensorEventBuilder.newBuilder(sensor, floatArrayOf(bpm)).setTimestamp(seconds * 1_000_000_000L).setAccuracy(accuracy).build(),
            sensor,
        )

    private fun motion(seconds: Long) = sensors.sendSensorEventToListeners(
        SensorEventBuilder.newBuilder(accelerometer, floatArrayOf(0f, 0f, 9.81f)).setTimestamp(seconds * 1_000_000_000L).build(),
        accelerometer,
    )

    @Test
    fun unusableValuesAreDropped() {
        assertEquals(62.0, SensorSession.heartRateValue(SensorManager.SENSOR_STATUS_ACCURACY_HIGH, 62f))
        assertEquals(58.0, SensorSession.heartRateValue(SensorManager.SENSOR_STATUS_ACCURACY_LOW, 58f))
        assertNull(SensorSession.heartRateValue(SensorManager.SENSOR_STATUS_UNRELIABLE, 60f))
        assertNull(SensorSession.heartRateValue(SensorManager.SENSOR_STATUS_NO_CONTACT, 60f))
        assertNull(SensorSession.heartRateValue(SensorManager.SENSOR_STATUS_ACCURACY_HIGH, 0f))
        assertNull(SensorSession.heartRateValue(SensorManager.SENSOR_STATUS_ACCURACY_HIGH, Float.NaN))
        assertNull(SensorSession.heartRateValue(SensorManager.SENSOR_STATUS_ACCURACY_HIGH, null))
    }

    @Test
    fun sensorGivesHeartRateInEveryEpoch() {
        sensors.addSensor(accelerometer)
        val hr = heartRateSensor()
        sensors.addSensor(hr)
        val s = start()
        assertEquals(SensorSession.SOURCE_SENSOR, s.heartRateSource)
        assertTrue(sensors.listeners.any { sensors.hasListener(it, hr) })

        // Epoche 0: gültige Werte und ein Wert ohne Hautkontakt; Epoche 1: nur untaugliche Werte; Epoche 2: einer.
        heartRate(hr, 5, 60f)
        heartRate(hr, 10, 64f)
        heartRate(hr, 15, 90f, SensorManager.SENSOR_STATUS_NO_CONTACT)
        heartRate(hr, 35, 0f)
        heartRate(hr, 40, 70f, SensorManager.SENSOR_STATUS_UNRELIABLE)
        heartRate(hr, 65, 55f)
        // Bewegung bis in Epoche 3 gibt die Pulswerte frei und schliesst die Epochen 0 bis 2.
        listOf(1L, 20L, 31L, 50L, 61L, 80L, 91L).forEach(::motion)

        assertEquals(listOf(62.0, null, 55.0), epochs.map { it.heartRate })
    }

    @Test
    fun wakeUpSensorIsPreferred() {
        sensors.addSensor(accelerometer)
        val plain = heartRateSensor(wakeUp = false)
        val wakeUp = heartRateSensor(wakeUp = true)
        sensors.addSensor(plain)
        sensors.addSensor(wakeUp)
        val s = start()
        assertEquals(SensorSession.SOURCE_SENSOR, s.heartRateSource)
        assertTrue(sensors.listeners.any { sensors.hasListener(it, wakeUp) })
        assertFalse(sensors.listeners.any { sensors.hasListener(it, plain) })
    }

    @Test
    fun withoutSensorFallsBackToHealthServices() {
        sensors.addSensor(accelerometer)
        val s = start()
        assertNotEquals(SensorSession.SOURCE_SENSOR, s.heartRateSource)
        assertEquals(1, sensors.listeners.size, "nur die Bewegung")
    }

    @Test
    fun failedRegistrationFallsBackToHealthServices() {
        sensors.addSensor(accelerometer)
        val hr = heartRateSensor()
        sensors.addSensor(hr)
        sensors.setForceListenersToFail(true)
        val s = start()
        assertNotEquals(SensorSession.SOURCE_SENSOR, s.heartRateSource)
        assertFalse(sensors.listeners.any { sensors.hasListener(it, hr) })
    }

    @Test
    fun stopUnregistersTheHeartRateSensor() {
        sensors.addSensor(accelerometer)
        val hr = heartRateSensor()
        sensors.addSensor(hr)
        val s = start()
        s.stop()
        session = null
        assertTrue(sensors.listeners.isEmpty())
    }

    @Test
    fun lowPowerMeasuresNoHeartRate() {
        sensors.addSensor(accelerometer)
        val hr = heartRateSensor()
        sensors.addSensor(hr)
        val s = start(lowPower = true)
        assertEquals(SensorSession.SOURCE_NONE, s.heartRateSource)
        assertFalse(sensors.listeners.any { sensors.hasListener(it, hr) })
    }
}
