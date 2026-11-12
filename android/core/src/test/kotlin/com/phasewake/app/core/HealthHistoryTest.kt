package com.phasewake.app.core

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verlauf mit Nächten aus der Gesundheits-App. Gegenstück: `HealthHistoryTests` (Swift), gleiche Fälle. */
class HealthHistoryTest {
    private val zone = ZoneId.of("Europe/Zurich")

    private fun t(text: String): Instant = Instant.parse(text)

    private fun span(from: String, to: String, stage: SleepStage) = StageSpan(t(from), t(to), stage)

    private fun night(windowStart: String, wokeAt: String, alarmId: String = "a") = NightRecord(
        alarmId = alarmId,
        windowStart = t(windowStart),
        target = t(windowStart).plusSeconds(30 * 60),
        wokeAt = t(wokeAt),
        reason = WakeReason.LIGHT_SLEEP,
        snoozes = 0,
        epochs = emptyList(),
    )

    private val full = HealthSession(
        t("2026-10-01T21:00:00Z"), t("2026-10-02T05:00:00Z"),
        listOf(
            span("2026-10-01T21:00:00Z", "2026-10-01T21:15:00Z", SleepStage.AWAKE),
            span("2026-10-01T21:15:00Z", "2026-10-02T01:00:00Z", SleepStage.LIGHT),
            span("2026-10-02T01:00:00Z", "2026-10-02T02:00:00Z", SleepStage.DEEP),
            span("2026-10-02T02:00:00Z", "2026-10-02T04:30:00Z", SleepStage.REM),
            span("2026-10-02T04:30:00Z", "2026-10-02T05:00:00Z", SleepStage.AWAKE),
        ),
        "com.sec.android.app.shealth",
    )

    /**
     * Samsung-Health-Nacht vom 1. November 02:42–12:50 Ortszeit (CET): 11 Min. wach, 60 Phasen à 9 Min. 30 s
     * (570 Min. Schlaf), 27 Min. wach. Je Phase abgerundet hätte das Diagramm 30 Minuten weniger gezeigt.
     */
    private val lateNight = HealthSession(
        t("2026-11-01T01:42:00Z"), t("2026-11-01T11:50:00Z"),
        buildList {
            add(span("2026-11-01T01:42:00Z", "2026-11-01T01:53:00Z", SleepStage.AWAKE))
            val first = t("2026-11-01T01:53:00Z")
            val stages = listOf(SleepStage.LIGHT, SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM)
            for (i in 0 until 60) add(StageSpan(first.plusSeconds(i * 570L), first.plusSeconds((i + 1) * 570L), stages[i % stages.size]))
            add(span("2026-11-01T11:23:00Z", "2026-11-01T11:50:00Z", SleepStage.AWAKE))
        },
        "com.sec.android.app.shealth",
    )

    @Test
    fun lateWakeUpSameNightAndTotalEverywhere() {
        val oct31 = java.time.LocalDate.of(2026, 10, 31)
        assertEquals(570, lateNight.asleepMinutes)
        assertEquals(570, StageStats.forSession(lateNight)!!.asleepMinutes)
        val sleeps = HealthHistory.summaries(listOf(lateNight), null, zone)
        assertEquals(listOf(570L), sleeps.map { it.asleepMinutes })
        // Verlauf und Detailseite: Nacht vom 31. Oktober (Morgen 1. November).
        assertEquals(listOf(oct31), HealthHistory.merge(emptyList(), listOf(lateNight), null, zone).map { it.date })
        // Diagramme: dasselbe Nachtdatum, dieselbe Dauer ("today" = Nachtdatum am Morgen des 2. November).
        val today = oct31.plusDays(1)
        val durations = ChartData.durations(sleeps, zone, today, goalMinutes = 480)!!
        assertEquals(oct31 to 570L, durations.bars.last().let { it.date to it.asleepMinutes })
        val bars = ChartData.sleepBars(sleeps, zone, today, nextTarget = null, goalMinutes = 480)!!
        assertEquals(oct31, bars.bars.last().date)
        assertTrue(bars.bars.last().hasData)
        assertEquals(mapOf(oct31 to 570L), NoteEffect.asleepByNight(sleeps, zone))
        assertEquals(listOf(oct31 to 570L), SleepGoal.nightsOf(sleeps, zone))
        assertEquals(listOf(oct31), Regularity.bedtimesOf(sleeps, zone).map { it.first })
    }

