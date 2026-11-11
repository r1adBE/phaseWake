package com.phasewake.app.ring

import android.content.Context
import android.content.Intent
import com.phasewake.app.QuietApp
import com.phasewake.app.R
import com.phasewake.app.core.Sunrise
import java.time.Duration
import java.time.Instant
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sonnenaufgang ab Weckfenster ([SunriseActivity]): geöffnet aus der Mitteilung (auch nach einem Neustart des Prozesses),
 * zeigt er Uhrzeit und Licht und macht den Bildschirm heller. «Licht aus» beendet ihn; klingelt der Wecker, übernimmt
 * der Weckbildschirm und dieser schliesst sich.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class SunriseScreenTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var screen: ActivityController<SunriseActivity>? = null
    private var service: ServiceController<PhoneRingService>? = null

    @Before
    fun pausedFrames() {
        ComposeTree.prepareFrames()
    }

    @After
    fun cleanUp() {
        screen?.pause()?.stop()?.destroy()
        service?.destroy()
        ComposeTree.drain()
        SunriseLight.stop(context)
    }

    /** Öffnet den Bildschirm wie die Mitteilung: Licht von vor fünf Minuten bis in fünf Minuten (halb hell). */
    private fun open(): ComposeTree {
        val now = Instant.now()
        val intent = Intent(context, SunriseActivity::class.java)
            .putExtra(PhoneRingService.EXTRA_ALARM_ID, "light")
            .putExtra(SunriseLight.EXTRA_START, now.minus(Duration.ofMinutes(5)).toEpochMilli())
            .putExtra(SunriseLight.EXTRA_END, now.plus(Duration.ofMinutes(5)).toEpochMilli())
        val controller = Robolectric.buildActivity(SunriseActivity::class.java, intent).setup()
        screen = controller
        return ComposeTree(controller.get()).also { it.idle(1_000) }
    }

    private val activity: SunriseActivity get() = screen!!.get()

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun lichtAusDerMitteilungBleibtBisLichtAus() {
        // Nach einem Neustart des Prozesses kennt nur die Mitteilung das Licht: Es wird wieder aufgenommen.
        assertNull(SunriseLight.active.value)
        val tree = open()
        assertEquals("light", SunriseLight.active.value?.alarmId)
        assertTrue(tree.shows(context.getString(R.string.sunrise_title)), "Titel fehlt: ${tree.texts()}")
        // Etwa halb hell: deutlich heller als zu Beginn, noch nicht voll.
        val brightness = activity.window.attributes.screenBrightness
        assertTrue(brightness > Sunrise.brightness(0.4f) && brightness < Sunrise.brightness(0.6f), "Helligkeit $brightness")
        tree.idle(3_000)
        assertFalse(activity.isFinishing)
        // «Licht aus»: Licht beendet, Bildschirm schliesst sich.
        tree.click(context.getString(R.string.sunrise_off))
        assertNull(SunriseLight.active.value)
        assertTrue(activity.isFinishing)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun weckerKlingeltDerWeckbildschirmUebernimmt() {
        val tree = open()
        assertFalse(activity.isFinishing)
        // Der Wecker klingelt: Dieser Bildschirm schliesst sich, das Licht läuft für den Weckbildschirm weiter.
        val ring = Intent(context, PhoneRingService::class.java)
            .setAction(PhoneRingService.ACTION_RING)
            .putExtra(PhoneRingService.EXTRA_ALARM_ID, "light")
        service = Robolectric.buildService(PhoneRingService::class.java, ring).create().startCommand(0, 1)
        tree.idle()
        assertTrue(PhoneRingService.ringState.value is PhoneRingService.State.Ringing)
        assertTrue(activity.isFinishing)
        assertEquals("light", SunriseLight.active.value?.alarmId)
    }
}
