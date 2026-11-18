package com.phasewake.app.wear.wake

import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import com.phasewake.app.wear.QuietWear
import java.time.Duration
import java.time.Instant
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Sonnenaufgang der Uhr: Display ein vor der Vollbild-Mitteilung, nach 2 s ohne sichtbaren Bildschirm einmal neu, beim
 * Ende Display frei und Mitteilung weg.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietWear::class)
class WatchSunriseTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val now = Instant.ofEpochMilli(System.currentTimeMillis())
    private val active = WatchSunrise.Active("a", now, now.plus(Duration.ofMinutes(30)))

    @After
    fun cleanUp() {
        WatchSunrise.shown = false
        WatchSunrise.stop(app)
    }

    @Test
    fun startWakesTheScreenAndRepostsWhenNotShown() {
        WatchSunrise.start(app, active, "7:00")
        assertTrue(WatchSunrise.wakeHeld, "Display-Wakelock beim Start")
        val first = assertNotNull(manager.getNotification(Notifications.ID_SUNRISE))
        assertNotNull(first.fullScreenIntent)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RingScreenWake.RETRY_MS))
        val again = assertNotNull(manager.getNotification(Notifications.ID_SUNRISE))
        assertNotSame(first, again, "neue Mitteilung statt Aktualisierung")
        WatchSunrise.stop(app)
        assertFalse(WatchSunrise.wakeHeld, "beim Ende darf das Display ausgehen")
        assertNull(manager.getNotification(Notifications.ID_SUNRISE))
    }

    @Test
    fun noRepostWhenShown() {
        WatchSunrise.start(app, active, "7:00")
        val first = assertNotNull(manager.getNotification(Notifications.ID_SUNRISE))
        WatchSunrise.shown = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RingScreenWake.RETRY_MS))
        assertSame(first, manager.getNotification(Notifications.ID_SUNRISE))
    }
}
