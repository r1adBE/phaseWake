package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FindingsTest {
    private val zone: ZoneId = ZoneId.of("Europe/Zurich")

    @Test
    fun sharedVectors() {
        // Gemeinsame Fälle mit Swift (shared/testdata/findings.vectors).
        val cases = NightFixtures.directory().resolve("findings.vectors").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
        for (line in cases) {
            val (expected, today, nights) = line.split('\t')
            val facts = nights.split(';').map(::facts)
            assertEquals(expected, text(Findings.of(facts, LocalDate.parse(today), zone)), line.take(80))
        }
        assertEquals(45, cases.size)
    }

    /** «Nachtdatum Beginn Ende Schlaf Einschlafdauer Tief Wach [Zone]»; Beginn vor 12:00 am Tag danach, Ende danach. */
    private fun facts(token: String): NightFacts {
        val f = token.trim().split(' ')
        val date = LocalDate.parse(f[0])
        val local = f.getOrNull(7)?.let(ZoneId::of)
        val bedTime = LocalTime.parse(f[1])
        val bed = ZonedDateTime.of(if (bedTime.hour < 12) date.plusDays(1) else date, bedTime, local ?: zone)
        var end = bed.with(LocalTime.parse(f[2]))
        if (!end.isAfter(bed)) end = end.plusDays(1)
        fun num(i: Int) = f[i].takeIf { it != "-" }?.toLong()
        return NightFacts(date, bed.toInstant(), end.toInstant(), local, f[3].toLong(), num(4), num(5), num(6))
    }

    private fun text(findings: List<Finding>): String = if (findings.isEmpty()) "-" else findings.joinToString(",") { f ->
        "${f.kind}=${f.value}" + (f.link?.let { "/$it/${f.withPercent}/${f.withoutPercent}" } ?: "")
    }

    private fun at(day: Int, hour: Int, minute: Int = 0): Instant = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant()

    private fun span(from: Instant, minutes: Long, stage: SleepStage) = StageSpan(from, from.plus(Duration.ofMinutes(minutes)), stage)

    /** Sitzung wie Samsung Health: wach bis zum Einschlafen, Tief, Leicht, Wach-Rest am Ende. */
    private fun session(day: Int, bedHour: Int, bedMinute: Int, latency: Long, deep: Long, asleep: Long, awakeAfter: Long, stages: Boolean = true): HealthSession {
        val asleepAt = at(day, bedHour, bedMinute)
        val start = asleepAt.minus(Duration.ofMinutes(latency))
        val end = asleepAt.plus(Duration.ofMinutes(asleep + awakeAfter))
        if (!stages) return HealthSession(start, end)
        val light = asleepAt.plus(Duration.ofMinutes(deep))
        return HealthSession(
            start, end,
            listOf(
                span(start, latency, SleepStage.AWAKE), span(asleepAt, deep, SleepStage.DEEP),
                span(light, asleep - deep, SleepStage.LIGHT), span(light.plus(Duration.ofMinutes(asleep - deep)), awakeAfter, SleepStage.AWAKE),
            ),
            timeZone = zone,
        )
    }

    @Test
    fun ownerNightsFromSamsungSessions() {
        // Echte Werte (Samsung, Nächte 8.–14.11.2026): Einschlafbeginn und Aufwachen (am Morgen danach), Schlaf, Einschlafdauer, Tief.
        val own = listOf(
            "01:56 09:59 434 15 57", "03:08 11:25 432 51 42", "02:43 07:57 284 55 20", "01:36 10:23 474 103 67",
            "03:11 11:56 438 95 56", "02:29 11:59 479 75 64", "04:30 11:25 353 85 15",
        )
        val sessions = own.mapIndexed { i, line ->
            val v = line.split(' ')
            val bed = LocalTime.parse(v[0])
            val inBed = Duration.between(bed, LocalTime.parse(v[1])).toMinutes()
            session(9 + i, bed.hour, bed.minute, v[3].toLong(), v[4].toLong(), v[2].toLong(), inBed - v[2].toLong())
        }
        val facts = NightFacts.of(sessions, null, zone)
        assertEquals((8..14).map { LocalDate.of(2026, 11, it) }, facts.map { it.date })
        assertEquals(listOf(15L, 51, 55, 103, 95, 75, 85), facts.map { it.latencyMinutes })
        assertEquals(listOf(57L, 42, 20, 67, 56, 64, 15), facts.map { it.deepMinutes })
        assertEquals(listOf(434L, 432, 284, 474, 438, 479, 353), facts.map { it.asleepMinutes })
        val findings = Findings.of(facts, LocalDate.of(2026, 11, 14), zone)
        assertEquals("LONG_LATENCY=68,LOW_DEEP=11/SHORT_NIGHTS/5/13,BEDTIME_VARIES=174", text(findings))
        // Startseite: die wichtigste – lange Einschlafdauer; ausgeblendet die nächstwichtige.
        assertEquals(FindingKind.LONG_LATENCY, Findings.home(findings, emptyMap(), at(15, 9))?.kind)
        val hidden = Findings.hide(emptyMap(), FindingKind.LONG_LATENCY, at(15, 9))
        assertEquals(FindingKind.LOW_DEEP, Findings.home(findings, hidden, at(15, 9, 1))?.kind)
        assertEquals(FindingKind.BEDTIME_VARIES, Findings.home(findings, Findings.hide(hidden, FindingKind.LOW_DEEP, at(15, 10)), at(15, 11))?.kind)
        // Nacht-Detail: 42 von 432 Min Schlaf = 10 % (gleich gerechnet wie die Regel).
        assertEquals(10, Findings.deepPercent(42, 432))
        assertNull(Findings.deepPercent(42, 0))
    }

    @Test
    fun factsWithoutStagesHaveNoPhases() {
        val facts = NightFacts.of(session(9, 1, 0, 20, 60, 400, 20, stages = false), zone)
        assertNull(facts.deepMinutes)
        assertNull(facts.awakeMinutes)
        assertNull(facts.latencyMinutes)
        assertEquals(440, facts.asleepMinutes)
        // Mit Phasen, ohne Wachphase am Anfang: Einschlafdauer unbekannt, Wach nach dem Einschlafen bleibt.
        val direct = NightFacts.of(session(9, 1, 0, 0, 60, 400, 30).let { s -> s.copy(stages = s.stages.drop(1)) }, zone)
        assertNull(direct.latencyMinutes)
        assertEquals(30, direct.awakeMinutes)
    }

    @Test
    fun ownOnsetVectors() {
        // Gemeinsame Fälle mit Swift (shared/testdata/findings-onset.vectors); Minuten ab 2026-11-08 22:00 UTC.
        val t0 = Instant.parse("2026-11-08T22:00:00Z")
        fun t(m: String): Instant = t0.plus(Duration.ofMinutes(m.toLong()))
        val cases = NightFixtures.directory().resolve("findings-onset.vectors").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
        for (line in cases) {
            val parts = line.split('\t')
            val (start, end) = parts[1].split(' ')
            val own = parts[2].split(';').filter { it.isNotBlank() }.map { r -> r.split(' ').let { OwnOnset(t(it[0]), t(it[1]), t(it[2]), t(it[3])) } }
            // Nächte der Uhr über NightRecord (Felder 11 und 12) und OwnOnset.of.
            val watch = parts.getOrElse(3) { "" }.split(';').filter { it.isNotBlank() }.mapNotNull { r ->
                r.split(' ').let { OwnOnset.of(NightRecord("a", t(it[2]), t(it[2]), t(it[2]), WakeReason.DEADLINE, 0, emptyList(), bedAt = t(it[0]), asleepAt = t(it[1]))) }
            }
            assertEquals(parts[0], Findings.ownLatency(t(start), t(end), own, watch)?.toString() ?: "-", line)
        }
        assertEquals(13, cases.size)
    }

    @Test
    fun healthLatencyBeforeOwnMeasurement() {
        // Samsung-Nacht mit Wachphase am Anfang (Einschlafdauer 20): die Gesundheits-App geht vor.
        val withLatency = session(9, 1, 0, 20, 60, 400, 30)
        val own = listOf(OwnOnset(withLatency.start.minusSeconds(600), withLatency.end, withLatency.start, withLatency.start.plus(Duration.ofMinutes(50))))
        assertEquals(20, NightFacts.of(withLatency, zone, own).latencyMinutes)
        // Ohne Wachphase am Anfang (Apple Watch): die eigene Messung (50 Min); Wach nach dem Einschlafen bleibt 30.
        val direct = withLatency.copy(stages = withLatency.stages.drop(1))
        val facts = NightFacts.of(direct, zone, own)
        assertEquals(50, facts.latencyMinutes)
        assertEquals(30, facts.awakeMinutes)
        assertNull(NightFacts.of(direct, zone).latencyMinutes)
        // Ganz ohne Phasen ebenso; über die Liste der Sitzungen gleich.
        assertEquals(50, NightFacts.of(session(9, 1, 0, 20, 60, 400, 30, stages = false), zone, own).latencyMinutes)
        assertEquals(listOf<Long?>(50), NightFacts.of(listOf(direct), null, zone, own).map { it.latencyMinutes })
        // Nacht der Uhr (Felder 11 und 12) vor der Aufzeichnung: 35 statt 50; ohne Einschlafzeit keine Messung.
        val night = NightRecord("a", withLatency.end, withLatency.end, withLatency.end, WakeReason.DEADLINE, 0, emptyList(),
            bedAt = withLatency.start, asleepAt = withLatency.start.plus(Duration.ofMinutes(35)))
        assertEquals(35, NightFacts.of(direct, zone, own, listOfNotNull(OwnOnset.of(night))).latencyMinutes)
        assertEquals(20, NightFacts.of(withLatency, zone, own, listOfNotNull(OwnOnset.of(night))).latencyMinutes)
        assertNull(OwnOnset.of(night.copy(asleepAt = null)))
        assertNull(OwnOnset.of(night.copy(bedAt = null)))
    }

    @Test
    fun hiddenFindingReturnsAfterSevenDays() {
        val findings = listOf(Finding(FindingKind.LONG_LATENCY, 60), Finding(FindingKind.BEDTIME_VARIES, 120))
        val hiddenAt = at(10, 8)
        val hidden = Findings.hide(emptyMap(), FindingKind.LONG_LATENCY, hiddenAt)
        // Ausgeblendet: die nächste; genau 7 Tage danach wieder die wichtigste.
        assertEquals(FindingKind.BEDTIME_VARIES, Findings.home(findings, hidden, hiddenAt.plus(Duration.ofDays(7)).minusMillis(1))?.kind)
        assertEquals(FindingKind.LONG_LATENCY, Findings.home(findings, hidden, hiddenAt.plus(Duration.ofDays(7)))?.kind)
        val both = Findings.hide(hidden, FindingKind.BEDTIME_VARIES, hiddenAt.plusSeconds(60))
        assertNull(Findings.home(findings, both, hiddenAt.plus(Duration.ofDays(1))))
        assertNull(Findings.home(emptyList(), emptyMap(), hiddenAt))
        // Abgelaufene fallen beim nächsten Ausblenden weg.
        assertEquals(setOf(FindingKind.SHORT_NIGHTS), Findings.hide(hidden, FindingKind.SHORT_NIGHTS, hiddenAt.plus(Duration.ofDays(8))).keys)
    }

    @Test
    fun bedtimeCardDefersFindingsWithTheSameTip() {
        val findings = listOf(Finding(FindingKind.SHORT_NIGHTS, 3), Finding(FindingKind.LOW_DEEP, 11), Finding(FindingKind.BEDTIME_VARIES, 120))
        val now = at(15, 21)
        // Ohne Bettzeit-Karte wie bisher die wichtigste; mit ihr tritt der gleiche Tipp zurück.
        assertEquals(FindingKind.SHORT_NIGHTS, Findings.home(findings, emptyMap(), now)?.kind)
        assertEquals(FindingKind.LOW_DEEP, Findings.home(findings, emptyMap(), now, bedtimeAdvice = true)?.kind)
        assertNull(Findings.home(listOf(findings[0], findings[2]), emptyMap(), now, bedtimeAdvice = true))
        assertEquals(setOf(FindingKind.SHORT_NIGHTS, FindingKind.BEDTIME_VARIES), Findings.BEDTIME_TIP)
    }

    @Test
    fun hiddenEncodesAndDecodes() {
        val hidden = mapOf(FindingKind.BEDTIME_VARIES to Instant.ofEpochMilli(2000), FindingKind.LONG_LATENCY to Instant.ofEpochMilli(1000))
        val text = Findings.encodeHidden(hidden)
        assertEquals("LONG_LATENCY|1000\nBEDTIME_VARIES|2000", text)
        assertEquals(hidden, Findings.decodeHidden(text))
        assertEquals(mapOf(FindingKind.MUCH_AWAKE to Instant.ofEpochMilli(5)), Findings.decodeHidden("OTHER|3\nMUCH_AWAKE|5\nLOW_DEEP|x\n\n"))
        assertEquals(emptyMap(), Findings.decodeHidden(null))
    }

    private fun night(day: Int, asleep: Long, deep: Long?) =
        NightFacts(LocalDate.of(2026, 11, day), at(day + 1, 0), at(day + 1, 8), null, asleep, null, deep, deep?.let { 20L })

    @Test
    fun weeklyDeepComparesWithTheWeekBefore() {
        // Woche So 8. bis Sa 14.11., Vorwoche 1. bis 7.11.; je mindestens 3 Nächte mit Phasen.
        val now = listOf(night(8, 400, 40), night(10, 400, 44), night(12, 400, 48), night(13, 400, null))
        val before = listOf(night(2, 400, 52), night(4, 400, 52), night(6, 400, 56))
        assertEquals(11 to 13, Findings.weekDeep(now + before, LocalDate.of(2026, 11, 14)))
        assertNull(Findings.weekDeep(now + before.take(2), LocalDate.of(2026, 11, 14)))
        assertNull(Findings.weekDeep(now.drop(1) + before, LocalDate.of(2026, 11, 14)))
    }
}
