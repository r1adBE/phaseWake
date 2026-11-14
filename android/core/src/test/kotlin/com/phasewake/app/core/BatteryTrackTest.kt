package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Akkuverbrauch je Nacht: nur Teilstrecken ohne Ladegerät, unbekannt statt falsch, Format alt/neu. Gleich in Swift. */
class BatteryTrackTest {
    private val t0: Instant = Instant.parse("2026-11-13T21:00:00Z")

    private fun at(minute: Long): Instant = t0.plus(Duration.ofMinutes(minute))

    private fun p(minute: Long, percent: Int, plugged: Boolean, mark: BatteryMark) = BatteryPoint(at(minute), percent, plugged, mark)

    @Test
    fun usedWithoutCharging() {
        val track = listOf(p(0, 80, false, BatteryMark.START), p(570, 71, false, BatteryMark.SAMPLE), p(600, 66, false, BatteryMark.END))
        assertEquals(14, BatteryTrack.used(track))
    }

    @Test
    fun chargingInBetweenIsLeftOut() {
        // 80 → 70 ohne Laden, geladen bis 95, dann 95 → 90: 10 + 5.
        val track = listOf(
            p(0, 80, false, BatteryMark.START),
            p(120, 70, true, BatteryMark.PLUG),
            p(240, 95, false, BatteryMark.UNPLUG),
            p(600, 90, false, BatteryMark.END),
        )
        assertEquals(15, BatteryTrack.used(track))
        // Am Ladegerät begonnen bzw. beendet: nur die Zeit ohne.
        assertEquals(5, BatteryTrack.used(listOf(p(0, 60, true, BatteryMark.START), p(240, 95, false, BatteryMark.UNPLUG), p(600, 90, false, BatteryMark.END))))
        assertEquals(10, BatteryTrack.used(listOf(p(0, 80, false, BatteryMark.START), p(120, 70, true, BatteryMark.PLUG), p(600, 100, true, BatteryMark.END))))
    }

    @Test
    fun unknownGivesNoValue() {
        assertNull(BatteryTrack.used(null))
        assertNull(BatteryTrack.used(emptyList()))
        assertNull(BatteryTrack.used(listOf(p(0, 80, false, BatteryMark.START))))
        // Ohne Ende, ohne Beginn.
        assertNull(BatteryTrack.used(listOf(p(0, 80, false, BatteryMark.START), p(10, 79, false, BatteryMark.SAMPLE))))
        assertNull(BatteryTrack.used(listOf(p(0, 80, false, BatteryMark.SAMPLE), p(10, 79, false, BatteryMark.END))))
        // Wechsel ans Ladegerät nicht beobachtet (erst am Ende bzw. bei einem Zwischenstand gesehen).
        assertNull(BatteryTrack.used(listOf(p(0, 80, false, BatteryMark.START), p(600, 90, true, BatteryMark.END))))
        assertNull(BatteryTrack.used(listOf(p(0, 80, true, BatteryMark.START), p(300, 99, false, BatteryMark.SAMPLE), p(600, 90, false, BatteryMark.END))))
        // Ladebeginn gesehen, Ladeende nicht.
        assertNull(BatteryTrack.used(listOf(p(0, 80, false, BatteryMark.START), p(120, 70, true, BatteryMark.PLUG), p(600, 90, false, BatteryMark.END))))
        // Anstieg ohne Ladegerät: unbemerkt geladen.
        assertNull(BatteryTrack.used(listOf(p(0, 50, false, BatteryMark.START), p(600, 55, false, BatteryMark.END))))
        // Die ganze Nacht am Ladegerät: kein Verbrauch messbar.
        assertNull(BatteryTrack.used(listOf(p(0, 50, true, BatteryMark.START), p(600, 100, true, BatteryMark.END))))
        // Zeit rückwärts, unmögliche Werte.
        assertNull(BatteryTrack.used(listOf(p(10, 80, false, BatteryMark.START), p(0, 79, false, BatteryMark.END))))
        assertNull(BatteryTrack.used(listOf(p(0, 180, false, BatteryMark.START), p(10, 79, false, BatteryMark.END))))
        assertNull(BatteryTrack.used(listOf(p(0, 80, false, BatteryMark.START), p(5, 80, false, BatteryMark.PLUG), p(10, 79, false, BatteryMark.END))))
        assertNull(BatteryTrack.used(listOf(p(0, 80, false, BatteryMark.START), p(5, 80, false, BatteryMark.START), p(10, 79, false, BatteryMark.END))))
    }