    @Test
    fun sessionMetrics() {
        assertEquals(480, full.durationMinutes)
        assertEquals(435, full.asleepMinutes)
        assertEquals(45L, full.awakeMinutes)
        assertEquals(t("2026-10-01T21:15:00Z"), full.asleepAt)
        assertEquals(60, full.minutesOf(SleepStage.DEEP))
        assertTrue(full.hasStages)
        assertFalse(full.isNap(zone))
        assertEquals(5, full.steps.size)
        assertEquals(HypnoStep(15.0, 240.0, SleepStage.LIGHT), full.steps[1])
    }

    @Test
    fun sessionWithoutStages() {
        val s = HealthSession(t("2026-10-01T22:00:00Z"), t("2026-10-02T06:00:00Z"))
        assertEquals(480, s.asleepMinutes)
        assertNull(s.awakeMinutes)
        assertFalse(s.hasStages)
        assertEquals(s.start, s.asleepAt)
        assertTrue(s.steps.isEmpty())
    }

    @Test
    fun onlyUnknownStages() {
        val s = HealthSession(
            t("2026-10-01T22:00:00Z"), t("2026-10-02T06:00:00Z"),
            listOf(span("2026-10-01T22:00:00Z", "2026-10-02T06:00:00Z", SleepStage.UNKNOWN)),
        )
        assertFalse(s.hasStages)
        assertNull(s.awakeMinutes)
        assertEquals(480, s.asleepMinutes)
        assertTrue(s.steps.isEmpty())
    }

    @Test
    fun stagesClippedToSession() {
        val s = HealthSession(
            t("2026-10-01T22:00:00Z"), t("2026-10-02T06:00:00Z"),
            listOf(span("2026-10-01T21:00:00Z", "2026-10-01T23:00:00Z", SleepStage.LIGHT)),
        )
        assertEquals(60, s.minutesOf(SleepStage.LIGHT))
        assertEquals(t("2026-10-01T22:00:00Z"), s.asleepAt)
        assertEquals(0L, s.awakeMinutes)
        assertEquals(0, HealthSession(s.end, s.start).durationMinutes)
    }

    @Test
    fun minutesRoundDown() {
        // Gegenstück zu Swift: knapp unter 180 Minuten bleiben 179 (ein Nap), genau 180 sind eine Nacht.
        val start = t("2026-10-02T12:00:00Z")
        assertEquals(179, HealthSession(start, start.plusSeconds(3 * 3600).minusNanos(400_000)).durationMinutes)
        assertEquals(180, HealthSession(start, start.plusSeconds(3 * 3600)).durationMinutes)
    }

    @Test
    fun napIsDropped() {
        val nap = HealthSession(t("2026-10-02T12:00:00Z"), t("2026-10-02T14:59:00Z"))
        val three = HealthSession(t("2026-10-03T12:00:00Z"), t("2026-10-03T15:00:00Z"))
        assertTrue(nap.isNap(zone))
        assertFalse(three.isNap(zone))
        assertEquals(listOf(three), HealthHistory.merge(emptyList(), listOf(nap, three), null, zone).map { it.session })
        // Ende nicht nach Beginn: keine Nacht.
        assertTrue(HealthHistory.merge(emptyList(), listOf(HealthSession(three.end, three.start)), null, zone).isEmpty())
    }

