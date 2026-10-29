package com.phasewake.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Befinden zu Nächten aus anderen Apps. Gegenstück: `HealthFeelingsTests` (Swift), gleiche Fälle. */
class HealthFeelingsTest {
    private val zone = ZoneId.of("Europe/Zurich")

    private fun t(text: String): Instant = Instant.parse(text)

    private fun d(text: String): LocalDate = LocalDate.parse(text)

    private fun night(windowStart: String, feeling: Int?) = NightRecord(
        alarmId = "a",
        windowStart = t(windowStart),
        target = t(windowStart).plusSeconds(30 * 60),
        wokeAt = t(windowStart).plusSeconds(20 * 60),
        reason = WakeReason.LIGHT_SLEEP,
        snoozes = 0,
        epochs = emptyList(),
        feeling = feeling,
    )

    /** [count] Nächte aus einer anderen App ab dem 1.10., je am Morgen danach um 07:00 Ortszeit zu Ende. */
    private fun health(count: Long, feeling: (Long) -> Int) = (0L until count).map { i ->
        HealthFeeling(d("2026-10-01").plusDays(i), t("2026-10-02T05:00:00Z").plusSeconds(i * 86_400), feeling(i))
    }

    @Test
    fun encodeAndDecode() {
        val map = mapOf(d("2026-10-01") to 4, d("2026-09-30") to 2)
        assertEquals("2026-09-30|2\n2026-10-01|4", HealthFeelings.encode(map))
        assertEquals(map, HealthFeelings.decode(HealthFeelings.encode(map)))
        // Unlesbar: kein Datum, Wert ausserhalb 1–5, leer, ungültiges Datum, zusätzliche Felder.
        val text = "x|3\n2026-10-02|9\n2026-10-03|\n2026-02-30|3\n2026-10-04|0\n2026-10-06|2|x\n2026-10-05| 1 "
        assertEquals(mapOf(d("2026-10-05") to 1), HealthFeelings.decode(text))
        assertTrue(HealthFeelings.decode(null).isEmpty())
        assertEquals("", HealthFeelings.encode(emptyMap()))
    }

    @Test
    fun setReplacesAndTrims() {
        val one = HealthFeelings.set(emptyMap(), d("2026-10-01"), 5)
        assertEquals(mapOf(d("2026-10-01") to 3), HealthFeelings.set(one, d("2026-10-01"), 3))
        assertEquals(one, HealthFeelings.set(one, d("2026-10-02"), 0))
        assertEquals(one, HealthFeelings.set(one, d("2026-10-02"), 6))
        var many = emptyMap<LocalDate, Int>()
        for (i in 0L until 91L) many = HealthFeelings.set(many, d("2026-01-01").plusDays(i), 4)
        assertEquals(HealthFeelings.MAX_DATES, many.size)
        assertFalse(d("2026-01-01") in many)
        assertTrue(d("2026-04-01") in many)
    }

    @Test
    fun onlyNightsFromOtherAppsInHistory() {
        val own = night("2026-10-02T04:30:00Z", 2)
        val session = HealthSession(t("2026-10-02T21:00:00Z"), t("2026-10-03T05:00:00Z"))
        val items = HealthHistory.merge(listOf(own), listOf(session), null, zone)
        // 1.10. gehört der eigenen Nacht, 20.9. ist in der Quelle weg: beide zählen nicht.
        val stored = mapOf(d("2026-10-01") to 5, d("2026-10-02") to 4, d("2026-09-20") to 1)
        assertEquals(listOf(HealthFeeling(d("2026-10-02"), session.end, 4)), HealthFeelings.of(items, stored))
        assertTrue(HealthFeelings.of(items, emptyMap()).isEmpty())
    }

    @Test
    fun ownFeelingWins() {
        val rated = night("2026-10-02T04:30:00Z", 2)
        val unrated = night("2026-10-03T04:30:00Z", null)
        val other = listOf(
            HealthFeeling(d("2026-10-01"), t("2026-10-02T05:00:00Z"), 5),
            HealthFeeling(d("2026-10-02"), t("2026-10-03T05:00:00Z"), 4),
            HealthFeeling(d("2026-10-04"), t("2026-10-05T05:00:00Z"), 3),
        )
        val expected = mapOf(d("2026-10-01") to 2, d("2026-10-02") to 4, d("2026-10-04") to 3)
        assertEquals(expected, HealthFeelings.byDate(listOf(rated, unrated), other, zone))
    }

    @Test
    fun countsInChartsAndTrends() {
        val three = health(3) { 4 }
        assertEquals(listOf(4, 4, 4), ChartData.feelingSeries(emptyList(), emptyMap(), zone, d("2026-10-04"), health = three)?.points?.mapNotNull { it.feeling })
        assertNull(ChartData.feelingSeries(emptyList(), emptyMap(), zone, d("2026-10-04")))
        val now = t("2026-10-04T12:00:00Z")
        assertEquals(4.0, Trends.compute(emptyList(), emptyList(), now, zone, three).feelingThisWeek)
        assertNull(Trends.compute(emptyList(), emptyList(), now, zone).feelingThisWeek)
        // Woche bis Samstag, 3.10.
        assertEquals(4.0, WeeklySummary.of(emptyList(), emptyList(), emptyMap(), zone, 450, d("2026-10-03"), three).feelingAverage)
        // Notiz-Wirkung: drei Nächte mit Kaffee (Befinden 2), drei ohne (4).
        val six = health(6) { if (it < 3) 2 else 4 }
        val notes = (0L until 3L).associate { d("2026-10-01").plusDays(it) to listOf(SleepNotes.CAFFEINE) }
        assertEquals(-2.0, NoteEffect.compute(emptyList(), notes, zone, health = six).single().delta)
    }
}
