package com.phasewake.app.ui.history

import android.content.Context
import com.phasewake.app.QuietApp
import com.phasewake.app.R
import com.phasewake.app.core.BatteryMark
import com.phasewake.app.core.BatteryPoint
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.WakeReason
import com.phasewake.app.shared.BatteryNight
import com.phasewake.app.sync.PhoneBattery
import java.time.Duration
import java.time.Instant
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Akku je Nacht (r1AD 14.11.2026): die Zeile der Detailseite (nur bekannte Teile, ohne Wert keine Zeile) und wie das Handy
 * seinen Akku der Nacht der Uhr zuordnet. Wie `BatteryLinePhoneTests` (iPhone).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class BatteryLineTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val t0: Instant = Instant.parse("2026-11-13T21:00:00Z")

    private fun at(minute: Long): Instant = t0.plus(Duration.ofMinutes(minute))

    private fun track(from: Int, to: Int, plugged: Boolean = false, start: Long = 0, end: Long = 600) =
        listOf(BatteryPoint(at(start), from, plugged, BatteryMark.START), BatteryPoint(at(end), to, plugged, BatteryMark.END))

    private fun night(watch: List<BatteryPoint>? = null, phone: List<BatteryPoint>? = null) =
        NightRecord("a", at(570), at(600), at(590), WakeReason.LIGHT_SLEEP, 0, emptyList(), watchBattery = watch, phoneBattery = phone)

    @Test
    fun bothParts() {
        val text = batteryLine(context, night(track(80, 66), track(50, 47)))
        assertEquals(context.getString(R.string.battery_night, context.getString(R.string.battery_watch, 14) + " · " + context.getString(R.string.battery_phone, 3)), text)
        assertEquals("Battery: Watch −14 % · Phone −3 %", text)
    }

    @Test
    fun onlyKnownParts() {
        assertEquals("Battery: Watch −14 %", batteryLine(context, night(track(80, 66))))
        // Handy die ganze Nacht am Ladegerät: kein Wert, nur die Uhr.
        assertEquals("Battery: Watch −14 %", batteryLine(context, night(track(80, 66), track(50, 90, plugged = true))))
        assertEquals("Battery: Phone −3 %", batteryLine(context, night(phone = track(50, 47))))
    }

    @Test
    fun noValueNoLine() {
        assertNull(batteryLine(context, night()))
        // Anstieg ohne Ladegerät: unbemerkt geladen, kein Wert.
        assertNull(batteryLine(context, night(track(50, 60), track(50, 90, plugged = true))))
    }

    @Test
    fun phoneTrackJoinsItsNight() {
        // Beim «stop» beendet und gemerkt: kommt zur passenden Nacht, nicht zu einer anderen.
        BatteryNight.keepDone(context, track(50, 47, end = 595))
        val other = night().copy(alarmId = "b", wokeAt = at(700))
        assertNull(PhoneBattery.withPhone(context, other, at(701)).phoneBattery)
        assertEquals(track(50, 47, end = 595), PhoneBattery.withPhone(context, night(), at(596)).phoneBattery)
        // Ohne gemerkten Akku und spät angekommen: nicht jetzt beenden.
        BatteryNight.begin(context, BatteryNight.Reading(at(0), 60, false))
        assertNull(PhoneBattery.withPhone(context, night(), at(700)).phoneBattery)
        // Frisch angekommen: jetzt beendet (hier ohne Akkustand des Systems: unbekannt, keine Punkte).
        assertNull(PhoneBattery.withPhone(context, night(), at(592)).phoneBattery)
        // Schon mit Akku des Handys: bleibt.
        val kept = night(phone = track(40, 38))
        assertEquals(kept, PhoneBattery.withPhone(context, kept, at(592)))
    }
}
