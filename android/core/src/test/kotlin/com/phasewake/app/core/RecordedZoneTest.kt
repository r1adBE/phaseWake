package com.phasewake.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fremde Nächte überall in der Zone ihrer Aufzeichnung (Audit F-3, Entscheid r1AD 14.11.2026): Nachtdatum, Diagramme,
 * Übersicht, Trends und Regelmässigkeit; ohne Zone die des Geräts. Gegenstück: `RecordedZoneTests` (Swift), gleiche Fälle.
 */
class RecordedZoneTest {
    private val zurich = ZoneId.of("Europe/Zurich")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val honolulu = ZoneId.of("Pacific/Honolulu")

    private fun t(text: String): Instant = Instant.parse(text)

    /** Nacht in Tokio am Nachtdatum [day] (Oktober 2026), 23:00–07:00 Ortszeit (+09:00). */
    private fun tokyoNight(day: Int) = HealthSession(
        t("2026-10-%02dT14:00:00Z".format(day)), t("2026-10-%02dT22:00:00Z".format(day)), timeZone = tokyo,
    )

    /** Nacht in Zürich am Nachtdatum [day] (Oktober 2026, Sommerzeit), 23:00–07:00 Ortszeit (+02:00). */
    private fun zurichNight(day: Int) = HealthSession(
        t("2026-10-%02dT21:00:00Z".format(day)), t("2026-10-%02dT05:00:00Z".format(day + 1)), timeZone = zurich,
    )

    private val oct1 = LocalDate.of(2026, 10, 1)

    @Test
    fun tokyoNightSeenInZurichKeepsItsClock() {
        val sleeps = HealthHistory.summaries(listOf(tokyoNight(1)), null, zurich)
        val bar = ChartData.sleepBars(sleeps, zurich, oct1.plusDays(1), nextTarget = null, goalMinutes = 480)!!.bars.last()
        assertEquals(SleepBar(oct1, 11 * 60, 19 * 60), bar)
        val overview = SleepOverviews.of(sleeps, zurich, oct1.plusDays(1), 480, OverviewUnit.DAYS)!!
        assertEquals(11 * 60, overview.bedtimeMinute)
        assertEquals(19 * 60, overview.wakeMinute)
        assertEquals(listOf(oct1), HealthHistory.merge(emptyList(), listOf(tokyoNight(1)), null, zurich).map { it.date })
    }

    @Test
    fun nightDateFollowsTheRecordingEvenFarAway() {
        // In Honolulu (−10:00) läge die Mitte der Nacht am 1. Oktober um 08:00, also am Nachtdatum 30. September.
        val session = tokyoNight(1)
        val sleeps = HealthHistory.summaries(listOf(session), null, honolulu)
        assertEquals(listOf(oct1), HealthHistory.merge(emptyList(), listOf(session), null, honolulu).map { it.date })
        assertEquals(mapOf(oct1 to 480L), NoteEffect.asleepByNight(sleeps, honolulu))
        assertEquals(listOf(oct1 to 480L), SleepGoal.nightsOf(sleeps, honolulu))
        assertEquals(mapOf(oct1 to true), Streak.reachedByNight(sleeps, honolulu, 480))
        assertEquals(oct1, SleepNotes.nightDate(session, honolulu))
    }

    @Test
    fun zurichAndTokyoMixedCountAsOneBedtime() {
        val sessions = (1..3).map(::zurichNight) + (4..5).map(::tokyoNight)
        val sleeps = HealthHistory.summaries(sessions, null, zurich)
        assertEquals(5, sleeps.size)
        assertEquals(5 to 5, Regularity.count(Regularity.bedtimesOf(sleeps, zurich), zurich))
        val trends = Trends.compute(emptyList(), sleeps, t("2026-10-06T12:00:00Z"), zurich)
        assertEquals(0L, trends.bedtimeSpreadMinutes)
        val bars = ChartData.sleepBars(sleeps, zurich, LocalDate.of(2026, 10, 6), null, 480)!!.bars.filter { it.hasData }
        assertEquals(List(5) { 11 * 60 }, bars.map { it.startMinute })
        val week = WeeklySummary.of(emptyList(), sleeps, emptyMap(), zurich, 480, LocalDate.of(2026, 10, 5))
        assertEquals(5 to 5, week.regular to week.regularCounted)
    }

    @Test
    fun nightOverTheClockChangeKeepsLocalTimes() {
        // 24./25. Oktober 2026: Zürich stellt um 03:00 auf 02:00 zurück. 23:00 (+02:00) bis 07:00 (+01:00), 9 Stunden.
        val recorded = HealthSession(t("2026-10-24T21:00:00Z"), t("2026-10-25T06:00:00Z"), timeZone = zurich)
        val oct24 = LocalDate.of(2026, 10, 24)
        for ((session, device) in listOf(recorded to tokyo, recorded.copy(timeZone = null) to zurich)) {
            val sleeps = HealthHistory.summaries(listOf(session), null, device)
            assertEquals(540L, sleeps.single().asleepMinutes)
            assertEquals(SleepBar(oct24, 11 * 60, 19 * 60), ChartData.sleepBars(sleeps, device, oct24.plusDays(1), null, 480)!!.bars.last())
            val overview = SleepOverviews.of(sleeps, device, oct24.plusDays(1), 480, OverviewUnit.DAYS)!!
            assertEquals(11 * 60 to 19 * 60, overview.bedtimeMinute to overview.wakeMinute)
            assertEquals(listOf(oct24), HealthHistory.merge(emptyList(), listOf(session), null, device).map { it.date })
        }
    }

    @Test
    fun withoutZoneTheDeviceCounts() {
        val plain = tokyoNight(1).copy(timeZone = null)
        val sleeps = HealthHistory.summaries(listOf(plain), null, zurich)
        // 14:00–22:00 UTC sind in Zürich 16:00–00:00.
        assertEquals(SleepBar(oct1, 4 * 60, 12 * 60), ChartData.sleepBars(sleeps, zurich, oct1.plusDays(1), null, 480)!!.bars.last())
        assertEquals(zurich, sleeps.single().zoneOr(zurich))
        assertEquals(tokyo, HealthHistory.summaries(listOf(tokyoNight(1)), null, zurich).single().zoneOr(zurich))
    }
}