    @Test
    fun napOnlyInDaytime() {
        // Europe/Zurich im Oktober: UTC+2. Kurz und ganz zwischen 09:00 und 19:00 Ortszeit: Nickerchen (Grenzen einschliesslich).
        assertTrue(HealthSession(t("2026-10-02T07:00:00Z"), t("2026-10-02T09:00:00Z")).isNap(zone))
        assertTrue(HealthSession(t("2026-10-02T15:00:00Z"), t("2026-10-02T17:00:00Z")).isNap(zone))
        // Beginn 08:59 oder Ende 19:01: Nacht.
        assertFalse(HealthSession(t("2026-10-02T06:59:00Z"), t("2026-10-02T08:59:00Z")).isNap(zone))
        assertFalse(HealthSession(t("2026-10-02T15:01:00Z"), t("2026-10-02T17:01:00Z")).isNap(zone))
        // Über Mitternacht (23:00–01:00) und kurz in der Nacht (01:00–03:00): Nacht, im Verlauf.
        assertFalse(HealthSession(t("2026-10-01T21:00:00Z"), t("2026-10-01T23:00:00Z")).isNap(zone))
        val short = HealthSession(t("2026-10-01T23:00:00Z"), t("2026-10-02T01:00:00Z"))
        assertFalse(short.isNap(zone))
        val items = HealthHistory.merge(emptyList(), listOf(short), null, zone)
        assertEquals(listOf(short), items.map { it.session })
        assertEquals(java.time.LocalDate.of(2026, 10, 1), items.single().date)
        // Diagramme und Trends nach derselben Regel: kurze Nacht zählt, Nickerchen am Tag nicht.
        val dayNap = HealthSession(t("2026-10-02T10:00:00Z"), t("2026-10-02T12:00:00Z"))
        assertEquals(listOf(120L), HealthHistory.summaries(listOf(short, dayNap), null, zone).map { it.asleepMinutes })
        assertEquals(java.time.LocalTime.of(9, 0), HealthHistory.NAP_EARLIEST)
        assertEquals(java.time.LocalTime.of(19, 0), HealthHistory.NAP_LATEST)
    }

    @Test
    fun phaseWakeNightWins() {
        val own = night("2026-10-02T04:30:00Z", "2026-10-02T04:48:00Z")
        val items = HealthHistory.merge(listOf(own), listOf(full), null, zone)
        assertEquals(1, items.size)
        assertEquals(HistoryKind.PHASE_WAKE, items[0].kind)
        assertEquals(own, items[0].night)
        assertEquals(java.time.LocalDate.of(2026, 10, 1), items[0].date)
    }

    @Test
    fun overlapDifferentDateDropped() {
        // Eigene Nacht 11:30–12:30 Ortszeit (Nachtdatum 1.10.), Sitzung bis 12:10 Ortszeit (Nachtdatum 2.10.).
        val own = night("2026-10-02T09:30:00Z", "2026-10-02T10:30:00Z")
        val s = HealthSession(t("2026-10-02T03:00:00Z"), t("2026-10-02T10:10:00Z"))
        assertEquals(listOf(HistoryKind.PHASE_WAKE), HealthHistory.merge(listOf(own), listOf(s), null, zone).map { it.kind })
    }

    @Test
    fun longestPerDate() {
        val a = HealthSession(t("2026-10-01T21:00:00Z"), t("2026-10-02T05:00:00Z"), source = "a")
        val b = HealthSession(t("2026-10-01T22:00:00Z"), t("2026-10-02T05:30:00Z"), source = "b")
        val c = HealthSession(t("2026-10-01T21:30:00Z"), t("2026-10-02T05:30:00Z"), source = "c")
        assertEquals(listOf(a), HealthHistory.merge(emptyList(), listOf(b, c, a), null, zone).map { it.session })
        val unnamed = a.copy(source = null)
        assertEquals(listOf(unnamed), HealthHistory.merge(emptyList(), listOf(a, unnamed), null, zone).map { it.session })
    }

    @Test
    fun clearedAtHides() {
        val own = night("2026-09-20T04:30:00Z", "2026-09-20T04:48:00Z")
        assertEquals(listOf(HistoryKind.PHASE_WAKE), HealthHistory.merge(listOf(own), listOf(full), full.end, zone).map { it.kind })
        val visible = HealthHistory.merge(listOf(own), listOf(full), full.end.minusMillis(1), zone)
        assertEquals(listOf(HistoryKind.HEALTH, HistoryKind.PHASE_WAKE), visible.map { it.kind })
        assertEquals(listOf(full), HealthHistory.visible(listOf(full), null))
    }

