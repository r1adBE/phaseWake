package com.phasewake.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChartDataTest {
    private val zone: ZoneId = ZoneId.of("Europe/Zurich")
    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun date(text: String) = LocalDate.parse(text)

    /** Nacht am Abend [night]: Einschlafen um [from] (nach Mitternacht am Folgetag), Aufwachen um [to] am Folgetag. */
    private fun sleep(night: String, from: String, to: String, asleep: Long): SleepSummary {
        val d = date(night)
        val start = LocalTime.parse(from)
        val startDay = if (start.hour < 12) d.plusDays(1) else d
        return SleepSummary(startDay.atTime(start).atZone(zone).toInstant(), d.plusDays(1).atTime(LocalTime.parse(to)).atZone(zone).toInstant(), asleep)
    }

    /** Uhr-Nacht am Abend [night]: Fenster 06:30–07:00 am Folgetag, geweckt [offset] Minuten nach Fensterbeginn. */
    private fun watchNight(night: String, offset: Long, reason: WakeReason, feeling: Int? = null, snoozes: Int = 0): NightRecord {
        val start = date(night).plusDays(1).atTime(6, 30).atZone(zone).toInstant()
        return NightRecord("a", start, start.plusSeconds(1800), start.plusSeconds(offset * 60), reason, snoozes, emptyList(), feeling)
    }

    private val sleeps = listOf(
        sleep("2026-10-27", "23:00", "06:30", 420),
        sleep("2026-10-26", "23:30", "07:00", 465),
        sleep("2026-10-25", "00:15", "07:10", 450),
        sleep("2026-10-20", "21:30", "05:00", 400),
    )

    @Test
    fun sleepBarsFourteenNightsWithAxisAndBedtime() {
        // Morgen 28.10. um 07:20: Nachtdatum 27.10., dafür gibt es Daten.
        val today = SleepNotes.nightDate(at("2026-10-28T07:20"), zone)
        val bars = ChartData.sleepBars(sleeps, zone, today, nextTarget = at("2026-10-29T07:00"), goalMinutes = 450)!!
        assertEquals(14, bars.bars.size)
        assertEquals(date("2026-10-14"), bars.bars.first().date)
        assertEquals(SleepBar(date("2026-10-27"), 11 * 60, 18 * 60 + 30), bars.bars.last())
        // Nach Mitternacht eingeschlafen: 00:15 = 735 Min ab Mittag.
        assertEquals(SleepBar(date("2026-10-25"), 735, 1150), bars.bars[11])
        assertEquals(SleepBar(date("2026-10-24"), null, null), bars.bars[10])
        assertEquals(4, bars.bars.count { it.hasData })
        // Achse 21:00–11:00; Bettzeit 07:00 − 7½ Std. = 23:30.
        assertEquals(ChartData.AXIS_START, bars.axisStart)
        assertEquals(ChartData.AXIS_END, bars.axisEnd)
        assertEquals(11 * 60 + 30, bars.bedtimeMinute)
        // Median 21:30/23:00/23:30/00:15 → 23:15; in der Woche ab 21.10.: 23:00 und 23:30 regelmässig, 00:15 nicht.
        assertEquals(2, bars.regular)
        assertEquals(3, bars.regularCounted)
        // Ohne Wecker keine Linie.
        assertNull(ChartData.sleepBars(sleeps, zone, today, null, 450)!!.bedtimeMinute)
    }

    @Test
    fun sleepBarsAxisGrowsAndEveningUsesNightBefore() {
        // Am Abend des 28.10.: noch keine Daten für heute Nacht → bis 27.10.
        val evening = SleepNotes.nightDate(at("2026-10-28T21:00"), zone)
        assertEquals(date("2026-10-28"), evening)
        assertEquals(date("2026-10-27"), ChartData.sleepBars(sleeps, zone, evening, null, 450)!!.bars.last().date)
        // Früh ins Bett (19:40) und lange geschlafen (bis 11:50): Achse auf ganze Stunden erweitert.
        val wide = ChartData.sleepBars(listOf(sleep("2026-10-27", "19:40", "11:50", 900)), zone, evening, null, 450)!!
        assertEquals(7 * 60, wide.axisStart)
        assertEquals(24 * 60, wide.axisEnd)
        assertEquals(SleepBar(date("2026-10-27"), 7 * 60 + 40, 23 * 60 + 50), wide.bars.last())
        // Zwei Einträge am selben Nachtdatum: der längere.
        val split = listOf(sleep("2026-10-27", "22:00", "02:00", 200), sleep("2026-10-27", "23:00", "07:00", 450))
        assertEquals(11 * 60, ChartData.sleepBars(split, zone, evening, null, 450)!!.bars.last().startMinute)
        // Zeitumstellung (25.10.): Balken nach Uhrzeit, 23:00–07:00.
        val dst = ChartData.sleepBars(listOf(sleep("2026-10-24", "23:00", "07:00", 480)), zone, date("2026-10-24"), null, 450)!!
        assertEquals(SleepBar(date("2026-10-24"), 660, 1140), dst.bars.last())
        assertNull(ChartData.sleepBars(emptyList(), zone, evening, null, 450))
        assertNull(ChartData.sleepBars(listOf(sleep("2026-09-01", "23:00", "07:00", 480)), zone, evening, null, 450))
    }

    @Test
    fun durationsSevenAndFourteenNights() {
        val today = date("2026-10-28")
        val week = ChartData.durations(sleeps, zone, today, goalMinutes = 450)!!
        assertEquals(7, week.bars.size)
        assertEquals(date("2026-10-21"), week.bars.first().date)
        assertEquals(DurationBar(date("2026-10-27"), 420, false), week.bars.last())
        assertEquals(DurationBar(date("2026-10-25"), 450, true), week.bars[4])
        assertEquals(DurationBar(date("2026-10-24"), null, false), week.bars[3])
        assertEquals(445L, week.averageMinutes)
        assertEquals(2, week.reached)
        assertEquals(3, week.counted)
        assertEquals(465L, week.scaleMinutes)
        val two = ChartData.durations(sleeps, zone, today, 450, ChartData.NIGHTS)!!
        assertEquals(14, two.bars.size)
        assertEquals(433L, two.averageMinutes)
        assertEquals(2 to 4, two.reached to two.counted)
        // Ziel über allen Nächten: Skala bis zum Ziel.
        assertEquals(600L, ChartData.durations(sleeps, zone, today, 600)!!.scaleMinutes)
        assertNull(ChartData.durations(emptyList(), zone, today, 450))
    }

    @Test
    fun wakeMomentsKindsAndAverage() {
        val nights = listOf(
            watchNight("2026-10-24", 18, WakeReason.LIGHT_SLEEP),
            watchNight("2026-10-25", 30, WakeReason.DEADLINE),
            watchNight("2026-10-26", 25, WakeReason.AWAKE),
            // Nach Schlummern 3 Min nach der Zielzeit.
            watchNight("2026-10-27", 33, WakeReason.DEADLINE, snoozes = 1),
        )
        val moments = ChartData.wakeMoments(nights.shuffled(kotlin.random.Random(3)), zone)!!
        assertEquals(listOf(12, 0, 5, 0), moments.points.map { it.minutesBefore })
        assertEquals(listOf(0, 0, 0, 3), moments.points.map { it.minutesAfter })
        assertEquals(listOf(WakeKind.LIGHT, WakeKind.TARGET, WakeKind.AWAKE, WakeKind.LATE), moments.points.map { it.kind })
        assertEquals(date("2026-10-27"), moments.points.last().date)
        assertEquals(30, moments.scaleMinutes)
        // (12 + 0 + 5 + 0) / 4 = 4,25 → 4.
        assertEquals(4, moments.averageBefore)
        // Höchstens 14 Nächte, die jüngsten.
        val many = (1..20).map { watchNight(LocalDate.of(2026, 10, 1).plusDays(it.toLong()).toString(), 20, WakeReason.LIGHT_SLEEP) }
        val last = ChartData.wakeMoments(many, zone)!!
        assertEquals(14, last.points.size)
        assertEquals(date("2026-10-21"), last.points.last().date)
        assertNull(ChartData.wakeMoments(emptyList(), zone))
    }

    @Test
    fun hypnogramOfTheNight() {
        val night = NightRecord("a", at("2026-10-28T06:30"), at("2026-10-28T07:00"), at("2026-10-28T06:42"), WakeReason.LIGHT_SLEEP, 0, emptyList())
        val stages = listOf(
            // Nacht davor: eigene Gruppe, gehört nicht dazu.
            StageSpan(at("2026-10-26T23:00"), at("2026-10-27T06:00"), SleepStage.LIGHT),
            StageSpan(at("2026-10-27T23:00"), at("2026-10-27T23:20"), SleepStage.AWAKE),
            StageSpan(at("2026-10-27T23:20"), at("2026-10-28T01:00"), SleepStage.LIGHT),
            StageSpan(at("2026-10-28T01:00"), at("2026-10-28T02:00"), SleepStage.DEEP),
            StageSpan(at("2026-10-28T02:00"), at("2026-10-28T05:00"), SleepStage.UNKNOWN),
            StageSpan(at("2026-10-28T05:00"), at("2026-10-28T06:20"), SleepStage.REM),
            StageSpan(at("2026-10-28T06:20"), at("2026-10-28T06:50"), SleepStage.LIGHT),
        )
        val h = ChartData.hypnogram(stages.reversed(), night)!!
        assertEquals(at("2026-10-27T23:00"), h.start)
        // Bis zur Zielzeit, auch wenn die Gesundheits-App früher endet.
        assertEquals(at("2026-10-28T07:00"), h.end)
        assertEquals(480.0, h.totalMinutes)
        assertEquals(5, h.steps.size)
        assertEquals(HypnoStep(0.0, 20.0, SleepStage.AWAKE), h.steps.first())
        assertEquals(HypnoStep(440.0, 470.0, SleepStage.LIGHT), h.steps.last())
        assertEquals(450.0, h.windowFrom)
        assertEquals(480.0, h.windowTo)
        assertEquals(462.0, h.wokeAt)
        assertEquals(SleepStage.LIGHT, h.stageAtWake)
        assertEquals(130L, h.minutesOf(SleepStage.LIGHT))
        assertEquals(60L, h.minutesOf(SleepStage.DEEP))
        assertEquals(listOf(0, 1, 2, 3, null), listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP, SleepStage.UNKNOWN).map(ChartData::level))
        // Ohne bekannte Phasen oder ohne Daten zur Nacht: nichts.
        assertNull(ChartData.hypnogram(listOf(StageSpan(at("2026-10-28T00:00"), at("2026-10-28T07:00"), SleepStage.UNKNOWN)), night))
        assertNull(ChartData.hypnogram(stages.take(1), night))
        assertNull(ChartData.hypnogram(emptyList(), night))
    }

    @Test
    fun feelingSeriesWithNotesAndWeeks() {
        val nights = listOf(
            watchNight("2026-10-27", 20, WakeReason.LIGHT_SLEEP, feeling = 4),
            watchNight("2026-10-26", 10, WakeReason.LIGHT_SLEEP, feeling = 3),
            // Zweiter Wecker am selben Morgen, später geweckt: dessen Befinden zählt.
            watchNight("2026-10-26", 20, WakeReason.LIGHT_SLEEP, feeling = 5),
            watchNight("2026-10-25", 20, WakeReason.LIGHT_SLEEP),
            watchNight("2026-10-20", 20, WakeReason.LIGHT_SLEEP, feeling = 2),
            watchNight("2026-10-15", 20, WakeReason.LIGHT_SLEEP, feeling = 1),
            watchNight("2026-10-01", 20, WakeReason.LIGHT_SLEEP, feeling = 5),
        )
        val notes = mapOf(date("2026-10-26") to listOf(SleepNotes.CAFFEINE), date("2026-10-28") to listOf(SleepNotes.STRESS))
        // Morgen des 28.10.: Nachtdatum 27.10. hat ein Befinden.
        val series = ChartData.feelingSeries(nights, notes, zone, SleepNotes.nightDate(at("2026-10-28T07:20"), zone))!!
        assertEquals(14, series.points.size)
        assertEquals(date("2026-10-14"), series.points.first().date)
        assertEquals(FeelingPoint(date("2026-10-27"), 4, emptyList()), series.points.last())
        assertEquals(FeelingPoint(date("2026-10-26"), 5, listOf(SleepNotes.CAFFEINE)), series.points[12])
        assertEquals(FeelingPoint(date("2026-10-25"), null, emptyList()), series.points[11])
        assertEquals(4.5, series.thisWeek)
        assertEquals(1.5, series.lastWeek)
        // Abend des 28.10.: die Notizen für heute Nacht gehören noch nicht ins Diagramm.
        val evening = ChartData.feelingSeries(nights, notes, zone, date("2026-10-28"))!!
        assertEquals(date("2026-10-27"), evening.points.last().date)
        assertTrue(evening.points.none { it.notes.contains(SleepNotes.STRESS) })
        // Nur ältere Angaben: Vorwoche ohne Daten.
        val one = ChartData.feelingSeries(listOf(nights.first()), emptyMap(), zone, date("2026-10-27"))!!
        assertEquals(4.0, one.thisWeek)
        assertNull(one.lastWeek)
        assertNull(ChartData.feelingSeries(nights.filter { it.feeling == null }, notes, zone, date("2026-10-27")))
        assertNull(ChartData.feelingSeries(emptyList(), notes, zone, date("2026-10-27")))
    }

    @Test
    fun emptyInputsGiveNoChart() {
        assertNull(ChartData.sleepBars(emptyList(), zone, date("2026-10-27"), null, 450))
        assertNull(ChartData.durations(emptyList(), zone, date("2026-10-27"), 450))
        assertNull(ChartData.wakeMoments(emptyList(), zone))
        val night = watchNight("2026-10-27", 10, WakeReason.LIGHT_SLEEP)
        assertNull(ChartData.hypnogram(emptyList(), night))
        // Nur unbekannte Phasen: nichts zu zeigen.
        val unknown = StageSpan(night.windowStart.minusSeconds(3600), night.target, SleepStage.UNKNOWN)
        assertNull(ChartData.hypnogram(listOf(unknown), night))
    }

    @Test
    fun levelsAndLastDate() {
        assertEquals(listOf(0, 2, 3, 1, null), SleepStage.entries.map { ChartData.level(it) })
        val today = date("2026-10-28")
        assertEquals(today, ChartData.lastDate(setOf(today), today))
        assertEquals(date("2026-10-27"), ChartData.lastDate(emptySet(), today))
    }
}
