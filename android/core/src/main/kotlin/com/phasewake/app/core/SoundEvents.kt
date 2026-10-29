package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Art eines Geräuschs in der Nacht. */
enum class SoundType {
    SNORE,
    TALK,
    OTHER,
}

/** Ein aufgenommener Ausschnitt eines Geräuschs. */
data class SoundClip(
    /** Nachtdatum (wie bei den Notizen: Abend, an dem die Nacht beginnt). */
    val night: LocalDate,
    /** Beginn des Geräuschs. */
    val at: Instant,
    /** Art des Geräuschs (aus [SoundEvents.classify]). */
    val type: SoundType,
    /** Dauer des Geräuschs (kann länger sein als der Ausschnitt, z. B. minutenlanges Schnarchen). */
    val durationMs: Long,
    /** Lautester Pegel in dBFS (gerundet); bei zu vielen Ausschnitten bleiben die lautesten. */
    val peakDb: Int,
    /** Dateiname des Ausschnitts im App-Ordner (ohne Pfad). */
    val file: String,
)

/** Bilanz einer Nacht: «Schnarchen: 12 Min (8 Ausschnitte) · Sprechen: 2 Ausschnitte». */
data class SoundSummary(val snoreMs: Long, val snoreClips: Int, val talkClips: Int, val otherClips: Int) {
    /** Schnarch-Minuten, gerundet; kurzes Schnarchen zählt als 1 Min statt 0. */
    val snoreMinutes: Int get() = if (snoreMs <= 0) 0 else maxOf(1, (snoreMs / 60_000.0).roundToInt())

    /** Alle Ausschnitte der Nacht zusammen. */
    val clips: Int get() = snoreClips + talkClips + otherClips
}

/**
 * Geräusche in der Nacht (Schnarchen, Sprechen): Regeln, Speicherformat und Aufräumen. Die Aufnahme
 * läuft nur auf Wunsch auf dem Handy; Ausschnitte bleiben im App-Ordner und werden nach
 * [RETENTION_DAYS] Tagen gelöscht. Nie in Sicherungen, nie im Export, nie übertragen.
 *
 * Speicherformat: je Ausschnitt eine Zeile «yyyy-MM-dd|Beginn ms|Art|Dauer ms|Spitze dB|Datei».
 */
object SoundEvents {
    /** Aufbewahrung der Ausschnitte in Tagen; danach löscht [expire] sie (Datenschutz). */
    const val RETENTION_DAYS: Long = 7

    /** Höchstzahl Ausschnitte je Nacht; darüber fallen die leisesten weg ([add]). */
    const val MAX_CLIPS_PER_NIGHT: Int = 40

    /** Die Aufnahme endet so lange nach der Zielzeit des nächsten Weckers. */
    val AFTER_TARGET: Duration = Duration.ofMinutes(30)

    /** Ohne Wecker innerhalb von [AUTO_START_LOOKAHEAD] endet die Aufnahme nach so langer Zeit. */
    val MAX_RUN: Duration = Duration.ofHours(12)

    /** Ohne Wecker in den nächsten [AUTO_START_LOOKAHEAD] startet die Vorgabe «für neue Nächte» ab dieser Stunde (Ortszeit). */
    const val AUTO_START_HOUR: Int = 21

    /** So weit voraus zählt ein Wecker für den automatischen Start. */
    val AUTO_START_LOOKAHEAD: Duration = Duration.ofHours(24)

    /** Abtastrate der Aufnahme (auch die von YAMNet). */
    const val SAMPLE_RATE: Int = 16_000

    private val SAFE_FILE = Regex("[A-Za-z0-9._-]{1,80}")

    /**
     * Ende der Aufnahme: Zielzeit des nächsten Weckers + [AFTER_TARGET], wenn er innerhalb von
     * [AUTO_START_LOOKAHEAD] liegt (auch mehr als [MAX_RUN] entfernt: früh eingeschaltet, später Wecker).
     * Ohne Wecker in dieser Zeit: [start] + [MAX_RUN].
     */
    fun recordingEnd(start: Instant, nextTarget: Instant?): Instant =
        if (nextTarget != null && nextTarget.isAfter(start) && !nextTarget.isAfter(start.plus(AUTO_START_LOOKAHEAD))) {
            nextTarget.plus(AFTER_TARGET)
        } else {
            start.plus(MAX_RUN)
        }

