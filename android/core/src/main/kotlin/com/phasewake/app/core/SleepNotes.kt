package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/** Eine eigene Schlafnotiz, die der Nutzer benannt hat. [id] = «custom:<uuid>». */
data class CustomNote(val id: String, val name: String)

/**
 * Schlafnotizen: was am Abend war (Kaffee, Sport, …). Gehören zu einem Nachtdatum = lokales Datum
 * des Abends, an dem die Nacht beginnt. Bleiben auf dem Handy, werden nicht mit der Uhr abgeglichen.
 *
 * Speicherformat: je Nachtdatum eine Zeile «yyyy-MM-dd|id,id,…»; eigene Notizen je Zeile
 * «custom:<uuid>|Name».
 */
object SleepNotes {
    /** Feste Notiz «Kaffee am Nachmittag» (Kennung im Speicher, nie ändern). */
    const val CAFFEINE = "CAFFEINE"

    /** Feste Notiz «Alkohol». */
    const val ALCOHOL = "ALCOHOL"

    /** Feste Notiz «Sport». */
    const val EXERCISE = "EXERCISE"

    /** Feste Notiz «Stress». */
    const val STRESS = "STRESS"

    /** Feste Notiz «spät gegessen». */
    const val LATE_MEAL = "LATE_MEAL"

    /** Feste Notiz «Bildschirm im Bett». */
    const val SCREEN = "SCREEN"

    /** Feste Notiz «Mittagsschlaf». */
    const val NAP = "NAP"

    /** Feste Notiz «krank». */
    const val SICK = "SICK"

    /** Die festen Notizen in Anzeigereihenfolge; die Kennungen sind fest (Speicher). */
    val FIXED: List<String> = listOf(CAFFEINE, ALCOHOL, EXERCISE, STRESS, LATE_MEAL, SCREEN, NAP, SICK)

    /** Vorsatz der Kennung eigener Notizen («custom:<uuid>»). */
    const val CUSTOM_PREFIX = "custom:"

    /** Höchstzahl eigener Notizen. */
    const val MAX_CUSTOM = 5

    /** Höchstlänge des Namens einer eigenen Notiz in Zeichen. */
    const val MAX_NAME_LENGTH = 30

    /** Höchstens so viele Nachtdaten bleiben gespeichert (wie der Verlauf). */
    const val MAX_DATES = NightHistory.MAX_NIGHTS

    /** Ab dieser Uhrzeit fragt die Startseite nach den Notizen für die kommende Nacht. */
    val EVENING_START: LocalTime = LocalTime.of(17, 0)

    /** Verschiebung, mit der ein Zeitpunkt seinem Nachtdatum zugeordnet wird. */
    private val NIGHT_SHIFT: Duration = Duration.ofHours(12)

    /** Ob [id] eine eigene Notiz ist (Name in [CustomNote], im Export nur «custom»). */
    fun isCustom(id: String): Boolean = id.startsWith(CUSTOM_PREFIX)

    /** Neue Kennung für eine eigene Notiz aus einer UUID (als Text). */
    fun customId(uuid: String): String = CUSTOM_PREFIX + uuid

    /**
     * Nachtdatum eines Zeitpunkts: lokales Datum von [at] − 12 h. Für eine Nacht aus dem Verlauf
     * gilt ihr Fensterbeginn, für eine Nacht aus der Gesundheits-App ihr Ende, für «jetzt» die
     * aktuelle Zeit (18:00 und 02:00 gehören zum selben Abend).
     */
    fun nightDate(at: Instant, zone: ZoneId): LocalDate = at.minus(NIGHT_SHIFT).atZone(zone).toLocalDate()

    /** Nachtdatum einer Uhr-Nacht: über ihren Fensterbeginn. */
    fun nightDate(night: NightRecord, zone: ZoneId): LocalDate = nightDate(night.windowStart, zone)

