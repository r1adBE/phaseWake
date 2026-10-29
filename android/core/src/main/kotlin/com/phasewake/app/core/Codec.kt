package com.phasewake.app.core

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

    /**
     * id|Stunde|Minute|Tage|Fenster|ein|Handy|Uhr-Ton|Schlafdauer|Ton|Name|Sonnenaufgang|Weckaufgabe|Sonnenaufgang-Gerät|
     * Überspringen-bis|Feiertage
     */
    private const val FIELDS = 16

    /**
     * So viele Felder je Weckerzeile kennt diese Version ([FIELDS]); die Uhr meldet die Zahl im Status, damit das
     * Handy eine ältere Uhr erkennt ([watchKnowsSkip]).
     */
    const val ALARM_FIELDS: Int = FIELDS

    /** Ab so vielen Feldern kennt eine Version Überspringen (Feld 15) und Feiertage (Feld 16). */
    const val SKIP_FIELDS: Int = 16

    /**
     * Ob eine Uhr, die [watchFields] Felder je Weckerzeile kennt, Überspringen und Feiertage beachtet. `null` = keine
     * Uhr bekannt (nie ein Status): `true`, das Handy weckt dann allein und beachtet beides. Ein Status ohne Angabe
     * stammt von einer älteren Uhr und zählt als 0.
     */
    fun watchKnowsSkip(watchFields: Int?): Boolean = watchFields == null || watchFields >= SKIP_FIELDS

    /** Ältere Stände ohne Sonnenaufgang (Feld 12 fehlt → aus). */
    private const val FIELDS_V1 = 11

    private val dayCodes = mapOf(
        DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
        DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
    )
    private val daysByCode = dayCodes.entries.associate { (day, code) -> code to day }

    /**
     * Schreibt Wecker im Format «phasewake-alarms 1»: Kopfzeile, dann je Wecker eine Zeile mit
     * [FIELDS] durch «|» getrennten Feldern. Texte (Kennung, Ton, Name) sind URL-kodiert, damit «|» und
     * Zeilenumbrüche darin nichts zerbrechen. Leere Liste = nur die Kopfzeile.
     */
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
                a.phone.name,
                if (a.watchSound) "1" else "0",
                a.sleepDurationMinutes?.toString().orEmpty(),
                escape(a.sound),
                escape(a.label),
                a.sunrise.name,
                a.wakeTask.name,
                a.sunriseDevice.name,
                a.skipUntil.toString(),
                if (a.skipHolidays) "1" else "0",
            ).joinToString(SEP)).append('\n')
        }
    }

    /**
     * Liest Wecker zeilenweise und tolerant: Eine unlesbare Zeile fällt weg, statt alle Wecker
     * mitzunehmen; unbekannte Werte (z. B. eine neue Weckart aus einer neueren Version) werden zum
     * Standard. Nur ein fremdes Format wirft [CodecException].
     */
    fun decodeAlarms(text: String): List<AlarmSpec> {
        val lines = text.lines().filter { it.isNotEmpty() }
        if (lines.firstOrNull() != ALARMS_HEADER) throw CodecException("Unbekanntes Weckerformat")
        return lines.drop(1).mapIndexedNotNull { index, line ->
            try {
                decodeAlarm(index, line)
            } catch (_: CodecException) {
                null
            }
        }
    }

    private fun decodeAlarm(index: Int, line: String): AlarmSpec =
            parse("Wecker ${index + 1}") {
                val f = line.split(SEP)
                // Neuere Versionen dürfen Felder anhängen; gelesen wird, was diese Version kennt.
                require(f.size >= FIELDS_V1) { "$FIELDS Felder erwartet, ${f.size} gefunden" }
                AlarmSpec(
                    id = unescape(f[0]),
                    hour = f[1].toInt(),
                    minute = f[2].toInt(),
                    days = if (f[3].isEmpty()) emptySet() else f[3].split(",").map {
                        daysByCode[it] ?: throw IllegalArgumentException("Wochentag '$it'")
                    }.toSet(),
                    windowMinutes = f[4].toInt(),
                    enabled = f[5] == "1",
                    phone = PhoneAlert.entries.firstOrNull { it.name == f[6] } ?: PhoneAlert.SOUND,
                    watchSound = f[7] == "1",
                    sleepDurationMinutes = f[8].ifEmpty { null }?.toInt(),
                    sound = unescape(f[9]),
                    label = unescape(f[10]),
                    sunrise = if (f.size > FIELDS_V1) Sunrise.entries.firstOrNull { it.name == f[11] } ?: Sunrise.OFF else Sunrise.OFF,
                    // Feld 13 fehlt bei älteren Ständen, unbekannt aus neueren: Halten.
                    wakeTask = f.getOrNull(12)?.let { v -> WakeTask.entries.firstOrNull { it.name == v } } ?: WakeTask.NONE,
                    // Feld 14 fehlt bei älteren Ständen, unbekannt aus neueren: Handy.
                    sunriseDevice = f.getOrNull(13)?.let { v -> SunriseDevice.entries.firstOrNull { it.name == v } } ?: SunriseDevice.PHONE,
                    // Felder 15 und 16 fehlen bei älteren Ständen (bis 14 Felder): nichts überspringen.
                    skipUntil = f.getOrNull(14)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                    skipHolidays = f.getOrNull(15) == "1",
                )
            }

    /** Weckgründe, die jede Version lesen kann; neuere stehen zusätzlich in Feld 8 ([encodeNight]). */
    private val legacyReasons = setOf(WakeReason.AWAKE, WakeReason.LIGHT_SLEEP, WakeReason.DEADLINE)

    /**
     * Schreibt eine Nacht im Format «phasewake-night 1»: Kopfzeile, Kopfdaten (Zeiten in ms seit 1970)
     * und eine Zeile mit allen Epochen «Bewegung;Puls», durch Komma getrennt; fehlende Werte bleiben leer.
     * Ein Grund, den ältere Versionen nicht kennen ([WakeReason.EXACT], [WakeReason.SHORT_NIGHT]), steht in Feld 5
     * als [WakeReason.DEADLINE] (so lesen ältere Versionen die Nacht weiter) und genau in einem angehängten Feld 8.
     */
    fun encodeNight(night: NightRecord): String = buildString {
        append(NIGHT_HEADER).append('\n')
        val legacy = night.reason in legacyReasons
        val fields = listOf(
            escape(night.alarmId),
            night.windowStart.toEpochMilli().toString(),
            night.target.toEpochMilli().toString(),
            night.wokeAt.toEpochMilli().toString(),
            if (legacy) night.reason.name else WakeReason.DEADLINE.name,
            night.snoozes.toString(),
            night.feeling?.toString().orEmpty(),
        )
        append((if (legacy) fields else fields + night.reason.name).joinToString(SEP)).append('\n')
        append(night.epochs.joinToString(",") { e ->
            // toString() liefert die kürzeste exakt rücklesbare Darstellung.
            (e.motion?.toString() ?: "") + ";" + (e.heartRate?.toString() ?: "")
        }).append('\n')
    }

    /**
     * Liest [encodeNight]. Ältere Stände ohne Befinden (6 Felder) bleiben lesbar, weitere angehängte Felder neuerer
     * Versionen werden ignoriert. Grund: Feld 8, wenn bekannt, sonst Feld 5; ein unbekannter Grund (aus einer
     * neueren Version) gilt als [WakeReason.DEADLINE]. Fremdes Format oder kaputte Werte: [CodecException]
     * (eine Nacht ist ganz oder gar nicht lesbar).
     */
    fun decodeNight(text: String): NightRecord {
        val lines = text.lines()
        if (lines.firstOrNull() != NIGHT_HEADER) throw CodecException("Unbekanntes Nachtformat")
        return parse("Nacht") {
            val f = lines.getOrElse(1) { "" }.split(SEP)
            // 7 Felder; ältere Stände ohne Befinden haben 6, neuere dürfen weitere anhängen (werden ignoriert).
            require(f.size >= 6) { "mindestens 6 Felder erwartet, ${f.size} gefunden" }
            val epochLine = lines.getOrElse(2) { "" }
            NightRecord(
                alarmId = unescape(f[0]),
                windowStart = Instant.ofEpochMilli(f[1].toLong()),
                target = Instant.ofEpochMilli(f[2].toLong()),
                wokeAt = Instant.ofEpochMilli(f[3].toLong()),
                reason = reasonOf(f.getOrNull(7)) ?: reasonOf(f[4]) ?: WakeReason.DEADLINE,
                snoozes = f[5].toInt(),
                feeling = f.getOrNull(6)?.ifEmpty { null }?.toInt(),
                epochs = if (epochLine.isEmpty()) emptyList() else epochLine.split(",").map { pair ->
                    val p = pair.split(";")
                    require(p.size == 2) { "Epoche '$pair'" }
                    Epoch(p[0].ifEmpty { null }?.toDouble(), p[1].ifEmpty { null }?.toDouble())
                },
            )
        }
    }

    private fun reasonOf(name: String?): WakeReason? = WakeReason.entries.firstOrNull { it.name == name }

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
