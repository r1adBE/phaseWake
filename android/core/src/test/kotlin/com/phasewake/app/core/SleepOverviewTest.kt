package com.phasewake.app.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Übersicht «Tage / Wochen / Monate» und Pulsverlauf. */
class SleepOverviewTest {
    private val zone: ZoneId = ZoneId.of("Europe/Zurich")

    /** Schlaf am Abend von [night] (Nachtdatum) um [bedHour]:[bedMinute] bis am Morgen danach um 06:30. */
    private fun sleep(night: LocalDate, bedHour: Int, bedMinute: Int, wakeHour: Int = 6, wakeMinute: Int = 30): SleepSummary {
        val start = night.atTime(bedHour, bedMinute).atZone(zone).toInstant()
        val end = night.plusDays(1).atTime(wakeHour, wakeMinute).atZone(zone).toInstant()
        return SleepSummary(start, end, java.time.Duration.between(start, end).toMinutes())
    }

    // Sonntag, 8.11.2026 (Morgen Montag 9.11.).
    private val today: LocalDate = LocalDate.of(2026, 11, 8)

    @Test
    fun daysAreTheLastSevenNightsWithAverages() {
        val sleeps = listOf(sleep(today, 23, 0), sleep(today.minusDays(1), 22, 30), sleep(today.minusDays(10), 22, 0))
        val o = SleepOverviews.of(sleeps, zone, today, 450, OverviewUnit.DAYS)!!
        assertEquals(7, o.bars.size)
        assertEquals(today, o.bars.last().first)
        assertEquals(today.minusDays(6), o.bars.first().first)
        // 7 Std. 30 Min und 8 Std.; die Nacht vor 10 Tagen liegt ausserhalb.
        assertEquals(450L, o.bars.last().asleepMinutes)
        assertEquals(480L, o.bars[5].asleepMinutes)
        assertNull(o.bars[0].asleepMinutes)
        assertEquals(2, o.counted)
        // 450 erreicht das Ziel 450, 480 auch.
        assertEquals(2, o.reachedNights)
        assertEquals(465L, o.averageMinutes)
        // 23:00 und 22:30 → 22:45 = 645 Min ab Mittag; Aufwachen 06:30 = 1110.
        assertEquals(645, o.bedtimeMinute)
        assertEquals(1110, o.wakeMinute)
        assertEquals(480L, o.scaleMinutes)
        assertEquals(true, o.bars.last().reached)
    }

    @Test
    fun weeksGoByTheMorningAndTheFirstDayOfTheWeek() {
        val ranges = SleepOverviews.ranges(today, OverviewUnit.WEEKS, DayOfWeek.MONDAY)
        assertEquals(8, ranges.size)
        // Morgen 9.11. (Montag): Woche 9.–15.11., als Nachtdaten 8.–14.11.
        assertEquals(LocalDate.of(2026, 11, 8) to LocalDate.of(2026, 11, 14), ranges.last())
        assertEquals(LocalDate.of(2026, 11, 1) to LocalDate.of(2026, 11, 7), ranges[6])
        // Woche ab Sonntag: Morgen 9.11. gehört zur Woche ab 8.11.
        assertEquals(LocalDate.of(2026, 11, 7) to LocalDate.of(2026, 11, 13), SleepOverviews.ranges(today, OverviewUnit.WEEKS, DayOfWeek.SUNDAY).last())
    }

    @Test
    fun monthsGoByTheMorning() {
        val ranges = SleepOverviews.ranges(LocalDate.of(2026, 10, 31), OverviewUnit.MONTHS)
        // Nacht 31.10. → Morgen 1.11.: November, als Nachtdaten 31.10.–29.11.
        assertEquals(LocalDate.of(2026, 10, 31) to LocalDate.of(2026, 11, 29), ranges.last())
        assertEquals(LocalDate.of(2026, 7, 31) to LocalDate.of(2026, 8, 30), ranges.first())
    }

    @Test
    fun weekBarIsTheAverageOfItsNights() {
        // Zwei Nächte in der Woche vom 2.–8.11. (Nachtdaten 1.–7.11.): 7 Std. 30 und 6 Std. 30.
        // Die zweite nach Mitternacht eingeschlafen: Nachtdatum über die Mitte bleibt der 3.11.
        val late = SleepSummary(
            LocalDate.of(2026, 11, 4).atTime(0, 0).atZone(zone).toInstant(),
            LocalDate.of(2026, 11, 4).atTime(6, 30).atZone(zone).toInstant(),
            390,
        )
        val sleeps = listOf(sleep(LocalDate.of(2026, 11, 2), 23, 0), late)
        val o = SleepOverviews.of(sleeps, zone, today, 450, OverviewUnit.WEEKS)!!
        // Heute (8.11.) noch ohne Daten: Die jüngste Säule ist die Woche der Nacht davor (Morgen So 8.11.).
        val week = o.bars.last()
        assertEquals(LocalDate.of(2026, 11, 1), week.first)
        assertEquals(2, week.nights)
        assertEquals(420L, week.asleepMinutes)
        assertEquals(false, week.reached)
        assertNull(o.bars[6].asleepMinutes)
    }