    @Test
    fun sortedNewestFirst() {
        val first = night("2026-10-02T04:30:00Z", "2026-10-02T04:48:00Z")
        val session = HealthSession(t("2026-10-02T21:00:00Z"), t("2026-10-03T05:00:00Z"))
        val third = night("2026-10-04T04:30:00Z", "2026-10-04T04:50:00Z")
        val items = HealthHistory.merge(listOf(first, third), listOf(session), null, zone)
        assertEquals(listOf(HistoryKind.PHASE_WAKE, HistoryKind.HEALTH, HistoryKind.PHASE_WAKE), items.map { it.kind })
        assertEquals(listOf(third.wokeAt, session.end, first.wokeAt), items.map { it.at })
        assertEquals(java.time.LocalDate.of(2026, 10, 2), items[1].date)
        // Gleiche Weckzeit: späterer Fensterbeginn zuerst, dann Wecker-Kennung.
        val early = night("2026-10-06T04:00:00Z", "2026-10-06T04:40:00Z", "z")
        val late = night("2026-10-06T04:20:00Z", "2026-10-06T04:40:00Z", "y")
        val sameB = night("2026-10-06T04:20:00Z", "2026-10-06T04:40:00Z", "b")
        assertEquals(listOf(sameB, late, early), HealthHistory.merge(listOf(early, late, sameB), emptyList(), null, zone).map { it.night })
        assertEquals(Instant.EPOCH, HistoryItem(HistoryKind.HEALTH, items[1].date).at)
    }

    @Test
    fun summariesRespectCleared() {
        val plain = HealthSession(t("2026-10-03T22:00:00Z"), t("2026-10-04T06:00:00Z"))
        val all = HealthHistory.summaries(listOf(full, plain), null, zone)
        assertEquals(listOf(435L, 480L), all.map { it.asleepMinutes })
        assertEquals(listOf(480L), HealthHistory.summaries(listOf(full, plain), full.end, zone).map { it.asleepMinutes })
        assertEquals(5, HealthHistory.stages(listOf(plain, full), null, zone).size)
        assertEquals(full.stages.first(), HealthHistory.stages(listOf(plain, full), null, zone).first())
        assertTrue(HealthHistory.stages(listOf(full), full.end, zone).isEmpty())
    }

    @Test
    fun overlappingAppsCountOnce() {
        // Zwei Apps schreiben dieselbe Nacht: die mit Phasen gewinnt, auch wenn die andere länger ist.
        val fit = HealthSession(t("2026-10-01T20:30:00Z"), t("2026-10-02T06:00:00Z"), source = "com.google.android.apps.fitness")
        assertEquals(listOf(full), HealthHistory.merge(emptyList(), listOf(fit, full), null, zone).map { it.session })
        assertEquals(listOf(435L), HealthHistory.summaries(listOf(fit, full), null, zone).map { it.asleepMinutes })
        assertEquals(full.stages, HealthHistory.stages(listOf(fit, full), null, zone))
        // Beide ohne Phasen: die längere.
        val short = HealthSession(t("2026-10-01T22:00:00Z"), t("2026-10-02T05:00:00Z"))
        assertEquals(listOf(fit), HealthHistory.distinct(listOf(short, fit)))
    }

    @Test
    fun touchingSessionsAreKept() {
        val first = HealthSession(t("2026-10-01T20:00:00Z"), t("2026-10-02T00:00:00Z"))
        val second = HealthSession(t("2026-10-02T00:00:00Z"), t("2026-10-02T05:00:00Z"))
        assertEquals(listOf(first, second), HealthHistory.distinct(listOf(second, first)))
        // Die zweite schliesst ohne Lücke an (HealthHistory.CONTINUATION_GAP): eine Nacht von 20:00 bis 05:00 UTC.
        val joined = HealthHistory.merge(emptyList(), listOf(first, second), null, zone).single().session!!
        assertEquals(first.start to second.end, joined.start to joined.end)
        assertEquals(540, joined.asleepMinutes)
    }
}
