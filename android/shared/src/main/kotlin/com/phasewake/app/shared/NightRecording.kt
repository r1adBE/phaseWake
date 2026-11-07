package com.phasewake.app.shared

import com.phasewake.app.core.DetectorConfig
import com.phasewake.app.core.Epoch
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Grund, warum eine Aufzeichnung endete. [code] steht in Datei, Paket und Export: nie ändern, nur ergänzen.
 * Unbekannte Kennungen (neuere Version) gelten beim Lesen als [SERVICE].
 */
enum class RecordEnd(val code: String) {
    /** Auf der Uhr gestoppt (Knopf oder Mitteilung). */
    WATCH("watch"),

    /** Am Handy gestoppt. */
    PHONE("phone"),

    /** Nach [NightRecording.MAX_DURATION] von selbst beendet. */
    CAP("cap"),

    /** Akku der Uhr unter [NightRecording.MIN_BATTERY]: Der Wecker hat Vorrang. */
    BATTERY("battery"),

    /** Messdienst beendet (vom System, oder nach einem Prozessende nicht wieder aufgenommen). */
    SERVICE("service"),
    ;

    companion object {
        fun of(code: String?): RecordEnd = entries.firstOrNull { it.code == code } ?: SERVICE
    }
}

/**
 * Eine aufgezeichnete Nacht («Nacht aufzeichnen» auf der Galaxy Watch): ab [start] je 30-s-Epoche Bewegung und Puls.
 * Epoche `i` deckt `start + i · 30 s` ab; Lücken (z. B. nach einem Prozessende) sind leere Epochen. [end] und
 * [endReason] fehlen, solange sie läuft. Datensammlung für spätere eigene Schwellen; der Detektor nutzt sie (noch) nicht.
 */
