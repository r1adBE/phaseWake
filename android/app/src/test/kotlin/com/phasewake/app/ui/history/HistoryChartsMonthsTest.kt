package com.phasewake.app.ui.history

import com.phasewake.app.core.OverviewUnit
import com.phasewake.app.core.SleepSummary
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Übersicht «Monate» mit allen Nächten der gezeigten Monate; «Tage» und «Wochen» wie die übrigen Kennzahlen. */
class HistoryChartsMonthsTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-11-18T12:00:00Z")

    /** Eine Nacht je Tag von [from] bis gestern: 23:00 bis 06:30, 7 Std. Schlaf. */
    private fun nightly(from: LocalDate): List<SleepSummary> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { it.isBefore(LocalDate.of(2026, 11, 18)) }.map {
            val start = it.atTime(23, 0).toInstant(zone)
            SleepSummary(start, start.plus(Duration.ofMinutes(450)), 420)
        }.toList()

    private fun charts(sleeps: List<SleepSummary>, monthSleeps: List<SleepSummary>?) = HistoryCharts.of(
        ChartSource(emptyList(), sleeps, emptyList(), emptyMap(), monthSleeps = monthSleeps),
        emptyList(), 450, now, zone, DayOfWeek.MONDAY,
    )

    @Test
    fun monthsGetTheWholeOldestMonth() {
        val all = nightly(LocalDate.of(2026, 6, 1))
        // Wie die übrigen Kennzahlen: Sitzungen der letzten 91 Tage.
        val recent = all.filter { it.end.isAfter(now.minus(Duration.ofDays(91))) }
        val full = charts(recent, all)
        val before = charts(recent, null)
        val oldest = full.overview.getValue(OverviewUnit.MONTHS).bars.first()
        // Älteste Säule: alle Nächte ihres Monats (Nachtdaten erstes bis letztes), nicht nur die der 90 Tage.
        val days = Duration.between(oldest.first.atStartOfDay(), oldest.last.plusDays(1).atStartOfDay()).toDays()
        assertEquals(days.toInt(), oldest.nights)
        assertTrue(before.overview.getValue(OverviewUnit.MONTHS).bars.first().nights < oldest.nights)
        // «Tage» und «Wochen» unverändert.
        assertEquals(before.overview[OverviewUnit.DAYS], full.overview[OverviewUnit.DAYS])
        assertEquals(before.overview[OverviewUnit.WEEKS], full.overview[OverviewUnit.WEEKS])
    }
}
