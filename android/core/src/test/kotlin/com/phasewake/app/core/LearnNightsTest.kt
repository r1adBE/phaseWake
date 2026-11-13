package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lern-Übersicht ([LearnNights]): Grund je Nacht, Liste der Nächte, Startseite, Warnung am Abend; gleich wie `LearnNightsTests` (Swift). */
class LearnNightsTest {
    private val zone = ZoneId.of("Europe/Zurich")

    private fun at(day: Int, hour: Int, minute: Int = 0): Instant = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant()

    private fun date(day: Int): LocalDate = LocalDate.of(2026, 11, day)

    /** Aufzeichnung ab [start] mit [epochs] Epochen (Bewegung, Puls in jeder [pulseEvery]-ten), Ende [end]. */
    private fun recorded(start: Instant, epochs: Int, pulseEvery: Int = 5, end: String? = "wake") =
        LearnNights.Recorded(Learning.Night(start, List(epochs) { Epoch(0.02, if (it % pulseEvery == 0) 55.0 else null) }), end)

    @Test
    fun sharedVectors() {
        // Gemeinsame Fälle mit Swift (shared/testdata/learn-nights.vectors).
        val lines = NightFixtures.directory().resolve("learn-nights.vectors").readLines(Charsets.UTF_8)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(lines.size >= 20)
        val seen = mutableSetOf<LearnNights.Reason>()
        for (line in lines) {
            val (expected, text) = line.split("\t", limit = 2)
            val reason = LearnNights.reason(facts(text))
            assertEquals(LearnNights.Reason.valueOf(expected), reason, line)
            seen += reason
        }
        assertEquals(LearnNights.Reason.entries.toSet(), seen, "jeder Grund mindestens einmal")
    }

    private fun facts(text: String): LearnNights.Facts {
        val map = text.trim().split(' ').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
        fun flag(key: String, default: Boolean) = map[key]?.let { it == "1" } ?: default
        return LearnNights.Facts(
            recorded = flag("recorded", false),
            measuredEpochs = map["measured"]?.toInt() ?: 0,
            spanEpochs = map["span"]?.toInt() ?: 0,
            heartRateEpochs = map["hr"]?.toInt() ?: 0,
            endCode = map["end"],
            beforeStart = flag("before", false),
            skip = map["skip"]?.let { code -> LearnSkip.Reason.entries.first { it.code == code } },
            consent = flag("consent", true),
            alarm = map["alarm"]?.takeIf { it != "-" }?.let { it == "1" },
            autoOnset = flag("auto", true),
            pending = flag("pending", false),
        )
    }

    @Test
    fun countedAgreesWithTheLearningRule() {
        for (epochs in listOf(0, 100, Learning.MIN_SPAN_EPOCHS - 1, Learning.MIN_SPAN_EPOCHS, 900)) {
            val night = recorded(at(1, 22), epochs).night
            val facts = LearnNights.Facts(
                recorded = true, measuredEpochs = Learning.measuredEpochs(night), spanEpochs = LearnNights.spanEpochs(night),
                heartRateEpochs = night.epochs.count { it.heartRate != null }, endCode = "wake",
            )
            assertEquals(Learning.counts(night), LearnNights.reason(facts).counted, "$epochs Epochen")
        }
        assertEquals(0, LearnNights.spanEpochs(Learning.Night(at(1, 22), List(10) { Epoch(null, null) })))
        assertEquals(20, LearnNights.pulsePercent(100, 20))
        assertEquals(0, LearnNights.pulsePercent(0, 5))
        assertEquals(100, LearnNights.pulsePercent(10, 30))
    }

    @Test
    fun nightsSinceTheStartNewestFirst() {
        val since = at(1, 18)
        val recordings = listOf(recorded(at(1, 22), 600), recorded(at(2, 23), 200, end = "watch"))
        val skips = listOf(LearnSkip(at(4, 7), LearnSkip.Reason.BATTERY, 35))
        val alarms = setOf(date(1), date(2), date(3), date(4), date(5))
        val now = at(6, 9)
        val nights = LearnNights.nights(recordings, skips, alarms, date(1), since, consent = true, autoOnset = true, now = now, zone = zone)
        assertEquals(listOf(date(5), date(4), date(3), date(2), date(1)), nights.map { it.date })
        assertEquals(
            listOf(LearnNights.Reason.PENDING, LearnNights.Reason.NO_RECORDING, LearnNights.Reason.BATTERY, LearnNights.Reason.ENDED_STOPPED, LearnNights.Reason.COUNTED),
            nights.map { it.reason },
        )
        assertEquals(35, nights[2].battery)
        assertEquals(LearnNights.Night(date(1), LearnNights.Reason.COUNTED, minutes = 300, measuredEpochs = 600, pulsePercent = 20), nights[4])
        assertEquals(100, nights[3].minutes)
        // Vor Mitternacht erscheint die laufende Nacht ohne Angaben nicht; ab Mittag ist die letzte nicht mehr unterwegs.
        assertEquals(date(5), LearnNights.nights(recordings, skips, alarms, date(1), since, true, true, at(6, 13), zone).first().date)
        assertEquals(LearnNights.Reason.NO_RECORDING, LearnNights.nights(recordings, skips, alarms, date(1), since, true, true, at(6, 13), zone).first().reason)
        assertEquals(date(4), LearnNights.nights(recordings, skips, alarms, date(1), since, true, true, at(5, 23), zone).first().date)
    }