data class NightRecording(
    val start: Instant,
    val epochs: List<Epoch>,
    val end: Instant? = null,
    val endReason: RecordEnd? = null,
) {
    /** Abgeschlossen (Ende gespeichert)? */
    val finished: Boolean get() = end != null

    /** Dauer bis zum Ende; laufend: bis zur letzten Epoche. */
    val duration: Duration get() = Duration.between(start, end ?: start.plusSeconds(epochs.size * EPOCH_SECONDS))

    companion object {
        /** Längste Aufzeichnung; danach endet sie von selbst ([RecordEnd.CAP]). */
        val MAX_DURATION: Duration = Duration.ofHours(12)

        /** Epochen in [MAX_DURATION]; mehr nimmt eine Aufzeichnung nicht auf. */
        val MAX_EPOCHS: Int = (MAX_DURATION.seconds / DetectorConfig.EPOCH_SECONDS).toInt()

        /** Unter diesem Akkustand (Prozent) startet keine Aufzeichnung, und eine laufende endet ([RecordEnd.BATTERY]). */
        const val MIN_BATTERY = 15

        /** So viele abgeschlossene Aufzeichnungen bleiben je Gerät; ältere werden gelöscht. */
        const val MAX_KEPT = 14

        private const val EPOCH_SECONDS = DetectorConfig.EPOCH_SECONDS.toLong()

        /** Erste Zeile von [encode]. */
        const val HEADER = "phasewake-recording 1"
        private const val START = "start="
        private const val END = "end="

        /**
         * Grund für ein Ende jetzt ([now], Akku [battery] in Prozent, `null` = unbekannt), sonst `null`: [RecordEnd.CAP] ab
         * [MAX_DURATION] nach [start], [RecordEnd.BATTERY] unter [MIN_BATTERY].
         */
        fun endNow(start: Instant, now: Instant, battery: Int?): RecordEnd? = when {
            !now.isBefore(start.plus(MAX_DURATION)) -> RecordEnd.CAP
            battery != null && battery < MIN_BATTERY -> RecordEnd.BATTERY
            else -> null
        }

        /**
         * Epochen, die bis [now] seit [start] vergangen sind (höchstens [MAX_EPOCHS]): So viele müsste die Aufzeichnung
         * haben; beim Wiederaufnehmen nach einem Prozessende wird bis dahin mit leeren Epochen aufgefüllt.
         */
        fun epochsUntil(start: Instant, now: Instant): Int =
            (Duration.between(start, now).seconds.coerceAtLeast(0L) / EPOCH_SECONDS).toInt().coerceAtMost(MAX_EPOCHS)

        /** Kopfzeilen einer neuen Aufzeichnung (Beginn der Datei); Epochen und Ende werden angehängt. */
        fun header(start: Instant): String = "$HEADER\n$START${start.toEpochMilli()}\n"

        /** Eine Epoche je Zeile, «bewegung;puls» (leer = keine Werte), wie die Epochen in `Codec.encodeNight`. */
        fun epochLines(epochs: List<Epoch>): String = buildString {
            for (e in epochs) append(e.motion?.toString().orEmpty()).append(';').append(e.heartRate?.toString().orEmpty()).append('\n')
        }

        /** Schlusszeile «end=ms,grund». */
        fun endLine(end: Instant, reason: RecordEnd): String = "$END${end.toEpochMilli()},${reason.code}\n"

        /** Ganze Aufzeichnung als Text (Datei am Handy, Paket an das Handy). */
        fun encode(recording: NightRecording): String = buildString {
            append(header(recording.start))
            append(epochLines(recording.epochs))
            val end = recording.end
            if (end != null) append(endLine(end, recording.endReason ?: RecordEnd.SERVICE))
        }

        /**
         * Liest [encode] bzw. eine angehängte Datei. Nachsichtig: Eine letzte Zeile ohne Zeilenende (Absturz beim
         * Schreiben) und unlesbare Epochen-Zeilen fallen weg. `null` bei fremdem Format oder ohne Beginn.
         */
        fun decode(text: String): NightRecording? {
            val complete = if (text.endsWith('\n')) text else text.substring(0, text.lastIndexOf('\n') + 1)
            val lines = complete.lines().dropLastWhile { it.isEmpty() }
            if (lines.firstOrNull() != HEADER) return null
            val start = lines.getOrNull(1)?.takeIf { it.startsWith(START) }?.removePrefix(START)?.toLongOrNull() ?: return null
            val epochs = ArrayList<Epoch>()
            var end: Instant? = null
            var reason: RecordEnd? = null
            for (line in lines.drop(2)) {
                if (line.startsWith(END)) {
                    val f = line.removePrefix(END).split(',')
                    end = f[0].toLongOrNull()?.let(Instant::ofEpochMilli) ?: continue
                    reason = RecordEnd.of(f.getOrNull(1))
                    break
                }
                epochOf(line)?.let(epochs::add)
            }
            return NightRecording(Instant.ofEpochMilli(start), epochs, end, reason)
        }

        private fun epochOf(line: String): Epoch? {
            val f = line.split(';')
            if (f.size != 2) return null
            val motion = if (f[0].isEmpty()) null else f[0].toDoubleOrNull() ?: return null
            val hr = if (f[1].isEmpty()) null else f[1].toDoubleOrNull() ?: return null
            return Epoch(motion, hr)
        }

        /**
         * CSV-Export der abgeschlossenen Aufzeichnungen wie `NightHistory.exportCsv`: ohne Datums- oder Uhrzeitangaben,
         * durchnummeriert (1 = älteste), Dezimalpunkt, Komma als Trenner. Je Epoche eine Zeile: Aufzeichnung, Grund des
         * Endes ([RecordEnd.code]), Epoche, Bewegung, Puls.
         */
        fun exportCsv(recordings: List<NightRecording>): String = buildString {
            append("recording,end_reason,epoch,motion,hr\n")
            recordings.filter { it.finished }.sortedBy { it.start }.forEachIndexed { index, recording ->
                val reason = (recording.endReason ?: RecordEnd.SERVICE).code
                recording.epochs.forEachIndexed { epochIndex, epoch ->
                    append(index + 1).append(',').append(reason).append(',').append(epochIndex).append(',')
                    append(epoch.motion?.let { String.format(Locale.ROOT, "%.6f", it) } ?: "").append(',')
                    append(epoch.heartRate?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "").append('\n')
                }
            }
        }
    }
}
