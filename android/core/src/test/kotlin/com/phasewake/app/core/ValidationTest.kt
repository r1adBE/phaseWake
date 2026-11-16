package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Validierungs-Modus ([Validation], [ValidationReport]); gemeinsame Fälle mit Swift in `shared/testdata/validation.vectors`. */
class ValidationTest {
    private val t0 = Instant.parse("2026-11-08T22:00:00Z")
    private val zone = ZoneId.of("Europe/Zurich")

    private fun minutes(m: String): Instant = t0.plusSeconds(Math.round(m.toDouble() * 60))

    /** «Anzahl*Bewegung/Puls» mit Leerzeichen, «-» = leer. */
    private fun epochs(text: String): List<Epoch> = text.trim().split(' ').filter { it.isNotEmpty() }.flatMap { token ->
        val (count, values) = token.split('*')
        val (motion, hr) = values.split('/')
        List(count.toInt()) { Epoch(motion.toDoubleOrNull(), hr.toDoubleOrNull()) }
    }

    /** «Beginn-Ende:PHASE» mit Kommas, «-» = keine; Zeiten über [at]. */
    private fun spans(text: String, at: (String) -> Instant): List<StageSpan> = if (text == "-") emptyList() else text.split(',').map {
        val (range, stage) = it.split(':')
        val (from, to) = range.split('-')
        StageSpan(at(from), at(to), SleepStage.valueOf(stage))
    }

    private class Case(val name: String, val zone: ZoneId) {
        val nights = mutableListOf<NightRecord>()
        val records = mutableListOf<Learning.Night>()
        val sessions = mutableListOf<HealthSession>()
        var learned: Learning.Values? = null
        val rows = mutableListOf<String>()
        val sums = mutableListOf<String>()
    }

    @Test
    fun sharedVectors() {
        val lines = NightFixtures.directory().resolve("validation.vectors").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val cases = mutableListOf<Case>()
        var refs = 0
        for (line in lines) {
            val f = line.split('\t')
            val case = cases.lastOrNull()
            when (f[0]) {
                "ref" -> {
                    val at = t0.plusSeconds(f[2].toLong())
                    val stage = Validation.reference(spans(f[3]) { t0.plusSeconds(it.toLong()) }, at)
                    assertEquals(f[1], Validation.Ref.of(stage).code, line)
                    refs++
                }
                "case" -> cases += Case(f[1], ZoneId.of(f[2]))
                "night" -> {
                    val (start, target, woke, reason) = f[1].split(' ')
                    case!!.nights += NightRecord("a", minutes(start), minutes(target), minutes(woke), WakeReason.valueOf(reason), 0, epochs(f.getOrElse(2) { "" }))
                }
                "record" -> case!!.records += Learning.Night(minutes(f[1]), epochs(f[2]))
                "session" -> {
                    val (start, end, local) = f[1].split(' ')
                    case!!.sessions += HealthSession(minutes(start), minutes(end), spans(f[2], ::minutes), timeZone = if (local == "-") null else ZoneId.of(local))
                }
                "learned" -> {
                    val (floor, resting) = f[1].split(' ')
                    case!!.learned = Learning.Values(floor.toDoubleOrNull(), resting.toDoubleOrNull(), null, Learning.NIGHTS)
                }
                "row" -> case!!.rows += f[1]
                "sum" -> case!!.sums += f[1]
                else -> error("unbekannte Zeile: $line")
            }
        }
        for (case in cases) {
            val entries = Validation.evaluate(case.nights, case.records, case.sessions, null, case.learned, case.zone)
            assertEquals(case.rows, ValidationReport.exportCsv(entries).lines().drop(1).filter { it.isNotEmpty() }, case.name)
            val sum = Regex("night \\d+: .*")
            assertEquals(case.sums, ValidationReport.lines(entries).filter { it.startsWith("all:") || sum.matches(it) }, case.name)
        }
        assertEquals(18, refs)
        assertEquals(8, cases.size)
    }

    @Test
    fun windowsAndMeasured() {
        val quiet = Epoch(0.02, 55.0)
        // 45 von 60 Epochen mit Bewegung zählen, 44 nicht; leer nie.
        assertTrue(Validation.measured(List(15) { Epoch(null, null) } + List(45) { quiet }))
        assertEquals(false, Validation.measured(List(16) { Epoch(null, null) } + List(44) { quiet }))
        assertEquals(false, Validation.measured(emptyList()))
        // 119 Epochen: Fenster ab 0 und 30 (das ab 60 hat keinen Platz); ruhig = Zielzeit am Ende des Fensters.
        val moments = Validation.shadows(t0, List(119) { quiet }, DetectorConfig())
        assertEquals(listOf(t0.plus(Duration.ofMinutes(30)), t0.plus(Duration.ofMinutes(45))), moments.map { it.at })
        assertTrue(moments.all { it.reason == WakeReason.DEADLINE })
        assertEquals(3, Validation.shadows(t0, List(120) { quiet }, DetectorConfig()).size)
        assertEquals(0, Validation.shadows(t0, List(59) { quiet }, DetectorConfig()).size)
    }

    @Test
    fun nightWithoutDecisionIsLeftOut() {
        // Nur Stichproben, keine eigene Nacht: kein Entscheid, keine Nacht, kein Abschnitt.
        val sampled = (0 until 12).flatMap { List(2) { Epoch(0.02, 55.0) } + List(8) { Epoch(null, null) } }
        val entries = Validation.evaluate(emptyList(), listOf(Learning.Night(t0, sampled)), emptyList(), null, null, zone)
        assertEquals(emptyList(), entries)
        assertEquals(emptyList(), ValidationReport.lines(entries))
        assertEquals("night,time,kind,reason,ref_stage\n", ValidationReport.exportCsv(entries))
    }

    @Test
    fun diagLines() {
        val night = NightRecord("a", t0, t0.plus(Duration.ofMinutes(30)), t0.plus(Duration.ofMinutes(20)), WakeReason.LIGHT_SLEEP, 0, emptyList())
        val session = HealthSession(t0.minus(Duration.ofHours(6)), t0.plus(Duration.ofHours(1)), listOf(StageSpan(t0.minus(Duration.ofHours(6)), t0.plus(Duration.ofHours(1)), SleepStage.DEEP)))
        val lines = ValidationReport.lines(Validation.evaluate(listOf(night), emptyList(), listOf(session), null, null, zone))
        assertEquals(
            listOf(
                ValidationReport.NOTE,
                "night 1 23:20 real LIGHT_SLEEP deep",
                "night 1: LIGHT_SLEEP real 0/1 shadow - · target real - shadow - · hits real 0/1",
                "all: 1 nights, 1 with reference · LIGHT_SLEEP real 0/1 shadow - · target real - shadow - · hits real 0/1",
            ),
            lines,
        )
        // «Verlauf löschen» blendet die Sitzung aus: keine Referenz mehr.
        val cleared = Validation.evaluate(listOf(night), emptyList(), listOf(session), t0.plus(Duration.ofHours(2)), null, zone)
        assertEquals(listOf(Validation.Ref.NONE), cleared.map { it.ref })
    }
}
