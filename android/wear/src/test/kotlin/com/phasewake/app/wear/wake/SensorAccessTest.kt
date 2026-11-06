package com.phasewake.app.wear.wake

import android.Manifest
import android.app.Application
import com.phasewake.app.core.DiagLog
import com.phasewake.app.shared.WatchPulse
import com.phasewake.app.wear.QuietWear
import java.time.Duration
import java.time.Instant
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Grund im Diagnose-Protokoll, wenn die Uhr nicht messen darf: Fehlerklasse, gekürzte Meldung und Sensorrechte; dazu
 * die Gründe, warum die Einschlaf-Messung nicht beginnt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class SensorAccessTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun refusalNamesClassMessageAndPermissions() {
        val access = SensorAccess(heartRate = true, background = false, activity = true, sdk = 36)
        assertEquals("hr=yes bg=no act=yes sdk=36", access.line())
        val error = SecurityException("Starting FGS with type health requires permissions")
        assertEquals(
            "SecurityException «Starting FGS with type health requires permissions» hr=yes bg=no act=yes sdk=36",
            SensorAccess.refusal(error, access),
        )
        assertEquals("RuntimeException «» hr=yes bg=no act=yes sdk=36", SensorAccess.refusal(RuntimeException(), access))
    }

    @Test
    fun messageIsShortAndWithoutProcessIds() {
        val raw = "Starting FGS callerApp=ProcessRecord{1a2b 4242:com.phasewake.app.test/u0a321} uid=10321 pid=4242\n«all» of"
        assertEquals("Starting FGS callerApp=… uid=… pid=… \"all\" of", SensorAccess.shortMessage(raw))
        val long = SensorAccess.shortMessage("x ".repeat(200))
        assertEquals(SensorAccess.MAX_MESSAGE, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("", SensorAccess.shortMessage(null))
        // Ganze Zeile mit der längsten Fehlerklasse passt ins Protokoll, die Rechte am Ende bleiben stehen.
        val access = SensorAccess(heartRate = false, background = false, activity = false, sdk = 36)
        val line = "auto onset not started: no sensors: " +
            SensorAccess.refusal(android.app.ForegroundServiceStartNotAllowedException("y".repeat(300)), access)
        assertTrue(line.length <= DiagLog.MAX_DETAIL, "${line.length} Zeichen")
    }

    @Test
    fun permissionNamesFollowTheSystemVersion() {
        assertEquals("android.permission.health.READ_HEART_RATE", SensorAccess.heartRatePermission(36))
        assertEquals(Manifest.permission.BODY_SENSORS, SensorAccess.heartRatePermission(35))
        assertEquals("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND", SensorAccess.backgroundPermission(36))
        assertEquals("android.permission.BODY_SENSORS_BACKGROUND", SensorAccess.backgroundPermission(33))
        assertNull(SensorAccess.backgroundPermission(32))
    }

    @Test
    fun readsGrantedPermissions() {
        val shadow = shadowOf(app)
        shadow.denyPermissions(Manifest.permission.BODY_SENSORS, "android.permission.BODY_SENSORS_BACKGROUND", Manifest.permission.ACTIVITY_RECOGNITION)
        assertEquals(SensorAccess(heartRate = false, background = false, activity = false, sdk = 35), SensorAccess.of(app))
        shadow.grantPermissions(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION)
        val foregroundOnly = SensorAccess.of(app)
        assertEquals(SensorAccess(heartRate = true, background = false, activity = true, sdk = 35), foregroundOnly)
        // Ans Handy: Puls ja, im Hintergrund nein – der Nacht-Check warnt.
        assertTrue(WatchPulse.warns(foregroundOnly.pulse()))
        shadow.grantPermissions("android.permission.BODY_SENSORS_BACKGROUND")
        assertFalse(WatchPulse.warns(SensorAccess.of(app).pulse()))
    }

    @Test
    fun notStartedReasons() {
        val since = Instant.parse("2026-11-05T21:00:00Z")
        val latest = since.plus(Duration.ofHours(9))
        fun reason(
            found: Boolean = true,
            duration: Boolean = true,
            auto: Boolean = true,
            at: Instant? = latest,
            begun: Boolean = false,
            done: Boolean = false,
        ) = OnsetRules.notStartedReason(found, duration, auto, since, at, begun, done)
        assertNull(reason())
        assertEquals("no alarm", reason(found = false))
        assertEquals("no sleep duration", reason(duration = false, auto = false))
        assertNull(reason(duration = false), "automatisch auch ohne Schlafdauer")
        assertEquals("no alarm time", reason(at = null))
        assertEquals("too far ahead", reason(at = since.plus(Duration.ofHours(17))))
        assertEquals("window begun", reason(begun = true))
        assertEquals("already done", reason(done = true))
        assertNull(reason(auto = false, begun = true, done = true), "«Gute Nacht» von Hand: Fenster und Erledigt zählen nicht")
    }
}