    /**
     * Ob die Startseite nach den Notizen für die kommende Nacht fragt: ab 17:00 des Abends bis zum
     * Beginn des Weckfensters, wenn das nächste Fenster [nextWindowStart] zu dieser Nacht gehört;
     * sonst ab 17:00 bis Mitternacht.
     */
    fun entryVisible(now: Instant, zone: ZoneId, nextWindowStart: Instant?): Boolean {
        val date = nightDate(now, zone)
        if (now.isBefore(ZonedDateTime.of(date, EVENING_START, zone).toInstant())) return false
        return if (nextWindowStart != null && nightDate(nextWindowStart, zone) == date) {
            now.isBefore(nextWindowStart)
        } else {
            now.atZone(zone).toLocalDate() == date
        }
    }

    /** Kehrt eine Notiz für ein Nachtdatum um; leere Daten fallen weg, höchstens [MAX_DATES] bleiben. */
    fun toggle(notes: Map<LocalDate, List<String>>, date: LocalDate, id: String): Map<LocalDate, List<String>> {
        val current = notes[date].orEmpty()
        val updated = if (id in current) current - id else current + id
        val result = notes.toMutableMap()
        if (updated.isEmpty()) result.remove(date) else result[date] = updated
        return trim(result)
    }

    /** Behält die jüngsten [MAX_DATES] Nachtdaten. */
    fun trim(notes: Map<LocalDate, List<String>>): Map<LocalDate, List<String>> =
        notes.filterValues { it.isNotEmpty() }.toSortedMap().entries.toList().takeLast(MAX_DATES)
            .associate { it.key to it.value }

    /** Je Nachtdatum eine Zeile «yyyy-MM-dd|id,id», nach Datum sortiert. */
    fun encode(notes: Map<LocalDate, List<String>>): String =
        trim(notes).entries.joinToString("\n") { (date, ids) -> "$date|" + ids.distinct().joinToString(",") }

    /** Liest [encode]; unlesbare Zeilen werden übersprungen. */
    fun decode(text: String?): Map<LocalDate, List<String>> {
        val result = sortedMapOf<LocalDate, List<String>>()
        text.orEmpty().lines().forEach { line ->
            val date = runCatching { LocalDate.parse(line.substringBefore('|')) }.getOrNull() ?: return@forEach
            val ids = line.substringAfter('|', "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (ids.isNotEmpty()) result[date] = ids
        }
        return trim(result)
    }

    /** Bereinigt einen Namen: ohne Zeilenumbrüche, getrimmt, höchstens [MAX_NAME_LENGTH] Zeichen. */
    fun cleanName(name: String): String =
        name.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_NAME_LENGTH).trim()

    /** Eigene Notizen je Zeile «custom:<uuid>|Name». */
    fun encodeCustom(notes: List<CustomNote>): String =
        notes.take(MAX_CUSTOM).joinToString("\n") { "${it.id}|${cleanName(it.name)}" }

    /**
     * Liest [encodeCustom]; Zeilen ohne gültige Kennung oder mit leerem Namen fallen weg, doppelte
     * Kennungen zählen einmal, höchstens [MAX_CUSTOM].
     */
    fun decodeCustom(text: String?): List<CustomNote> =
        text.orEmpty().lines().mapNotNull { line ->
            val id = line.substringBefore('|')
            val name = cleanName(line.substringAfter('|', ""))
            if (!isCustom(id) || id.length <= CUSTOM_PREFIX.length || name.isEmpty()) null else CustomNote(id, name)
        }.distinctBy { it.id }.take(MAX_CUSTOM)

    /** Kennung im CSV-Export: feste Notizen mit Kennung, eigene nur als «custom» (Name bleibt privat). */
    fun exportId(id: String): String = if (isCustom(id)) "custom" else id
}

/**
 * Wie eine Notiz mit dem Befinden am Morgen zusammenhängt, über die letzten [NightHistory.MAX_NIGHTS]
 * Nächte mit Befinden. Kein Beweis, nur ein Hinweis – darum erst ab [MIN_NIGHTS] Nächten je Seite.
 */