    @Test
    fun alarmKnowledgeAndStart() {
        val since = at(1, 18)
        // Ab dem Beginn der gemerkten Wecker heisst ein fehlendes Datum «kein Wecker», davor unbekannt.
        val nights = LearnNights.nights(emptyList(), emptyList(), setOf(date(2)), date(3), since, true, true, at(4, 13), zone)
        assertEquals(
            listOf(LearnNights.Reason.NO_ALARM, LearnNights.Reason.NO_RECORDING, LearnNights.Reason.UNKNOWN),
            nights.map { it.reason },
        )
        // Eine Aufzeichnung, die vor dem Beginn der Lernphase begann, zählt nicht.
        val before = LearnNights.nights(listOf(recorded(at(1, 17), 600)), emptyList(), emptySet(), null, since, true, true, at(2, 13), zone)
        assertEquals(LearnNights.Reason.LEARNING_OFF, before.single().reason)
        // Eine Meldung belegt den Wecker; ohne Schalter (Galaxy Watch) startet nichts.
        val auto = LearnNights.nights(emptyList(), emptyList(), emptySet(), null, since, true, false, at(2, 13), zone)
        assertEquals(LearnNights.Reason.AUTO_OFF, auto.single().reason)
        // Höchstens 14 Nächte.
        val long = LearnNights.nights(emptyList(), emptyList(), emptySet(), null, at(1, 18).minus(Duration.ofDays(40)), true, true, at(2, 13), zone)
        assertEquals(LearnNights.MAX_NIGHTS, long.size)
        // Die beste Aufzeichnung der Nacht: eine zählende vor einer mit mehr Messungen.
        val sampled = LearnNights.Recorded(Learning.Night(at(1, 22), List(400) { if (it % 5 == 0) Epoch(0.02, 50.0) else Epoch(null, null) }), "wake")
        val two = listOf(sampled, recorded(at(1, 21), 350))
        val best = LearnNights.nights(two, emptyList(), emptySet(), null, since, true, true, at(2, 13), zone).single()
        assertEquals(LearnNights.Reason.COUNTED, best.reason)
        assertEquals(80, best.measuredEpochs)
    }

    @Test
    fun homeShowsOnlyAnUnexpectedMissOfLastNight() {
        fun night(day: Int, reason: LearnNights.Reason) = LearnNights.Night(date(day), reason)
        val now = at(6, 9)
        assertEquals(night(5, LearnNights.Reason.BATTERY), LearnNights.lastNightMiss(listOf(night(5, LearnNights.Reason.BATTERY)), now, zone))
        assertEquals(night(4, LearnNights.Reason.SENSORS), LearnNights.lastNightMiss(listOf(night(4, LearnNights.Reason.SENSORS)), now, zone))
        assertNull(LearnNights.lastNightMiss(listOf(night(3, LearnNights.Reason.SENSORS)), now, zone), "älter als gestern")
        for (quiet in listOf(LearnNights.Reason.COUNTED, LearnNights.Reason.COUNTED_NO_PULSE, LearnNights.Reason.PENDING, LearnNights.Reason.NO_ALARM, LearnNights.Reason.LEARNING_OFF)) {
            assertNull(LearnNights.lastNightMiss(listOf(night(5, quiet)), now, zone), quiet.name)
        }
        assertNull(LearnNights.lastNightMiss(emptyList(), now, zone))
    }

    @Test
    fun eveningWarningOneHourBeforeTheRecording() {
        val start = at(5, 22)
        val low = Learning.MIN_BATTERY - 1
        assertEquals(at(5, 21), LearnNights.warnAt(start))
        assertTrue(LearnNights.shouldWarn(true, low, false, at(5, 21), start))
        assertTrue(LearnNights.shouldWarn(true, low, false, at(5, 20, 50), start), "ungenauer Alarm etwas früher")
        assertFalse(LearnNights.shouldWarn(true, low, false, at(5, 20, 49), start))
        assertTrue(LearnNights.shouldWarn(true, low, false, at(5, 21, 59), start))
        assertFalse(LearnNights.shouldWarn(true, low, false, start, start), "Aufzeichnung beginnt schon")
        assertFalse(LearnNights.shouldWarn(true, Learning.MIN_BATTERY, false, at(5, 21), start))
        assertFalse(LearnNights.shouldWarn(true, null, false, at(5, 21), start), "unbekannter Akku")
        assertFalse(LearnNights.shouldWarn(true, low, true, at(5, 21), start), "lädt")
        assertFalse(LearnNights.shouldWarn(false, low, false, at(5, 21), start), "Lernphase läuft nicht")
    }
}
