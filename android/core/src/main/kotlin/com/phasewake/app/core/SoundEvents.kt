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

/**
 * Ein aufgenommener Ausschnitt eines Geräuschs.
 *
 * @property night Nachtdatum (wie bei den Notizen: Abend, an dem die Nacht beginnt).
 * @property at Beginn des Geräuschs.
 * @property durationMs Dauer des Geräuschs (kann länger sein als der Ausschnitt, z. B. minutenlanges Schnarchen).
 * @property peakDb Lautester Pegel in dBFS (gerundet); bei zu vielen Ausschnitten bleiben die lautesten.
 * @property file Dateiname des Ausschnitts im App-Ordner (ohne Pfad).
 */
data class SoundClip(
    val night: LocalDate,
    val at: Instant,
    val type: SoundType,
    val durationMs: Long,
    val peakDb: Int,
    val file: String,
)

/** Bilanz einer Nacht: «Schnarchen: 12 Min (8 Ausschnitte) · Sprechen: 2 Ausschnitte». */
data class SoundSummary(val snoreMs: Long, val snoreClips: Int, val talkClips: Int, val otherClips: Int) {
    /** Schnarch-Minuten, gerundet; kurzes Schnarchen zählt als 1 Min statt 0. */
    val snoreMinutes: Int get() = if (snoreMs <= 0) 0 else maxOf(1, (snoreMs / 60_000.0).roundToInt())

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
    const val RETENTION_DAYS: Long = 7
    const val MAX_CLIPS_PER_NIGHT: Int = 40

    /** Die Aufnahme endet so lange nach der Zielzeit des nächsten Weckers. */
    val AFTER_TARGET: Duration = Duration.ofMinutes(30)

    /** Ohne Wecker in dieser Zeit endet die Aufnahme spätestens nach so langer Zeit. */
    val MAX_RUN: Duration = Duration.ofHours(12)

    /** Abtastrate der Aufnahme (auch die von YAMNet). */
    const val SAMPLE_RATE: Int = 16_000

    private val SAFE_FILE = Regex("[A-Za-z0-9._-]{1,80}")