    @Test
    fun noDataInRangeIsNull() {
        assertNull(SleepOverviews.of(emptyList(), zone, today, 450, OverviewUnit.MONTHS))
        assertNull(SleepOverviews.of(listOf(sleep(today.minusDays(30), 23, 0)), zone, today, 450, OverviewUnit.DAYS))
    }

    @Test
    fun pulseCurveInFiveMinuteBuckets() {
        val start = Instant.parse("2026-11-08T22:00:00Z")
        // 8 Stücke zu 10 Epochen; Stück 1 ohne Puls, Stück 0 mit 50–59, die übrigen konstant 60 + Stück.
        val epochs = List(80) { i ->
            val bucket = i / 10
            Epoch(0.01, when (bucket) { 0 -> 50.0 + i; 1 -> null; else -> 60.0 + bucket })
        }
        val p = SleepOverviews.pulse(epochs, start)!!
        assertEquals(8, p.buckets.size)
        assertEquals(54.5, p.buckets[0])
        assertNull(p.buckets[1])
        assertEquals(67.0, p.buckets[7])
        // (545 + 10 · (62 + 63 + 64 + 65 + 66 + 67)) / 70 = 63.07 → 63.
        assertEquals(63, p.average)
        assertEquals(54.5, p.low)
        assertEquals(67.0, p.high)
        assertEquals(start.plusSeconds(80 * 30L), p.end)
        assertNull(SleepOverviews.pulse(List(9) { Epoch(null, 60.0) }, start))
    }

    @Test
    fun pulseNeedsSixBucketsWithValues() {
        val start = Instant.parse("2026-11-08T22:00:00Z")
        // Weckfenster 30 Min mit Puls nur in 5 Stücken: keine Kurve; in 6 Stücken: Kurve.
        assertNull(SleepOverviews.pulse(List(60) { i -> Epoch(0.01, if (i < 10) null else 60.0) }, start))
        assertEquals(6, SleepOverviews.pulse(List(60) { Epoch(0.01, 60.0) }, start)!!.buckets.size)
    }

    @Test
    fun barsCarryTheAverageStageMinutes() {
        val night = sleep(today, 23, 0)
        val other = sleep(today.minusDays(1), 23, 0)
        val t = night.start
        val stages = listOf(
            StageSpan(t, t.plusSeconds(60 * 60), SleepStage.DEEP),
            StageSpan(t.plusSeconds(60 * 60), t.plusSeconds(300 * 60), SleepStage.LIGHT),
            StageSpan(t.plusSeconds(300 * 60), t.plusSeconds(450 * 60), SleepStage.REM),
            // Ausserhalb der Nacht: zählt nicht.
            StageSpan(t.minusSeconds(3 * 3600), t.minusSeconds(2 * 3600), SleepStage.DEEP),
        )
        val o = SleepOverviews.of(listOf(other, night), zone, today, 450, OverviewUnit.WEEKS, DayOfWeek.MONDAY, stages)!!
        // Beide Nächte in der Woche ab Montag 9.11. bzw. 2.11.: die heutige allein in der letzten Säule.
        val bar = o.bars.last()
        assertEquals(mapOf(SleepStage.AWAKE to 0L, SleepStage.REM to 150L, SleepStage.LIGHT to 240L, SleepStage.DEEP to 60L), bar.stageMinutes)
        assertEquals(emptyMap(), o.bars[6].stageMinutes)
        assertEquals(emptyMap(), SleepOverviews.stageAverage(emptyList(), stages))
        // Monat: beide Nächte in einer Säule, nur eine mit Phasen → die Säule zeigt nur die Dauer (neutral).
        val month = SleepOverviews.of(listOf(other, night), zone, today, 450, OverviewUnit.MONTHS, DayOfWeek.MONDAY, stages)!!
        assertEquals(2, month.bars.last().nights)
        assertEquals(emptyMap(), month.bars.last().stageMinutes)
        // Beide Nächte mit Phasen: Durchschnitt über beide.
        val o2 = other.start
        val both = stages + listOf(
            StageSpan(o2, o2.plusSeconds(90 * 60), SleepStage.DEEP),
            StageSpan(o2.plusSeconds(90 * 60), o2.plusSeconds(450 * 60), SleepStage.LIGHT),
        )
        val all = SleepOverviews.of(listOf(other, night), zone, today, 450, OverviewUnit.MONTHS, DayOfWeek.MONDAY, both)!!
        // Tief (60 + 90) / 2 = 75, leicht (240 + 360) / 2 = 300, REM 150 / 2 = 75.
        assertEquals(mapOf(SleepStage.AWAKE to 0L, SleepStage.REM to 75L, SleepStage.LIGHT to 300L, SleepStage.DEEP to 75L), all.bars.last().stageMinutes)
    }
}