    @Test
    fun lifecycle() {
        var open = BatteryTrack.begin(emptyList(), at(0), 80, false)
        assertEquals(1, open.size)
        assertTrue(BatteryTrack.isOpen(open, at(1)))
        // Zweiter Beginn derselben Nacht (Weckfenster nach der Einschlaf-Messung): Zwischenstand, früher Beginn bleibt.
        open = BatteryTrack.begin(open, at(570), 72, false)
        assertEquals(listOf(BatteryMark.START, BatteryMark.SAMPLE), open.map { it.mark })
        // Ladegerät: nur Wechsel zählen.
        assertEquals(open, BatteryTrack.charge(open, at(575), 72, false))
        open = BatteryTrack.charge(open, at(580), 71, true)
        open = BatteryTrack.charge(open, at(585), 73, false)
        assertEquals(BatteryMark.UNPLUG, open.last().mark)
        val track = BatteryTrack.end(open, at(600), 70, false)!!
        assertEquals(BatteryMark.END, track.last().mark)
        assertEquals(8 + 1 + 3, BatteryTrack.used(track))
        // Beendet: nichts läuft mehr.
        assertFalse(BatteryTrack.isOpen(track, at(601)))
        assertNull(BatteryTrack.end(track, at(601), 70, false))
        assertEquals(track, BatteryTrack.sample(track, at(601), 70, false))
    }

    @Test
    fun orphanedNightIsReplaced() {
        val open = BatteryTrack.begin(emptyList(), at(0), 80, false)
        val later = at(0).plus(BatteryTrack.MAX_SPAN).plusSeconds(1)
        assertFalse(BatteryTrack.isOpen(open, later))
        assertNull(BatteryTrack.end(open, later, 50, false))
        assertEquals(listOf(BatteryPoint(later, 50, false, BatteryMark.START)), BatteryTrack.begin(open, later, 50, false))
        // Uhr zurückgestellt: neu beginnen.
        assertFalse(BatteryTrack.isOpen(open, at(-1)))
    }

    @Test
    fun samplesAreCapped() {
        var open = BatteryTrack.begin(emptyList(), at(0), 90, false)
        repeat(BatteryTrack.MAX_SAMPLES + 5) { open = BatteryTrack.sample(open, at(it + 1L), 90, false) }
        assertEquals(BatteryTrack.MAX_SAMPLES + 1, open.size)
        // Ladewechsel zählen trotzdem.
        assertEquals(BatteryMark.PLUG, BatteryTrack.charge(open, at(100), 89, true).last().mark)
    }

    @Test
    fun formatRoundTrip() {
        val track = listOf(p(0, 80, false, BatteryMark.START), p(120, 70, true, BatteryMark.PLUG), p(240, 95, false, BatteryMark.UNPLUG), p(600, 90, false, BatteryMark.END))
        val text = BatteryTrack.encode(track)
        // Byte-gleich mit Swift (BatteryTrackTests.testFormatRoundTrip).
        assertEquals("s,1794603600000,80,0;c,1794610800000,70,1;u,1794618000000,95,0;e,1794639600000,90,0", text)
        assertEquals(track, BatteryTrack.decode(text))
        assertNull(BatteryTrack.decode(null))
        assertNull(BatteryTrack.decode(""))
        assertNull(BatteryTrack.decode("x,1,80,0"))
        assertNull(BatteryTrack.decode("s,1,101,0"))
        assertNull(BatteryTrack.decode("s,-1,80,0"))
        assertNull(BatteryTrack.decode("s,1,80,2"))
        assertNull(BatteryTrack.decode("s,1,80"))
        assertNull(BatteryTrack.decode("c,1,80,0"))
        assertNull(BatteryTrack.decode(List(BatteryTrack.MAX_POINTS + 1) { "m,1,80,0" }.joinToString(";")))
    }

