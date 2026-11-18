package com.phasewake.app.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Gruppen der Liste im Verlauf. Gegenstück: `HistoryGroupsTests` (Swift), gleiche Fälle. */
class HistoryGroupsTest {
    /** Eintrag der Liste: Tag (Morgen) und ob er als Nacht zählt (sonst Aufzeichnung oder Geräusche). */
    private data class E(val day: LocalDate, val night: Boolean = true)

    private val today = LocalDate.of(2026, 11, 18)

    private fun d(text: String): LocalDate = LocalDate.parse(text)

    private fun e(text: String, night: Boolean = true) = E(d(text), night)

    /** Fünf Nächte 17.–13.11.2026 (stehen einzeln oben). */
    private val recent = (17 downTo 13).map { e("2026-11-%02d".format(it)) }

    private fun group(entries: List<E>, firstDay: DayOfWeek = DayOfWeek.MONDAY, now: LocalDate = today) =
        HistoryGroups.of(entries, { it.day }, { it.night }, now, firstDay)

    private fun singles(entries: List<E>) = entries.map { HistoryGroup.Single(it) }

    @Test
    fun fewerThanFiveNightsStaySingle() {
        val entries = recent.take(3)
        assertEquals(singles(entries), group(entries))
    }

    @Test
    fun exactlyFiveNightsStaySingleTheSixthIsGrouped() {
        assertEquals(singles(recent), group(recent))
        val old = e("2025-12-24")
        assertEquals(
            singles(recent) + HistoryGroup.Week(d("2025-12-22"), listOf(old)),
            group(recent + old),
        )
    }

    @Test
    fun fiveNewestStaySingleNoMatterHowOld() {
        val entries = listOf(e("2020-05-04"), e("2019-03-02"), e("2018-01-01"), e("2017-07-07"), e("2016-06-06"))
        assertEquals(singles(entries), group(entries))
    }

    @Test
    fun recordingsAndSoundsBeforeTheFifthNightStaySingleAfterItAreGrouped() {
        val sounds = e("2026-11-16", night = false)
        val recording = e("2026-11-12", night = false)
        val sixth = e("2026-11-11")
        val entries = recent.take(2) + sounds + recent.drop(2) + recording + sixth
        assertEquals(
            singles(recent.take(2) + sounds + recent.drop(2)) +
                HistoryGroup.Week(d("2026-11-09"), listOf(recording, sixth)),
            group(entries),
        )
    }

    @Test
    fun weekSplitAtTheFiveNightsLimitKeepsOnlyItsOwnNights() {
        // Woche ab Mo 9.11.: 13.11. steht einzeln (fünfte Nacht), 12. und 10.11. in der Wochenzeile.
        val entries = recent + e("2026-11-12") + e("2026-11-10")
        val groups = group(entries)
        assertEquals(HistoryGroup.Week(d("2026-11-09"), listOf(e("2026-11-12"), e("2026-11-10"))), groups.last())
        assertEquals(6, groups.size)
    }

    @Test
    fun weekAcrossMonthBoundary() {
        val entries = recent + e("2026-11-01") + e("2026-10-30") + e("2026-10-26")
        assertEquals(
            singles(recent) + HistoryGroup.Week(d("2026-10-26"), entries.drop(5)),
            group(entries),
        )
    }

    @Test
    fun weekStartsOnSundayOrMondayByLanguage() {
        // So 1.11. und Fr 30.10.: mit Montag eine Woche, mit Sonntag zwei.
        val entries = recent + e("2026-11-01") + e("2026-10-30")
        assertEquals(
            singles(recent) + HistoryGroup.Week(d("2026-10-26"), entries.drop(5)),
            group(entries, DayOfWeek.MONDAY),
        )
        assertEquals(
            singles(recent) + listOf(
                HistoryGroup.Week(d("2026-11-01"), listOf(e("2026-11-01"))),
                HistoryGroup.Week(d("2026-10-25"), listOf(e("2026-10-30"))),
            ),
            group(entries, DayOfWeek.SUNDAY),
        )
        assertEquals(d("2026-10-31"), HistoryGroup.Week(d("2026-10-25"), emptyList<E>()).end)
    }

    @Test
    fun gapsOfSeveralWeeksGiveNoEmptyWeeks() {
        val entries = recent + e("2026-10-20") + e("2026-09-01")
        assertEquals(
            singles(recent) + listOf(
                HistoryGroup.Week(d("2026-10-19"), listOf(e("2026-10-20"))),
                HistoryGroup.Week(d("2026-08-31"), listOf(e("2026-09-01"))),
            ),
            group(entries),
        )
    }