data class NoteEffect(
    /** Kennung der Notiz. */
    val noteId: String,
    /** Nächte mit Befinden und dieser Notiz. */
    val nightsWith: Int,
    /** Nächte mit Befinden ohne diese Notiz. */
    val nightsWithout: Int,
    /** Durchschnittliches Befinden (1–5) mit Notiz. */
    val avgFeelingWith: Double,
    /** Durchschnittliches Befinden (1–5) ohne Notiz. */
    val avgFeelingWithout: Double,
    /** Durchschnittlicher Schlaf in Minuten mit Notiz; `null` ohne genug Nächte mit Schlafdauer. */
    val asleepWith: Double?,
    /** Durchschnittlicher Schlaf in Minuten ohne Notiz; `null` wie bei [asleepWith]. */
    val asleepWithout: Double?,
) {
    /** Befinden mit minus ohne Notiz. */
    val delta: Double get() = avgFeelingWith - avgFeelingWithout

    /** Ob der Unterschied gross genug für die Anzeige ist. */
    val isNotable: Boolean get() = abs(delta) >= NOTABLE_DELTA - 1e-9

    companion object {
        /** Mindestanzahl Nächte mit und ohne Notiz, damit eine Wirkung berechnet wird. */
        const val MIN_NIGHTS: Int = 3

        /** Ab diesem Unterschied im Befinden (Punkte auf der Skala 1–5) wird die Wirkung gezeigt. */
        const val NOTABLE_DELTA: Double = 0.5

        /**
         * Wirkung jeder Notiz, die in den betrachteten Nächten vorkommt, nach |delta| absteigend
         * (bei Gleichstand nach Kennung). Betrachtet werden die letzten [NightHistory.MAX_NIGHTS]
         * Nächte mit Befinden; mehrere Nächte mit demselben Nachtdatum zählen einmal (die zuletzt
         * geweckte). «Ohne» = jede dieser Nächte, an der die Notiz nicht gesetzt ist.
         *
         * @param asleep Schlafdauer in Minuten je Nachtdatum (aus der Gesundheits-App), falls vorhanden.
         * @param health Befinden zu Nächten aus anderen Apps; zählt an Nachtdaten ohne eigenes ([HealthFeelings.byDate]).
         */
        fun compute(
            nights: List<NightRecord>,
            notes: Map<LocalDate, List<String>>,
            zone: ZoneId,
            asleep: Map<LocalDate, Long> = emptyMap(),
            health: List<HealthFeeling> = emptyList(),
        ): List<NoteEffect> {
            val rated = HealthFeelings.byDate(NightHistory.trim(nights), health, zone)
            val ids = rated.keys.flatMap { notes[it].orEmpty() }.distinct()
            return ids.mapNotNull { id ->
                val (with, without) = rated.entries.partition { id in notes[it.key].orEmpty() }
                if (with.size < MIN_NIGHTS || without.size < MIN_NIGHTS) return@mapNotNull null
                fun asleepAverage(entries: List<Map.Entry<LocalDate, Int>>): Double? =
                    entries.mapNotNull { asleep[it.key] }.takeIf { it.size >= MIN_NIGHTS }?.average()
                val asleepWith = asleepAverage(with)
                val asleepWithout = asleepAverage(without)
                NoteEffect(
                    noteId = id,
                    nightsWith = with.size,
                    nightsWithout = without.size,
                    avgFeelingWith = with.map { it.value }.average(),
                    avgFeelingWithout = without.map { it.value }.average(),
                    // Beide Seiten oder keine: ein Vergleich braucht zwei Werte.
                    asleepWith = asleepWith.takeIf { asleepWithout != null },
                    asleepWithout = asleepWithout.takeIf { asleepWith != null },
                )
            }.sortedWith(compareByDescending<NoteEffect> { abs(it.delta) }.thenBy { it.noteId })
        }

        /**
         * Schlafdauer je Nachtdatum aus der Gesundheits-App: Nachtdatum über das Ende der Nacht;
         * mehrere Nächte am selben Datum → die längste.
         */
        fun asleepByNight(sleeps: List<SleepSummary>, zone: ZoneId): Map<LocalDate, Long> =
            sleeps.groupBy { SleepNotes.nightDate(it.end, zone) }.mapValues { (_, list) -> list.maxOf { it.asleepMinutes } }
    }
}
