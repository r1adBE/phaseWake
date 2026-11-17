package com.phasewake.app.core

import java.time.Instant
import java.time.ZoneId

/**
 * Anzeige des Validierungs-Modus ([Validation]): Abschnitt im Diagnose-Bericht ([lines]) und CSV-Export ([exportCsv],
 * Datei «phasewake-validation.csv»). Technische Zeilen auf Englisch wie das Diagnose-Protokoll. Identisch zu
 * `ValidationReport` (Swift).
 */
object ValidationReport {
    /** Titel des Abschnitts im Diagnose-Bericht. */
    const val TITLE: String = "VALIDATION"

    /** Erste Zeile von [lines]: was die Referenz ist und wie die Summen zu lesen sind. */
    const val NOTE: String =
        "reference = sleep stages of the health app (estimate of the watch, not a sleep lab), ±1 min; " +
        "x/y = light, REM or awake of decisions with a known stage; target = DEADLINE, EXACT, SHORT_NIGHT"

    /** Kopfzeile von [exportCsv]. */
    const val CSV_HEADER: String = "night,time,kind,reason,ref_stage"

    /** Zählt die Referenzphasen von Entscheiden ([Validation.Ref.NONE] zählt nicht). */
    data class Tally(
        val awake: Int = 0,
        val light: Int = 0,
        val rem: Int = 0,
        val deep: Int = 0,
        val unknown: Int = 0,
    ) {
        /** Leicht, REM oder wach: ein guter Moment. */
        val good: Int get() = awake + light + rem

        /** Mit bekannter Phase. */
        val known: Int get() = good + deep

        /** «gut/bekannt», dazu « unknown N» mit unbekannten; ganz ohne Entscheid «-». */
        fun ratio(): String = when {
            known == 0 && unknown == 0 -> "-"
            unknown > 0 -> "$good/$known unknown $unknown"
            else -> "$good/$known"
        }

        /** Phasen mit Anzahl, z. B. «light 1 deep 2» (Reihenfolge awake, light, rem, deep, unknown); ohne «-». */
        fun stages(): String =
            listOf("awake" to awake, "light" to light, "rem" to rem, "deep" to deep, "unknown" to unknown)
                .filter { it.second > 0 }.joinToString(" ") { "${it.first} ${it.second}" }.ifEmpty { "-" }

        companion object {
            /** Zählt die Referenzphasen von [entries] ([Validation.Ref.NONE] zählt nicht). */
            fun of(entries: List<Validation.Entry>): Tally = Tally(
                awake = entries.count { it.ref == Validation.Ref.AWAKE },
                light = entries.count { it.ref == Validation.Ref.LIGHT },
                rem = entries.count { it.ref == Validation.Ref.REM },
                deep = entries.count { it.ref == Validation.Ref.DEEP },
                unknown = entries.count { it.ref == Validation.Ref.UNKNOWN },
            )
        }
    }

    /**
     * Summen über [entries] mit Referenz: LIGHT_SLEEP echt und Schatten, Phase zur Zielzeit ([WakeReason.atTarget])
     * echt und Schatten, Trefferquote aller echten Entscheide.
     */
    fun summary(entries: List<Validation.Entry>): String {
        val counted = entries.filter { it.ref != Validation.Ref.NONE }
        fun of(kind: Validation.Kind, filter: (WakeReason) -> Boolean) =
            Tally.of(counted.filter { it.kind == kind && filter(it.reason) })
        val real = Validation.Kind.REAL
        val shadow = Validation.Kind.SHADOW
        val light = { kind: Validation.Kind -> of(kind) { it == WakeReason.LIGHT_SLEEP }.ratio() }
        return "LIGHT_SLEEP real ${light(real)} shadow ${light(shadow)}" +
            " · target real ${of(real) { it.atTarget }.stages()} shadow ${of(shadow) { it.atTarget }.stages()}" +
            " · hits real ${of(real) { true }.ratio()}"
    }

    /**
     * Zeilen des Abschnitts: [NOTE], je Nacht ihre Entscheide «night N HH:mm kind REASON ref» und «night N: …» mit den
     * Summen ([summary]) bzw. «night N: no reference», zuletzt «all: N nights, M with reference · …». Ohne Entscheide
     * leer.
     */
    fun lines(entries: List<Validation.Entry>): List<String> {
        if (entries.isEmpty()) return emptyList()
        val byNight = entries.groupBy { it.night }
        val out = mutableListOf(NOTE)
        for ((night, list) in byNight) {
            list.forEach {
                out += "night $night ${time(it.at, it.zone)} ${it.kind.code} ${it.reason.name} ${it.ref.code}"
            }
            val noRef = list.all { it.ref == Validation.Ref.NONE }
            out += if (noRef) "night $night: no reference" else "night $night: ${summary(list)}"
        }
        val withRef = byNight.values.count { list -> list.any { it.ref != Validation.Ref.NONE } }
        val all = if (withRef > 0) " · ${summary(entries)}" else ""
        out += "all: ${byNight.size} nights, $withRef with reference$all"
        return out
    }

    /** CSV «night,time,kind,reason,ref_stage», je Entscheid eine Zeile; Uhrzeit «HH:mm» ohne Datum. */
    fun exportCsv(entries: List<Validation.Entry>): String = buildString {
        append(CSV_HEADER).append('\n')
        for (e in entries) {
            append(e.night).append(',').append(time(e.at, e.zone)).append(',').append(e.kind.code).append(',')
            append(e.reason.name).append(',').append(e.ref.code).append('\n')
        }
    }

    /** «HH:mm» in [zone], gregorianisch mit Ziffern 0–9 wie Swift. */
    fun time(at: Instant, zone: ZoneId): String {
        val local = at.atZone(zone)
        return "%02d:%02d".format(java.util.Locale.ROOT, local.hour, local.minute)
    }
}
