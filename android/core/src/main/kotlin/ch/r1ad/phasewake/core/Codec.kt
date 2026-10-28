package ch.r1ad.phasewake.core

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.DayOfWeek
import java.time.Instant

/** Fehler beim Lesen gespeicherter oder empfangener Daten. */
class CodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Textformat für Wecker und Nächte. Dient gleichzeitig als Speicherformat auf dem Gerät und als
 * Inhalt der Datenpakete zwischen Handy und Uhr. Versioniert über die erste Zeile.
 *
 * Bewusst ohne Bibliothek: einfach zu lesen, vollständig testbar, auf beiden Seiten identisch.
 */
object Codec {
    private const val ALARMS_HEADER = "phasewake-alarms 1"
    private const val NIGHT_HEADER = "phasewake-night 1"
    private const val SEP = "|"

    private val dayCodes = mapOf(
        DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
        DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
    )
    private val daysByCode = dayCodes.entries.associate { (day, code) -> code to day }

    fun encodeAlarms(alarms: List<AlarmSpec>): String = buildString {
        append(ALARMS_HEADER).append('\n')
        for (a in alarms) {
            append(listOf(
                escape(a.id),
                a.hour.toString(),
                a.minute.toString(),
                a.days.sorted().joinToString(",") { dayCodes.getValue(it) },
                a.windowMinutes.toString(),
                if (a.enabled) "1" else "0",
                if (a.phoneBackup) "1" else "0",
                a.sleepDurationMinutes?.toString().orEmpty(),
                escape(a.label),
            ).joinToString(SEP)).append('\n')
        }
    }

    fun decodeAlarms(text: String): List<AlarmSpec> {
        val lines = text.lines().filter { it.isNotEmpty() }
        if (lines.firstOrNull() != ALARMS_HEADER) throw CodecException("Unbekanntes Weckerformat")
        return lines.drop(1).mapIndexed { index, line ->
            parse("Wecker ${index + 1}") {
                val f = line.split(SEP)
                require(f.size == 9) { "9 Felder erwartet, ${f.size} gefunden" }
                AlarmSpec(
                    id = unescape(f[0]),
                    hour = f[1].toInt(),
                    minute = f[2].toInt(),
                    days = if (f[3].isEmpty()) emptySet() else f[3].split(",").map {
                        daysByCode[it] ?: throw IllegalArgumentException("Wochentag '$it'")
                    }.toSet(),
                    windowMinutes = f[4].toInt(),
                    enabled = f[5] == "1",
                    phoneBackup = f[6] == "1",
                    sleepDurationMinutes = f[7].ifEmpty { null }?.toInt(),
                    label = unescape(f[8]),
                )
            }
        }
    }

    fun encodeNight(night: NightRecord): String = buildString {
        append(NIGHT_HEADER).append('\n')
        append(listOf(
            escape(night.alarmId),
            night.windowStart.toEpochMilli().toString(),
            night.target.toEpochMilli().toString(),
            night.wokeAt.toEpochMilli().toString(),
            night.reason.name,
            night.snoozes.toString(),
        ).joinToString(SEP)).append('\n')
        append(night.epochs.joinToString(",") { e ->
            // toString() liefert die kürzeste exakt rücklesbare Darstellung.
            (e.motion?.toString() ?: "") + ";" + (e.heartRate?.toString() ?: "")
        }).append('\n')
    }

    fun decodeNight(text: String): NightRecord {
        val lines = text.lines()
        if (lines.firstOrNull() != NIGHT_HEADER) throw CodecException("Unbekanntes Nachtformat")
        return parse("Nacht") {
            val f = lines.getOrElse(1) { "" }.split(SEP)
            require(f.size == 6) { "6 Felder erwartet, ${f.size} gefunden" }
            val epochLine = lines.getOrElse(2) { "" }
            NightRecord(
                alarmId = unescape(f[0]),
                windowStart = Instant.ofEpochMilli(f[1].toLong()),
                target = Instant.ofEpochMilli(f[2].toLong()),
                wokeAt = Instant.ofEpochMilli(f[3].toLong()),
                reason = WakeReason.valueOf(f[4]),
                snoozes = f[5].toInt(),
                epochs = if (epochLine.isEmpty()) emptyList() else epochLine.split(",").map { pair ->
                    val p = pair.split(";")
                    require(p.size == 2) { "Epoche '$pair'" }
                    Epoch(p[0].ifEmpty { null }?.toDouble(), p[1].ifEmpty { null }?.toDouble())
                },
            )
        }
    }

    private inline fun <T> parse(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            // NumberFormatException ist eine IllegalArgumentException.
            throw CodecException("$what: ${e.message}", e)
        }

    private fun escape(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun unescape(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)
}