    /**
     * Ob die Vorgabe «für neue Nächte ein» die Aufnahme beim Öffnen der App jetzt von selbst starten
     * darf: ab der Bettzeit der kommenden Nacht ([Bedtime.bedtime] = Zielzeit des nächsten Weckers −
     * Schlafziel) bis zur Zielzeit. Liegt kein Wecker innerhalb von [AUTO_START_LOOKAHEAD] (oder
     * keiner), ab [AUTO_START_HOUR] Uhr bis Mitternacht. Davor startet sie nur, wenn der Nutzer sie
     * selbst einschaltet.
     *
     * @param nextTarget Zielzeit des nächsten Weckers (Einschlaf-Modus: die eingestellte späteste Zeit).
     */
    fun mayAutoStart(now: Instant, nextTarget: Instant?, goalMinutes: Int, zone: ZoneId, windowStart: Instant? = null): Boolean {
        if (nextTarget != null && nextTarget.isAfter(now) && !nextTarget.isAfter(now.plus(AUTO_START_LOOKAHEAD))) {
            // Im Weckfenster nicht mehr: Wer dann öffnet, ist meist schon wach (entschieden mit r1AD).
            if (windowStart != null && !now.isBefore(windowStart)) return false
            return !now.isBefore(Bedtime.bedtime(nextTarget, goalMinutes))
        }
        return now.atZone(zone).hour >= AUTO_START_HOUR
    }

    /**
     * Art zu einer Bezeichnung eines Klassifikators, unabhängig von Gross-/Kleinschreibung: YAMNet
     * (Android: «Snoring», «Speech», «Whispering», «Conversation») und SoundAnalysis (iPhone:
     * «snoring», «speech», «whispering», «shout»). Alles andere: [SoundType.OTHER].
     */
    fun typeOf(label: String): SoundType = when (label.trim().lowercase()) {
        "snoring" -> SoundType.SNORE
        "speech", "whispering", "conversation", "shout" -> SoundType.TALK
        else -> SoundType.OTHER
    }

    /** Mindestwert (gemittelt über den Ausschnitt), ab dem Schnarchen oder Sprechen gilt. */
    const val MIN_SCORE: Double = 0.15

    /**
     * Werte je Bezeichnung über alle Abschnitte ([frames]) eines Ausschnitts gemittelt; fehlt eine
     * Bezeichnung in einem Abschnitt, zählt sie dort als 0. Auf beiden Plattformen gleich.
     */
    fun averageScores(frames: List<Map<String, Double>>): Map<String, Double> {
        if (frames.isEmpty()) return emptyMap()
        val sums = HashMap<String, Double>()
        for (frame in frames) for ((label, score) in frame) sums[label] = (sums[label] ?: 0.0) + score
        return sums.mapValues { it.value / frames.size }
    }

    /**
     * Art aus den gemittelten Werten je Bezeichnung ([averageScores]). Verglichen werden nur die
     * Ziel-Bezeichnungen: der höchste Wert für Schnarchen gegen den höchsten für Sprechen. Andere
     * Bezeichnungen («Breathing», «Snort», «Silence» …) zählen nicht mit; sie schlügen Schnarchen sonst oft.
     * Liegen beide unter [MIN_SCORE]: [SoundType.OTHER]. Gleichstand: Schnarchen.
     */
    fun classify(scores: Map<String, Double>): SoundType {
        val snore = scores.filterKeys { typeOf(it) == SoundType.SNORE }.values.maxOrNull() ?: 0.0
        val talk = scores.filterKeys { typeOf(it) == SoundType.TALK }.values.maxOrNull() ?: 0.0
        return when {
            snore < MIN_SCORE && talk < MIN_SCORE -> SoundType.OTHER
            snore >= talk -> SoundType.SNORE
            else -> SoundType.TALK
        }
    }

