package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Gemeinsame Fälle mit Swift (`shared/testdata/schedule.vectors`, Audit 17.11.2026 CODE-03): [AlarmSchedule] mit
 * Wochentagen, Feiertagen, Überspringen, erledigten Nächten, Nachfrist, begonnener Nacht über einen Zonenwechsel und
 * Nachholen, auch über die Sommer- und Winterzeit in Zürich. Gegenstück: `ScheduleVectorsTests` (Swift).
 */
class ScheduleVectorsTest {

    @Test
    fun sharedVectors() {
        val lines = ScheduleVectors.lines()
        for (line in lines) {
            val fields = line.split('\t')
            assertEquals(fields.takeLast(2).map(ScheduleVectors::expected), ScheduleVectors.actual(fields).map { it.first }, line)
        }
        assertEquals(mapOf("next" to 29, "planned" to 20, "missed" to 12), lines.groupingBy { it.substringBefore('\t') }.eachCount())
    }
}

/**
 * Leser von `schedule.vectors` (Format im Kopf der Datei). [actual] rechnet die beiden letzten Felder einer Zeile mit dem
 * heutigen Kern aus: je Paar der Vergleichswert (Zeitpunkte als ms) und der Text, wie er in der Datei steht.
 */
internal object ScheduleVectors {
    fun lines(): List<String> =
        NightFixtures.directory().resolve("schedule.vectors").readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }

    /** Die beiden erwarteten Felder der Zeile [f], so wie der Kern sie heute ergibt. */
    fun actual(f: List<String>): List<Pair<String, String>> {
        val now = instant(f[1])
        val zone = ZoneId.of(f[2])
        return when (f[0]) {
            "next" -> {
                require(f.size == 8) { "next: 8 Felder erwartet" }
                val holidays = dates(f[3])
                val done = f[4].takeIf { it != "-" }?.let(::instant)
                val spec = alarm(f[5])
                val next = AlarmSchedule.nextTarget(spec, maxOf(now, done ?: now), zone, holidays)
                val earliest = AlarmSchedule.earliestPlan(listOf(spec), now, zone, holidays, done?.let { mapOf(spec.id to it) }.orEmpty())
                check(earliest?.target == next) { "earliestPlan weicht von nextTarget ab" }
                val skip = AlarmSchedule.skipNext(spec, now, zone, holidays, done).skipUntil
                listOf(text(next, zone), wall(skip))
            }
            "planned" -> {
                require(f.size == 11) { "planned: 11 Felder erwartet" }
                val plannedZone = f[3].takeIf { it != "-" }?.let(ZoneId::of)
                val holidays = dates(f[4])
                val spec = alarm(f[5])
                val target = f[6].takeIf { it != "-" }?.let(::instant)
                val grace = Duration.ofMinutes(f[7].toLong())
                val suppressed = f[8].takeIf { it != "-" }?.let(::instant)
                val inGrace = AlarmSchedule.inGrace(spec, target, now, grace, zone, holidays, suppressed)
                val begun = AlarmSchedule.begunNight(spec, target, plannedZone, now, grace, holidays, suppressed)
                listOf(inGrace, begun).map { plan ->
                    check(plan == null || plan == AlarmSchedule.planAt(spec, target!!)) { "Plan nicht zur geplanten Zielzeit" }
                    if (plan == null) "-" to "-" else "+" to "+"
                }
            }
            "missed" -> {
                require(f.size == 9) { "missed: 9 Felder erwartet" }
                val holidays = dates(f[3])
                val done = if (f[4] == "-") emptyMap() else f[4].split(',').associate { it.substringBefore('=') to instant(it.substringAfter('=')) }
                val catchUp = f[5].takeIf { it != "-" }?.split(' ')?.let { CatchUpWindow(instant(it[0]), instant(it[1]), it[2].split(',').toSet()) }
                val specs = f[6].split(';').map(::alarm)
                val missed = AlarmSchedule.missedPlan(specs, catchUp, now, zone, holidays, done)
                val earliest = AlarmSchedule.earliestPlan(specs, now, zone, holidays, done)
                listOf(missed, earliest).map { plan -> plan(plan, zone) }
            }
            else -> error("Unbekannte Art ${f[0]}")
        }
    }

    /** Eine Weckerzeile im `Codec`-Format (16 Felder). */
    private fun alarm(line: String): AlarmSpec =
        Codec.decodeAlarms("phasewake-alarms 1\n$line\n").singleOrNull() ?: error("Weckerzeile unlesbar: $line")

    private fun dates(text: String): Set<LocalDate> = if (text == "-") emptySet() else text.split(',').map(LocalDate::parse).toSet()

    private fun instant(text: String): Instant = OffsetDateTime.parse(text).toInstant()

    private val format: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    /** Zeitpunkt (ms, Text mit Abstand der Zone) oder «-». */
    private fun text(at: Instant?, zone: ZoneId): Pair<String, String> =
        if (at == null) "-" to "-" else at.toEpochMilli().toString() to at.atZone(zone).format(format)

    /** `skipUntil` als Wandzeit «yyyy-MM-ddTHH:mm:ss» oder «0». */
    private fun wall(ms: Long): Pair<String, String> =
        if (ms == 0L) "0" to "0" else ms.toString() to LocalDateTime.ofEpochSecond(ms / 1000, 0, ZoneOffset.UTC).toString().let { if (it.length == 16) "$it:00" else it }

    /** Plan als «Kennung@Zielzeit» oder «-». */
    private fun plan(plan: WakePlan?, zone: ZoneId): Pair<String, String> {
        if (plan == null) return "-" to "-"
        val (ms, shown) = text(plan.target, zone)
        return "${plan.alarmId}@$ms" to "${plan.alarmId}@$shown"
    }

    /** Erwartetes Feld [field] als Vergleichswert wie [actual] (Zeitpunkte als ms). */
    fun expected(field: String): String = when {
        field == "-" || field == "+" || field == "0" -> field
        '@' in field -> "${field.substringBefore('@')}@${instant(field.substringAfter('@')).toEpochMilli()}"
        field.length == 19 -> LocalDateTime.parse(field).toInstant(ZoneOffset.UTC).toEpochMilli().toString()
        else -> instant(field).toEpochMilli().toString()
    }
}
