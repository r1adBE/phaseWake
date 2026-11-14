package com.phasewake.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Weiterschlafen nach dem Wecken ([HealthHistory.CONTINUATION_GAP]) mit der echten Nacht vom 12. November 2026
 * (Galaxy Watch 8, Samsung Health → Health Connect). Gegenstück: `HealthContinuationTests` (Swift), gleiche Fälle.
 *
 * Samsung zeigt: im Bett 01:36–11:56, Schlaf 03:11–09:57 und 09:58–11:56 (zwei Sitzungen, getrennt durch den
 * Wecker um 09:57), Schlafzeit 8 h 44, tatsächlicher Schlaf 7 h 18, Einschlafen 1 h 35, wach 1 h 26.
 * Europe/Zurich im November: UTC+1.
 */
class HealthContinuationTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val samsung = "com.sec.android.app.shealth"

    private fun t(text: String): Instant = Instant.parse(text)

    private fun span(from: String, to: String, stage: SleepStage) = StageSpan(t("2026-11-12T$from:00Z"), t("2026-11-12T$to:00Z"), stage)

    /** Erste Sitzung 01:36–09:57 Ortszeit: 95 Min. bis zum Einschlafen, 60 Min. wach, 346 Min. Schlaf. */
    private val night = HealthSession(
        t("2026-11-12T00:36:00Z"), t("2026-11-12T08:57:00Z"),
        listOf(
            span("00:36", "02:11", SleepStage.AWAKE),
            span("02:11", "04:00", SleepStage.LIGHT),
            span("04:00", "05:00", SleepStage.DEEP),
            span("05:00", "06:00", SleepStage.AWAKE),
            span("06:00", "08:57", SleepStage.REM),
        ),
        samsung,
    )

    /** Zweite Sitzung 09:58–11:56 Ortszeit (nach dem Wecker um 09:57): 26 Min. wach, 92 Min. Schlaf. */
    private val sleptOn = HealthSession(
        t("2026-11-12T08:58:00Z"), t("2026-11-12T10:56:00Z"),
        listOf(span("08:58", "09:24", SleepStage.AWAKE), span("09:24", "10:56", SleepStage.LIGHT)),
        samsung,
    )

    /** Echtes Nickerchen 14:00–15:00 Ortszeit, über eine Stunde nach dem Ende der Nacht. */
    private val nap = HealthSession(t("2026-11-12T13:00:00Z"), t("2026-11-12T14:00:00Z"), source = samsung)

    private val nov11 = LocalDate.of(2026, 11, 11)

    @Test
    fun realNightCountsBothParts() {
        // Für sich allein wäre die zweite Sitzung ein Nickerchen (kurz, ganz am Tag).
        assertTrue(sleptOn.isNap(zone))
        val sessions = listOf(nap, sleptOn, night)
        // Verlauf und Detailseite: eine Nacht im Bett 01:36–11:56, Schlaf 7 h 18, Einschlafen um 03:11.
        val session = HealthHistory.merge(emptyList(), sessions, null, zone).single().let {
            assertEquals(HistoryKind.HEALTH, it.kind)
            assertEquals(nov11, it.date)
            it.session!!
        }
        assertEquals(night.start to sleptOn.end, session.start to session.end)
        assertEquals(620, session.durationMinutes)
        assertEquals(438, session.asleepMinutes)
        assertEquals(181L, session.awakeMinutes)
        assertEquals(t("2026-11-12T02:11:00Z"), session.asleepAt)
        assertEquals(samsung, session.source)
        assertEquals(438, StageStats.forSession(session)!!.asleepMinutes)
        // Diagramme, Trends, Schlafziel: 03:11–11:56 (8 h 45 mit der Minute Lücke), Schlaf beider Teile.
        val sleeps = HealthHistory.summaries(sessions, null, zone)
        assertEquals(listOf(SleepSummary(t("2026-11-12T02:11:00Z"), sleptOn.end, 438)), sleeps)
        assertEquals(525, java.time.Duration.between(sleeps.single().start, sleeps.single().end).toMinutes())
        assertEquals(listOf(nov11 to 438L), SleepGoal.nightsOf(sleeps, zone))
        assertEquals(nov11 to 438L, ChartData.durations(sleeps, zone, nov11.plusDays(1), goalMinutes = 480)!!.bars.last().let { it.date to it.asleepMinutes })
        val bar = ChartData.sleepBars(sleeps, zone, nov11.plusDays(1), nextTarget = null, goalMinutes = 480)!!.bars.last()
        assertEquals(Regularity.minutesFromNoon(sleptOn.end, zone).toInt(), bar.endMinute)
        // Phasen für eigene Nächte: beide Teile, das Nickerchen nicht.
        assertEquals(night.stages + sleptOn.stages, HealthHistory.stages(sessions, null, zone))
    }

    @Test
    fun ownNightIncludesSleepingOn() {
        // Phase Wake weckte um 09:57 (Fenster 09:27–09:57 Ortszeit): Schlafprofil und Werte bis 11:56.
        val own = NightRecord(
            alarmId = "a",
            windowStart = t("2026-11-12T08:27:00Z"),
            target = t("2026-11-12T08:57:00Z"),
            wokeAt = t("2026-11-12T08:57:00Z"),
            reason = WakeReason.LIGHT_SLEEP,
            snoozes = 0,
            epochs = emptyList(),
        )
        val sessions = listOf(night, sleptOn)
        assertEquals(listOf(HistoryKind.PHASE_WAKE), HealthHistory.merge(listOf(own), sessions, null, zone).map { it.kind })
        val stages = HealthHistory.stages(sessions, null, zone)
        assertEquals(sleptOn.end, ChartData.hypnogram(stages, own)!!.end)
        assertEquals(438, StageStats.forNight(stages, own)!!.asleepMinutes)
    }

    @Test
    fun realNapStaysNap() {
        // Mehr als 60 Minuten nach dem Ende der Nacht: Nickerchen, zählt nirgends.
        val late = sleptOn.copy(start = night.end.plusSeconds(61 * 60), stages = emptyList())
        assertTrue(late.isNap(zone))
        assertEquals(listOf(night), HealthHistory.merge(emptyList(), listOf(night, late), null, zone).map { it.session })
        assertEquals(listOf(346L), HealthHistory.summaries(listOf(night, late), null, zone).map { it.asleepMinutes })
        assertEquals(night.stages, HealthHistory.stages(listOf(night, late), null, zone))
        // Genau 60 Minuten: setzt die Nacht noch fort (einschliesslich).
        val edge = HealthSession(night.end.plusSeconds(60 * 60), night.end.plusSeconds(90 * 60))
        assertEquals(edge.end, HealthHistory.merge(emptyList(), listOf(night, edge), null, zone).single().session!!.end)
        assertEquals(java.time.Duration.ofMinutes(60), HealthHistory.CONTINUATION_GAP)
    }

    @Test
    fun chainOfShortSessions() {
        // Zwei kurze Sitzungen nacheinander (je höchstens 60 Min. Lücke) gehören beide zur Nacht; ohne Phasen
        // zählt jede über ihre Dauer, die Lücken nicht.
        val second = HealthSession(t("2026-11-12T09:30:00Z"), t("2026-11-12T10:00:00Z"))
        val third = HealthSession(t("2026-11-12T10:45:00Z"), t("2026-11-12T11:15:00Z"))
        val joined = HealthHistory.merge(emptyList(), listOf(third, night, second), null, zone).single().session!!
        assertEquals(night.start to third.end, joined.start to joined.end)
        assertEquals(346 + 30 + 30, joined.asleepMinutes)
        assertEquals(night.stages.size + 2, joined.stages.size)
        assertEquals(listOf(406L), HealthHistory.summaries(listOf(third, night, second), null, zone).map { it.asleepMinutes })
        // Ein Nickerchen ohne Nacht davor setzt nichts fort.
        assertTrue(HealthHistory.merge(emptyList(), listOf(second, third), null, zone).isEmpty())
    }

    @Test
    fun overlappingShortSessionIsNoContinuation() {
        // Eine andere App schreibt einen kurzen Teil, der sich mit der Nacht überschneidet: Doppel, zählt nicht.
        val other = HealthSession(t("2026-11-12T08:30:00Z"), t("2026-11-12T09:30:00Z"), source = "other")
        assertEquals(listOf(night), HealthHistory.merge(emptyList(), listOf(night, other), null, zone).map { it.session })
        // Zwei Apps schreiben dasselbe Weiterschlafen: nur einmal gezählt.
        val copy = sleptOn.copy(stages = emptyList(), source = "other")
        assertEquals(listOf(438L), HealthHistory.summaries(listOf(night, sleptOn, copy), null, zone).map { it.asleepMinutes })
    }

    @Test
    fun shortOverlapIsStillAContinuation() {
        // Audit 2c82e15: Health Connect schreibt die Teile oft mit einer Sekunde Überlappung – trotzdem Weiterschlafen.
        val overlapping = sleptOn.copy(
            start = night.end.minusSeconds(1),
            stages = listOf(StageSpan(night.end.minusSeconds(1), t("2026-11-12T09:24:00Z"), SleepStage.AWAKE), span("09:24", "10:56", SleepStage.LIGHT)),
        )
        val session = HealthHistory.merge(emptyList(), listOf(night, overlapping), null, zone).single().session!!
        assertEquals(night.start to overlapping.end, session.start to session.end)
        // Die Sekunde zählt nicht doppelt; Schlaf wie ohne Überlappung: 346 + 92.
        assertEquals(438, session.asleepMinutes)
        assertEquals(listOf(438L), HealthHistory.summaries(listOf(night, overlapping), null, zone).map { it.asleepMinutes })
        // Auch ein langer zweiter Teil (keine Nickerchen-Länge) mit Sekunden-Überlappung bleibt, statt als Doppel zu fallen.
        val long = HealthSession(night.end.minusSeconds(1), night.end.plus(java.time.Duration.ofHours(4)), source = samsung)
        assertEquals(night.end.plus(java.time.Duration.ofHours(4)), HealthHistory.merge(emptyList(), listOf(night, long), null, zone).single().session!!.end)
    }

    @Test
    fun oneRuleForOneNightEverywhere() {
        // Entscheid r1AD 14.11. (Audit W-2): eine Nacht = Kette mit Lücken bis 60 Min – im Verlauf wie in Diagrammen,
        // Ø, Schlafziel und Trends. 22:00–02:00 und 03:30–07:30 (90 Min Lücke) sind zwei Nächte am selben Nachtdatum.
        val first = HealthSession(t("2026-11-10T21:00:00Z"), t("2026-11-11T01:00:00Z"))
        val second = HealthSession(t("2026-11-11T02:30:00Z"), t("2026-11-11T06:30:00Z"))
        val nov10 = LocalDate.of(2026, 11, 10)
        assertEquals(listOf(240L, 240L), HealthHistory.summaries(listOf(first, second), null, zone).map { it.asleepMinutes })
        assertEquals(listOf(nov10, nov10), SleepGoal.nightsOf(HealthHistory.summaries(listOf(first, second), null, zone), zone).map { it.first })
        // Schlafziel 8 Std.: eine Nacht gezählt (die längere), nicht erreicht – wie der Verlauf mit 4 Std.
        assertEquals(0 to 1, SleepGoal.weekStatus(SleepGoal.nightsOf(HealthHistory.summaries(listOf(first, second), null, zone), zone), 480))
        assertEquals(240L, HealthHistory.merge(emptyList(), listOf(first, second), null, zone).single().session!!.asleepMinutes)
        // Genau 60 Min Lücke: eine Nacht (480); 60 Min + 1 s: zwei.
        val close = HealthSession(first.end.plusSeconds(3600), first.end.plusSeconds(3600 + 4 * 3600))
        assertEquals(listOf(480L), HealthHistory.summaries(listOf(first, close), null, zone).map { it.asleepMinutes })
        val apart = HealthSession(first.end.plusSeconds(3601), first.end.plusSeconds(3601 + 4 * 3600))
        assertEquals(listOf(240L, 240L), HealthHistory.summaries(listOf(first, apart), null, zone).map { it.asleepMinutes })
        assertEquals(java.time.Duration.ofMinutes(60), SleepSummary.NEW_NIGHT_GAP)
    }

    @Test
    fun profileOfOwnNightLeavesOutEarlierSofaSleep() {
        // Audit W-2: Sofa 20:30–21:15, Nacht 23:00–07:00, Phase Wake weckt um 07:00: Profil ab 23:00, nicht ab 20:30.
        val sofa = HealthSession(t("2026-11-10T19:30:00Z"), t("2026-11-10T20:15:00Z"), listOf(StageSpan(t("2026-11-10T19:30:00Z"), t("2026-11-10T20:15:00Z"), SleepStage.LIGHT)))
        val bed = HealthSession(t("2026-11-10T22:00:00Z"), t("2026-11-11T06:00:00Z"), listOf(StageSpan(t("2026-11-10T22:00:00Z"), t("2026-11-11T06:00:00Z"), SleepStage.LIGHT)))
        val own = NightRecord("a", t("2026-11-11T05:30:00Z"), t("2026-11-11T06:00:00Z"), t("2026-11-11T06:00:00Z"), WakeReason.LIGHT_SLEEP, 0, emptyList())
        val stages = HealthHistory.stages(listOf(sofa, bed), null, zone)
        assertEquals(bed.start, ChartData.hypnogram(stages, own)!!.start)
        assertEquals(480L, StageStats.forNight(stages, own)!!.asleepMinutes)
        // Bis 60 Min Lücke gehört der frühere Schlaf noch dazu.
        val near = sofa.copy(start = t("2026-11-10T20:15:00Z"), end = t("2026-11-10T21:00:00Z"), stages = listOf(StageSpan(t("2026-11-10T20:15:00Z"), t("2026-11-10T21:00:00Z"), SleepStage.LIGHT)))
        assertEquals(near.start, ChartData.hypnogram(HealthHistory.stages(listOf(near, bed), null, zone), own)!!.start)
    }

    /** Erster Teil 01:36–09:57 nur «leicht» (501 Min), zweiter Teil [overlapSeconds] vor dessen Ende, 120 Min «leicht». */
    private fun overlapCase(overlapSeconds: Long): Pair<HealthSession, HealthSession> {
        val first = HealthSession(night.start, night.end, listOf(StageSpan(night.start, night.end, SleepStage.LIGHT)), samsung)
        val from = night.end.minusSeconds(overlapSeconds)
        val second = HealthSession(from, from.plusSeconds(120 * 60), listOf(StageSpan(from, from.plusSeconds(120 * 60), SleepStage.LIGHT)), samsung)
        return first to second
    }

    private fun minutes(spans: List<StageSpan>) = spans.sumOf { java.time.Duration.between(it.start, it.end).toMillis() } / 60_000

    @Test
    fun overlapWithSleepCountsOnceEverywhere() {
        // Audit BE-2: 4 Min Schlaf in der Überlappung zählen in Verlauf, Diagrammen, Trends und Schlafprofil nur einmal.
        val (first, second) = overlapCase(4 * 60)
        val sessions = listOf(first, second)
        assertEquals(617L, HealthHistory.merge(emptyList(), sessions, null, zone).single().session!!.asleepMinutes)
        assertEquals(listOf(617L), HealthHistory.summaries(sessions, null, zone).map { it.asleepMinutes })
        assertEquals(617L, minutes(HealthHistory.stages(sessions, null, zone)))
        // Genau 5:00 Überlappung: noch Anschluss, 501 + 115.
        val (a, b) = overlapCase(5 * 60)
        assertEquals(616L, HealthHistory.merge(emptyList(), listOf(a, b), null, zone).single().session!!.asleepMinutes)
        assertEquals(listOf(616L), HealthHistory.summaries(listOf(a, b), null, zone).map { it.asleepMinutes })
        assertEquals(616L, minutes(HealthHistory.stages(listOf(a, b), null, zone)))
        // 5:01: Doppel – nur der längere Teil zählt, überall gleich.
        val (c, d) = overlapCase(5 * 60 + 1)
        assertEquals(listOf(c), HealthHistory.merge(emptyList(), listOf(c, d), null, zone).map { it.session })
        assertEquals(listOf(501L), HealthHistory.summaries(listOf(c, d), null, zone).map { it.asleepMinutes })
        assertEquals(501L, minutes(HealthHistory.stages(listOf(c, d), null, zone)))
        assertEquals(java.time.Duration.ofMinutes(5), HealthHistory.OVERLAP_TOLERANCE)
    }

    @Test
    fun overlapWithoutStagesCountsOnce() {
        // Zweiter Teil ohne Phasen (ganze Dauer Schlaf), 3 Min Überlappung: 501 + 117; das Schlafprofil hat nur Phasen.
        val first = HealthSession(night.start, night.end, listOf(StageSpan(night.start, night.end, SleepStage.LIGHT)), samsung)
        val second = HealthSession(night.end.minusSeconds(3 * 60), night.end.plusSeconds(117 * 60))
        assertEquals(listOf(618L), HealthHistory.summaries(listOf(first, second), null, zone).map { it.asleepMinutes })
        assertEquals(618L, HealthHistory.merge(emptyList(), listOf(first, second), null, zone).single().session!!.asleepMinutes)
        assertEquals(first.stages, HealthHistory.stages(listOf(first, second), null, zone))
    }
}