    /** Pegel eines Blocks in dBFS (Effektivwert, 16-Bit-Werte); Stille ergibt [SILENCE_DB]. */
    fun levelDb(samples: ShortArray, from: Int = 0, to: Int = samples.size): Double {
        if (to <= from) return SILENCE_DB
        var sum = 0.0
        for (i in from until to) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / (to - from))
        return if (rms <= 1e-7) SILENCE_DB else (20 * log10(rms)).coerceAtLeast(SILENCE_DB)
    }

    /** Pegel für digitale Stille in dBFS (statt −∞, damit gerechnet werden kann). */
    const val SILENCE_DB: Double = -140.0

    /** Bilanz der Ausschnitte (einer Nacht). */
    fun summary(clips: List<SoundClip>): SoundSummary = SoundSummary(
        snoreMs = clips.filter { it.type == SoundType.SNORE }.sumOf { it.durationMs },
        snoreClips = clips.count { it.type == SoundType.SNORE },
        talkClips = clips.count { it.type == SoundType.TALK },
        otherClips = clips.count { it.type == SoundType.OTHER },
    )

    /** Ergebnis von [add]: was bleibt und welche Ausschnitte (Dateien) wegfallen. */
    data class Added(val kept: List<SoundClip>, val dropped: List<SoundClip>)

    /**
     * Fügt einen Ausschnitt hinzu. Hat seine Nacht danach mehr als [max] Ausschnitte, fallen die
     * leisesten weg (bei gleichem Pegel die jüngeren) – auch der neue selbst, wenn er der leiseste ist.
     */
    fun add(clips: List<SoundClip>, clip: SoundClip, max: Int = MAX_CLIPS_PER_NIGHT): Added {
        val all = clips + clip
        val night = all.filter { it.night == clip.night }
        if (night.size <= max) return Added(sorted(all), emptyList())
        val dropped = night.sortedWith(compareBy<SoundClip> { it.peakDb }.thenByDescending { it.at }).take(night.size - max).toSet()
        return Added(sorted(all.filterNot { it in dropped }), dropped.toList())
    }

    /** Teilt in (behalten, abgelaufen): abgelaufen ist, was vor [now] − [RETENTION_DAYS] Tagen begann. */
    fun expire(clips: List<SoundClip>, now: Instant, days: Long = RETENTION_DAYS): Pair<List<SoundClip>, List<SoundClip>> {
        val limit = now.minus(Duration.ofDays(days))
        return clips.partition { !it.at.isBefore(limit) }
    }

    /** Die Ausschnitte einer Nacht, älteste zuerst. */
    fun forNight(clips: List<SoundClip>, night: LocalDate): List<SoundClip> = clips.filter { it.night == night }.sortedBy { it.at }

    /**
     * Nachtdaten mit Ausschnitten, zu denen es keine aufgezeichnete Nacht gibt (z. B. Uhr nicht getragen):
     * Morgenbericht und Verlauf zeigen ihre Geräusche in einer eigenen Karte. Neueste zuerst, je Datum
     * mit dem Beginn des jüngsten Ausschnitts (zum Einordnen zwischen die Nächte).
     */
    fun nightsWithoutRecord(clips: List<SoundClip>, recorded: Collection<LocalDate>): List<Pair<LocalDate, Instant>> {
        val known = recorded.toSet()
        return clips.filter { it.night !in known }.groupBy { it.night }
            .map { (night, list) -> night to list.maxOf { it.at } }
            .sortedWith(compareByDescending<Pair<LocalDate, Instant>> { it.second }.thenByDescending { it.first })
    }

    /** Nachtdatum eines Geräuschs (wie bei den Notizen). */
    fun nightDate(at: Instant, zone: ZoneId): LocalDate = SleepNotes.nightDate(at, zone)

    /** Je Ausschnitt eine Zeile im Speicherformat, nach Beginn und Dateiname sortiert. */
    fun encode(clips: List<SoundClip>): String = sorted(clips).joinToString("\n") {
        listOf(it.night.toString(), it.at.toEpochMilli().toString(), it.type.name, it.durationMs.toString(), it.peakDb.toString(), it.file)
            .joinToString("|")
    }

    /** Liest [encode]; unlesbare Zeilen (und Dateinamen mit Pfad) werden übersprungen, unbekannte Arten zu [SoundType.OTHER]. */
    fun decode(text: String?): List<SoundClip> = sorted(
        text.orEmpty().lines().mapNotNull { line ->
            val f = line.split('|')
            if (f.size < 6 || !SAFE_FILE.matches(f[5])) return@mapNotNull null
            runCatching {
                SoundClip(
                    night = LocalDate.parse(f[0]),
                    at = Instant.ofEpochMilli(f[1].toLong()),
                    type = SoundType.entries.firstOrNull { it.name == f[2] } ?: SoundType.OTHER,
                    durationMs = f[3].toLong().coerceAtLeast(0),
                    peakDb = f[4].toInt(),
                    file = f[5],
                )
            }.getOrNull()
        }.distinctBy { it.file },
    )

    /** Ob ein Dateiname gültig ist (keine Pfade): Schutz beim Löschen und Abspielen. */
    fun isSafeFile(name: String): Boolean = SAFE_FILE.matches(name) && name != "." && name != ".."

    private fun sorted(clips: List<SoundClip>) = clips.sortedWith(compareBy<SoundClip> { it.at }.thenBy { it.file })
}