    @Test
    fun weekAcrossTheOneYearLimitIsSplit() {
        // Heute 18.11.2026: bis 18.11.2025 je Woche, 17.11.2025 schon im Jahr 2025 (gleiche Woche ab Mo 17.11.).
        val entries = recent + e("2025-11-19") + e("2025-11-18") + e("2025-11-17")
        assertEquals(
            singles(recent) + listOf(
                HistoryGroup.Week(d("2025-11-17"), listOf(e("2025-11-19"), e("2025-11-18"))),
                HistoryGroup.Year(2025, listOf(HistoryGroup.Week(d("2025-11-17"), listOf(e("2025-11-17"))))),
            ),
            group(entries),
        )
    }

    @Test
    fun weekAcrossNewYearIsSplitBetweenTheYears() {
        val entries = recent + e("2025-01-01") + e("2024-12-31")
        assertEquals(
            singles(recent) + listOf(
                HistoryGroup.Year(2025, listOf(HistoryGroup.Week(d("2024-12-30"), listOf(e("2025-01-01"))))),
                HistoryGroup.Year(2024, listOf(HistoryGroup.Week(d("2024-12-30"), listOf(e("2024-12-31"))))),
            ),
            group(entries),
        )
    }

    @Test
    fun nightsOlderThanAYearInSeveralYears() {
        val entries = recent + e("2026-01-05") + e("2025-03-12") + e("2025-03-10") + e("2025-02-01") +
            e("2023-06-15")
        val groups = group(entries)
        assertEquals(
            listOf(
                HistoryGroup.Week(d("2026-01-05"), listOf(e("2026-01-05"))),
                HistoryGroup.Year(
                    2025,
                    listOf(
                        HistoryGroup.Week(d("2025-03-10"), listOf(e("2025-03-12"), e("2025-03-10"))),
                        HistoryGroup.Week(d("2025-01-27"), listOf(e("2025-02-01"))),
                    ),
                ),
                HistoryGroup.Year(2023, listOf(HistoryGroup.Week(d("2023-06-12"), listOf(e("2023-06-15"))))),
            ),
            groups.drop(5),
        )
        val year = groups[6] as HistoryGroup.Year<E>
        assertEquals(listOf(e("2025-03-12"), e("2025-03-10"), e("2025-02-01")), year.entries)
    }

    @Test
    fun oneYearLimitOnLeapDay() {
        assertEquals(d("2027-02-28"), HistoryGroups.yearCutoff(d("2028-02-29")))
        assertEquals(d("2025-11-18"), HistoryGroups.yearCutoff(today))
    }

    @Test
    fun daylightSavingChangeKeepsTheLocalDay() {
        // Tag in der Ortszeit: 28.03. 23:30 UTC ist So 29.03. 00:30 in Zürich (Umstellung in dieser Nacht),
        // 25.10. 23:30 UTC ist Mo 26.10. 00:30 (Rückstellung in der Nacht davor).
        val zone = ZoneId.of("Europe/Zurich")
        fun at(text: String) = E(Instant.parse(text).atZone(zone).toLocalDate())
        val spring = at("2026-03-28T23:30:00Z")
        val autumn = at("2026-10-25T23:30:00Z")
        assertEquals(d("2026-03-29"), spring.day)
        assertEquals(d("2026-10-26"), autumn.day)
        assertEquals(
            singles(recent) + listOf(
                HistoryGroup.Week(d("2026-10-26"), listOf(autumn)),
                HistoryGroup.Week(d("2026-03-23"), listOf(spring)),
            ),
            group(recent + autumn + spring),
        )
    }

    @Test
    fun summaryCountsNightsAveragesSleepPerNightDateAndFeeling() {
        data class N(val date: LocalDate?, val feeling: Int?)
        val entries = listOf(
            N(d("2026-11-01"), 4), N(d("2026-11-01"), 2), N(d("2026-10-31"), null), N(null, 5), N(d("2026-10-30"), 5),
        )
        val asleep = mapOf(d("2026-11-01") to 420L, d("2026-10-31") to 391L)
        val summary = HistoryGroups.summary(entries, { it.date }, asleep) { it.feeling }
        // Zwei Nächte am 1.11. zählen beim Schlaf einmal: (420 + 391) / 2 = 405 (abgerundet).
        assertEquals(HistoryGroupSummary(nights = 4, asleepMinutes = 405, feeling = 11.0 / 3), summary)
        val empty = HistoryGroups.summary(listOf(N(d("2026-10-29"), null)), { it.date }, asleep) { it.feeling }
        assertEquals(1, empty.nights)
        assertNull(empty.asleepMinutes)
        assertNull(empty.feeling)
    }
}