    /**
     * Ende der Aufnahme: Zielzeit des nächsten Weckers + [AFTER_TARGET]; liegt kein Wecker innerhalb
     * von [MAX_RUN] (oder keiner), spätestens [start] + [MAX_RUN].
     */
    fun recordingEnd(start: Instant, nextTarget: Instant?): Instant {
        val latest = start.plus(MAX_RUN)
        return if (nextTarget != null && nextTarget.isAfter(start) && !nextTarget.isAfter(latest)) nextTarget.plus(AFTER_TARGET) else latest
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

    /** Art aus den Werten je Bezeichnung (z. B. gemittelt über den Ausschnitt): die Bezeichnung mit dem höchsten Wert. */
    fun classify(scores: Map<String, Double>): SoundType =
        scores.entries.filter { it.value > 0.0 }.maxWithOrNull(compareBy<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            ?.let { typeOf(it.key) } ?: SoundType.OTHER

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

    /** Nachtdatum eines Geräuschs (wie bei den Notizen). */
    fun nightDate(at: Instant, zone: ZoneId): LocalDate = SleepNotes.nightDate(at, zone)

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

/**
 * Findet Geräusche in einem Strom von Pegeln. Rein rechnerisch, ohne Audio: Der Aufrufer misst den
 * Pegel je Block von [FRAME_MS] ms und führt die Aktionen aus (Ausschnitt beginnen, beenden, speichern).
 *
 * Regeln:
 * - Grundrauschen = Median der Sekundenpegel der letzten [FLOOR_SECONDS] s (Sekundenpegel = Mittel der
 *   Blockpegel einer Sekunde); erst ab [MIN_FLOOR_SECONDS] s Vorlauf wird erkannt.
 * - Ein Geräusch beginnt, wenn der Pegel mindestens [MIN_LOUD_MS] ms am Stück über Grundrauschen +
 *   [THRESHOLD_DB] liegt. Beginn = erster dieser Blöcke.
 * - Ausschnitt: [PRE_ROLL_MS] ms vor dem Beginn bis höchstens [MAX_CLIP_MS] ms insgesamt; früher zu
 *   Ende, wenn es [CLIP_TAIL_MS] ms lang leise ist.
 * - Das Geräusch dauert, bis es [EVENT_GAP_MS] ms lang leise ist (Dauer = Beginn bis Ende des letzten
 *   lauten Blocks), höchstens [MAX_EVENT_MS] ms. So gibt es höchstens einen Ausschnitt je 30 s, und
 *   langes Schnarchen zählt als ein Geräusch mit seiner ganzen Dauer.
 *
 * @param atMs Beginn des Blocks in ms (beliebiger, gleichmässig steigender Bezug, z. B. ab Aufnahmebeginn).
 */
class SoundEventDetector {

    sealed interface Action {
        /** Ausschnitt beginnt bei [clipStartMs] (Vorlauf ab dort übernehmen); das Geräusch bei [onsetMs]. */
        data class ClipStart(val clipStartMs: Long, val onsetMs: Long) : Action

        /** Ausschnitt endet bei [clipEndMs] (exklusiv): speichern und einordnen. */
        data class ClipEnd(val onsetMs: Long, val clipEndMs: Long) : Action

        /** Geräusch zu Ende. */
        data class EventEnd(val onsetMs: Long, val durationMs: Long, val peakDb: Double) : Action
    }

    private val seconds = ArrayDeque<Double>()
    private var secondSum = 0.0
    private var secondFrames = 0
    private var floor: Double? = null

    private var loudRun = 0
    private var runStartMs = 0L
    private var runPeak = Double.NEGATIVE_INFINITY

    // Laufendes Geräusch.
    private var onsetMs: Long? = null
    private var lastLoudEndMs = 0L
    private var peak = Double.NEGATIVE_INFINITY
    private var clipStartMs: Long? = null

    /** Aktuelle Schwelle in dBFS; `null` während des Vorlaufs. */
    val threshold: Double? get() = floor?.plus(THRESHOLD_DB)

    fun onFrame(atMs: Long, levelDb: Double): List<Action> {
        val actions = mutableListOf<Action>()
        val limit = threshold
        val loud = limit != null && levelDb > limit
        val frameEnd = atMs + FRAME_MS

        val onset = onsetMs
        if (onset == null) {
            if (loud) {
                if (loudRun == 0) {
                    runStartMs = atMs
                    runPeak = Double.NEGATIVE_INFINITY
                }
                loudRun++
                runPeak = maxOf(runPeak, levelDb)
                if (loudRun * FRAME_MS >= MIN_LOUD_MS) {
                    onsetMs = runStartMs
                    lastLoudEndMs = frameEnd
                    peak = runPeak
                    val start = runStartMs - PRE_ROLL_MS
                    clipStartMs = start
                    loudRun = 0
                    actions += Action.ClipStart(start, runStartMs)
                }
            } else {
                loudRun = 0
            }
        } else {
            if (loud) {
                lastLoudEndMs = frameEnd
                peak = maxOf(peak, levelDb)
            }
            val clip = clipStartMs
            if (clip != null && (frameEnd - clip >= MAX_CLIP_MS || frameEnd - lastLoudEndMs >= CLIP_TAIL_MS)) {
                actions += Action.ClipEnd(onset, minOf(frameEnd, clip + MAX_CLIP_MS))
                clipStartMs = null
            }
            if (frameEnd - lastLoudEndMs >= EVENT_GAP_MS || frameEnd - onset >= MAX_EVENT_MS) {
                actions += endEvent()
            }
        }

        addToFloor(levelDb)
        return actions
    }

    /** Aufnahme endet: offenen Ausschnitt und offenes Geräusch abschliessen. */
    fun finish(atMs: Long): List<Action> {
        val onset = onsetMs ?: return emptyList()
        val actions = mutableListOf<Action>()
        clipStartMs?.let { clip -> actions += Action.ClipEnd(onset, minOf(atMs, clip + MAX_CLIP_MS).coerceAtLeast(onset)) }
        clipStartMs = null
        actions += endEvent()
        return actions
    }

    private fun endEvent(): List<Action> {
        val onset = onsetMs ?: return emptyList()
        val result = mutableListOf<Action>()
        clipStartMs?.let { clip -> result += Action.ClipEnd(onset, minOf(lastLoudEndMs, clip + MAX_CLIP_MS)) }
        result += Action.EventEnd(onset, (lastLoudEndMs - onset).coerceAtLeast(0), peak)
        onsetMs = null
        clipStartMs = null
        peak = Double.NEGATIVE_INFINITY
        loudRun = 0
        return result
    }

    private fun addToFloor(levelDb: Double) {
        secondSum += levelDb
        secondFrames++
        if (secondFrames * FRAME_MS < 1000) return
        seconds.addLast(secondSum / secondFrames)
        secondSum = 0.0
        secondFrames = 0
        while (seconds.size > FLOOR_SECONDS) seconds.removeFirst()
        floor = if (seconds.size >= MIN_FLOOR_SECONDS) median(seconds) else null
    }

    companion object {
        const val FRAME_MS: Long = 100
        const val FLOOR_SECONDS: Int = 60
        const val MIN_FLOOR_SECONDS: Int = 10
        const val THRESHOLD_DB: Double = 12.0
        const val MIN_LOUD_MS: Long = 500
        const val PRE_ROLL_MS: Long = 2_000
        const val MAX_CLIP_MS: Long = 10_000
        const val CLIP_TAIL_MS: Long = 2_000
        const val EVENT_GAP_MS: Long = 30_000
        const val MAX_EVENT_MS: Long = 10 * 60_000

        fun median(values: Collection<Double>): Double {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