    @Test
    fun belongsToAndArrival() {
        val night = NightRecord("a", at(570), at(600), at(590), WakeReason.LIGHT_SLEEP, 0, emptyList())
        val track = listOf(p(0, 80, false, BatteryMark.START), p(595, 70, false, BatteryMark.END))
        assertTrue(BatteryTrack.belongsTo(track, night))
        assertFalse(BatteryTrack.belongsTo(listOf(p(591, 80, false, BatteryMark.START), p(595, 70, false, BatteryMark.END)), night))
        assertFalse(BatteryTrack.belongsTo(listOf(p(0, 80, false, BatteryMark.START), p(589, 70, false, BatteryMark.END)), night))
        assertFalse(BatteryTrack.belongsTo(emptyList(), night))
        // Ende der Uhr-Messung, sonst das erste Wecken.
        assertTrue(BatteryTrack.arrivedFresh(night, at(594)))
        assertFalse(BatteryTrack.arrivedFresh(night, at(596)))
        val withWatch = night.copy(watchBattery = listOf(p(0, 90, false, BatteryMark.START), p(610, 70, false, BatteryMark.END)))
        assertTrue(BatteryTrack.arrivedFresh(withWatch, at(614)))
        assertFalse(BatteryTrack.arrivedFresh(withWatch, at(616)))
    }

    @Test
    fun describeForDiagnosis() {
        assertEquals("start 80% unplugged", BatteryTrack.describe(p(0, 80, false, BatteryMark.START)))
        assertEquals("charge start 70% plugged", BatteryTrack.describe(p(0, 70, true, BatteryMark.PLUG)))
        assertEquals("charge end 95% unplugged", BatteryTrack.describe(p(0, 95, false, BatteryMark.UNPLUG)))
        assertEquals("sample 75% unplugged", BatteryTrack.describe(p(0, 75, false, BatteryMark.SAMPLE)))
        assertEquals("end 66% unplugged", BatteryTrack.describe(p(0, 66, false, BatteryMark.END)))
    }

    @Test
    fun nightCodecOldAndNew() {
        val watch = listOf(p(0, 80, false, BatteryMark.START), p(600, 66, false, BatteryMark.END))
        val phone = listOf(p(1, 50, false, BatteryMark.START), p(601, 47, false, BatteryMark.END))
        val night = NightRecord("a", Instant.ofEpochMilli(0), Instant.ofEpochMilli(1_800_000), Instant.ofEpochMilli(1_080_000), WakeReason.AWAKE, 0, emptyList())
        // Ohne Akku unverändert.
        assertEquals("phasewake-night 1\na|0|1800000|1080000|AWAKE|0|\n\n", Codec.encodeNight(night))
        // Mit Akku: Feld 8 leer (bekannter Grund), Feld 9 Uhr, Feld 10 Handy. Byte-gleich mit Swift.
        val both = night.copy(watchBattery = watch, phoneBattery = phone)
        val text = Codec.encodeNight(both)
        assertEquals(
            "phasewake-night 1\na|0|1800000|1080000|AWAKE|0||" +
                "|s,1794603600000,80,0;e,1794639600000,66,0|s,1794603660000,50,0;e,1794639660000,47,0\n\n",
            text,
        )
        assertEquals(both, Codec.decodeNight(text))
        // Nur Handy: Feld 9 leer. Neuer Grund bleibt in Feld 8.
        val phoneOnly = night.copy(reason = WakeReason.EXACT, phoneBattery = phone)
        assertEquals("phasewake-night 1\na|0|1800000|1080000|DEADLINE|0||EXACT||s,1794603660000,50,0;e,1794639660000,47,0\n\n", Codec.encodeNight(phoneOnly))
        assertEquals(phoneOnly, Codec.decodeNight(Codec.encodeNight(phoneOnly)))
        val watchOnly = night.copy(watchBattery = watch)
        assertEquals(watchOnly, Codec.decodeNight(Codec.encodeNight(watchOnly)))
        // Wie eine ältere Version liest: die ersten 8 Felder, leeres Feld 8 = Grund aus Feld 5.
        val fields = text.lines()[1].split("|")
        assertEquals("AWAKE", fields[4])
        assertEquals("", fields[7])
        // Ältere Nacht ohne Akku-Felder, kaputtes Akku-Feld: Nacht bleibt, ohne Akku.
        assertNull(Codec.decodeNight("phasewake-night 1\na|0|1800000|1080000|AWAKE|0|\n\n").watchBattery)
        val broken = Codec.decodeNight("phasewake-night 1\na|0|1800000|1080000|AWAKE|0|||kaputt|s,1,80\n\n")
        assertEquals(WakeReason.AWAKE, broken.reason)
        assertNull(broken.watchBattery)
        assertNull(broken.phoneBattery)
    }
}
