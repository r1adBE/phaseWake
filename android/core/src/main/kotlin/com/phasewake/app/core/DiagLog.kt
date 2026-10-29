package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Gerät, das ein Ereignis protokolliert hat. */
enum class DiagDevice(
    /** Kürzel im Format von [DiagLog.encode]. */
    val code: String,
) {
    /** Handy (Android oder iPhone). */
    PHONE("P"),

    /** Uhr (Galaxy Watch oder Apple Watch). */
    WATCH("W"),
}

/** Art eines Ereignisses im Diagnose-Protokoll. */
enum class DiagKind {
    /** Wecker empfangen (von der Gegenseite). */
    ALARMS_RECEIVED,

    /** Wecker oder Sicherung geplant. */
    SCHEDULED,

    /** Abgleich gesendet oder empfangen. */
    SYNC,

    /** Weckfenster geöffnet. */
    WINDOW,

    /** Messung gestartet. */
    MEASURE_START,

    /** Messung beendet. */
    MEASURE_END,

    /** Phase erkannt (leichter Schlaf, wach). */
    PHASE,

    /** Klingeln (wer und warum im Text). */
    RING,

    /** Weckbildschirm: erwarteter Weg ([RingRoute]), Startversuch und ob er zu sehen ist (Weg im Text). */
    RING_SCREEN,

    /** Schlummern. */
    SNOOZE,

    /** Stopp. */
    STOP,

    /** Zweitwecker ausgesetzt (die Uhr hat die Nacht übernommen). */
    SUPPRESSED,

    /** Neustart des Geräts oder der App. */
    BOOT,

    /** Vor dem ersten Entsperren (Direct Boot). */
    DIRECT_BOOT,

    /** Akkustand. */
    BATTERY,

    /** Probewecker. */
    TEST,

    /** Powernap. */
    NAP,

    /** Fehler oder Ausnahme. */
    ERROR,

    /** Unbekannte Art (aus einer neueren Version). */
    OTHER,
}

/** Ein Eintrag im Diagnose-Protokoll; [detail] ohne persönliche Daten (keine Namen, keine Messwerte). */
data class DiagEntry(val at: Instant, val device: DiagDevice, val kind: DiagKind, val detail: String)

/**
 * Diagnose-Protokoll: nur lokal, ein Ringpuffer der letzten [MAX_ENTRIES] Ereignisse aus höchstens [MAX_AGE].
 * Eine Zeile je Eintrag: «ms|P|ART|Text». Auf allen Geräten gleich, damit Handy und Uhr dasselbe lesen.
 */
object DiagLog {
    /** Höchstens so viele Einträge (die neuesten bleiben). */
    const val MAX_ENTRIES: Int = 500

    /** Älter als das fällt weg. */
    val MAX_AGE: Duration = Duration.ofDays(7)

    /** Längster Text eines Eintrags in Zeichen. */
    const val MAX_DETAIL: Int = 200

    private const val SEP = "|"
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Eine Zeile; «|» und Zeilenumbrüche im Text werden zu Leerzeichen, zu lange Texte gekürzt. */
    fun encode(entry: DiagEntry): String =
        listOf(entry.at.toEpochMilli().toString(), entry.device.code, entry.kind.name, clean(entry.detail)).joinToString(SEP)

    /** Liest [encode]; unbekannte Art wird [DiagKind.OTHER], eine kaputte Zeile `null`. */
    fun decode(line: String): DiagEntry? {
        val f = line.split(SEP, limit = 4)
        if (f.size < 3) return null
        val at = f[0].toLongOrNull() ?: return null
        val device = DiagDevice.entries.firstOrNull { it.code == f[1] } ?: return null
        val kind = DiagKind.entries.firstOrNull { it.name == f[2] } ?: DiagKind.OTHER
        return DiagEntry(Instant.ofEpochMilli(at), device, kind, f.getOrElse(3) { "" })
    }

    /** Nach Zeit sortiert, ohne Einträge älter als [MAX_AGE] vor [now], höchstens die neuesten [MAX_ENTRIES]. */
    fun trim(entries: List<DiagEntry>, now: Instant): List<DiagEntry> {
        val from = now.minus(MAX_AGE)
        return entries.filter { !it.at.isBefore(from) }.sortedBy { it.at }.takeLast(MAX_ENTRIES)
    }

    /** Lesbarer Text zum Teilen: «2026-10-29 07:12:03 phone RING Text» je Zeile, älteste zuerst. */
    fun format(entries: List<DiagEntry>, zone: ZoneId): String =
        entries.sortedBy { it.at }.joinToString("\n") { e ->
            listOf(TIME.format(e.at.atZone(zone)), e.device.name.lowercase(), e.kind.name, e.detail).filter { it.isNotEmpty() }.joinToString(" ")
        }

    /** Text ohne Trennzeichen und Zeilenumbrüche, höchstens [MAX_DETAIL] Zeichen. */
    fun clean(detail: String): String =
        detail.replace(SEP, " ").replace('\n', ' ').replace('\r', ' ').trim().take(MAX_DETAIL)
}

/** Bericht der Uhr ans Handy: Zustand als Schlüssel und Wert, dazu ihr Protokoll. */
data class DiagReport(val status: List<Pair<String, String>>, val entries: List<DiagEntry>)

/** Format von [DiagReport]: «phasewake-diag 1», Zeilen «schlüssel=wert», «--», dann die Zeilen von [DiagLog]. */
object DiagReports {
    private const val HEADER = "phasewake-diag 1"
    private const val DIVIDER = "--"

    /** Schreibt den Bericht; «=» im Schlüssel und Zeilenumbrüche im Wert werden entfernt. */
    fun encode(report: DiagReport): String = buildString {
        append(HEADER).append('\n')
        for ((key, value) in report.status) {
            append(key.replace("=", "").replace('\n', ' ')).append('=').append(value.replace('\n', ' ')).append('\n')
        }
        append(DIVIDER).append('\n')
        report.entries.forEach { append(DiagLog.encode(it)).append('\n') }
    }

    /** Liest [encode]; fremdes Format oder `null` ergibt `null`, kaputte Zeilen fallen weg. */
    fun decode(text: String?): DiagReport? {
        val lines = text?.lines() ?: return null
        if (lines.firstOrNull() != HEADER) return null
        val divider = lines.indexOf(DIVIDER).takeIf { it > 0 } ?: lines.size
        val status = lines.subList(1, divider).filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }
        val entries = lines.drop(divider + 1).filter { it.isNotBlank() }.mapNotNull(DiagLog::decode)
        return DiagReport(status, entries)
    }
}

/** Freischalten der Diagnose: [TAPS]-mal auf die Versionszeile tippen, ab [HINT_FROM] mit Hinweis. Wie Android-Entwickleroptionen. */
object DiagUnlock {
    /** So viele Tipps schalten die Diagnose frei. */
    const val TAPS: Int = 7

    /** Ab so vielen Tipps erscheint «Noch N Mal». */
    const val HINT_FROM: Int = 4

    /** Noch fehlende Tipps für den Hinweis; `null` vor [HINT_FROM] und ab [TAPS]. */
    fun remaining(taps: Int): Int? = if (taps in HINT_FROM until TAPS) TAPS - taps else null

    /** Ob [taps] Tipps die Diagnose freischalten. */
    fun unlocked(taps: Int): Boolean = taps >= TAPS
}
