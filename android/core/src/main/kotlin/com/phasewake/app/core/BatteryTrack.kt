package com.phasewake.app.core

import java.time.Duration
import java.time.Instant

/** Art eines Akku-Messpunkts einer Nacht ([BatteryTrack]). [code] steht im Speicherformat: nie ändern, nur ergänzen. */
enum class BatteryMark(val code: String) {
    /** Messbeginn der Nacht (Einschlaf-Messung, Aufzeichnung oder Weckfenster, was zuerst kommt). */
    START("s"),

    /** Zwischenstand, z. B. Beginn des Weckfensters nach der Einschlaf-Messung. */
    SAMPLE("m"),

    /** Ladebeginn: Ladegerät angeschlossen. */
    PLUG("c"),

    /** Ladeende: Ladegerät getrennt. */
    UNPLUG("u"),

    /** Ende der Nacht (Wecken beendet). */
    END("e"),
}

/** Akkustand des ganzen Geräts: [percent] 0–100, [plugged] = am Ladegerät. */
data class BatteryPoint(val at: Instant, val percent: Int, val plugged: Boolean, val mark: BatteryMark)

/**
 * Akkuverbrauch eines Geräts in einer Nacht (Entscheid r1AD 14.11.2026): Messpunkte vom Messbeginn bis zum Ende der Nacht.
 * Gemessen wird der Akkustand des ganzen Geräts (den Verbrauch nur dieser App liefert kein System). Zeit am Ladegerät zählt
 * nicht: Summiert werden nur die Teilstrecken ohne Laden. Ist etwas unbekannt (Laden ohne beobachteten Beginn oder Ende,
 * Anstieg ohne Laden, Zeit rückwärts), gibt es keinen Wert statt eines falschen. Identisch zu `BatteryTrack` (Swift).
 */
object BatteryTrack {
    /** Länger offen gilt eine Nacht als verwaist (z. B. nie beendet): Ein neuer Beginn ersetzt sie. */
    val MAX_SPAN: Duration = Duration.ofHours(14)

    /** Höchstens so viele Zwischenstände ([BatteryMark.SAMPLE]) je Nacht; Ladebeginn und -ende zählen immer. */
    const val MAX_SAMPLES: Int = 24

    /** Mehr Punkte liest [decode] nicht. */
    const val MAX_POINTS: Int = 64

    /** So weit darf eine Nacht beim Handy nach ihrem Ende eintreffen, damit «jetzt» noch als ihr Ende gilt. */
    val ARRIVAL_SLACK: Duration = Duration.ofMinutes(5)

    /**
     * Verbrauch in Prozentpunkten, nur aus den Teilstrecken ohne Ladegerät; `null`, wenn unbekannt: weniger als zwei
     * Punkte, nicht Beginn … Ende, Zeit rückwärts, ein Wechsel ans oder vom Ladegerät ohne beobachteten Ladebeginn bzw.
     * -ende, ein Anstieg ohne Ladegerät (unbemerkt geladen) oder gar keine Zeit ohne Ladegerät.
     */
    fun used(track: List<BatteryPoint>?): Int? {
        if (track == null || track.size < 2 || track.size > MAX_POINTS) return null
        if (track.first().mark != BatteryMark.START || track.last().mark != BatteryMark.END) return null
        if (track.subList(1, track.size - 1).any { it.mark == BatteryMark.START || it.mark == BatteryMark.END }) return null
        if (track.any { it.percent !in 0..100 || !consistent(it) }) return null
        var used = 0
        var unplugged = false
        for ((a, b) in track.zipWithNext()) {
            if (b.at.isBefore(a.at)) return null
            if (a.plugged != b.plugged && b.mark != (if (b.plugged) BatteryMark.PLUG else BatteryMark.UNPLUG)) return null
            if (!a.plugged) {
                if (b.percent > a.percent) return null
                used += a.percent - b.percent
                unplugged = true
            }
        }
        return if (unplugged) used else null
    }

    /** Ob [open] eine laufende Nacht ist (begonnen, nicht beendet, höchstens [MAX_SPAN] alt, [now] nicht davor). */
    fun isOpen(open: List<BatteryPoint>, now: Instant): Boolean {
        val first = open.firstOrNull() ?: return false
        return first.mark == BatteryMark.START && open.last().mark != BatteryMark.END && !now.isBefore(open.last().at) &&
            Duration.between(first.at, now) <= MAX_SPAN
    }

    /**
     * Messbeginn: Läuft schon eine Nacht ([isOpen]), bleibt ihr früherer Beginn und dieser Stand kommt als Zwischenstand
     * dazu; sonst beginnt eine neue (ersetzt eine verwaiste). Eine neue Nacht hat genau einen Punkt.
     */
    fun begin(open: List<BatteryPoint>, at: Instant, percent: Int, plugged: Boolean): List<BatteryPoint> =
        if (isOpen(open, at)) sample(open, at, percent, plugged) else listOf(BatteryPoint(at, percent, plugged, BatteryMark.START))

    /** Zwischenstand in einer laufenden Nacht (höchstens [MAX_SAMPLES]); sonst unverändert. */
    fun sample(open: List<BatteryPoint>, at: Instant, percent: Int, plugged: Boolean): List<BatteryPoint> {
        if (!isOpen(open, at) || open.count { it.mark == BatteryMark.SAMPLE } >= MAX_SAMPLES) return open
        return open + BatteryPoint(at, percent, plugged, BatteryMark.SAMPLE)
    }

    /** Ladegerät angeschlossen oder getrennt (beobachtet): Ladebeginn bzw. -ende, wenn sich der Stand ändert; sonst unverändert. */
    fun charge(open: List<BatteryPoint>, at: Instant, percent: Int, plugged: Boolean): List<BatteryPoint> {
        if (!isOpen(open, at) || open.last().plugged == plugged) return open
        return open + BatteryPoint(at, percent, plugged, if (plugged) BatteryMark.PLUG else BatteryMark.UNPLUG)
    }

    /** Ende der Nacht: die fertigen Punkte; `null`, wenn keine Nacht läuft. */
    fun end(open: List<BatteryPoint>, at: Instant, percent: Int, plugged: Boolean): List<BatteryPoint>? =
        if (isOpen(open, at)) open + BatteryPoint(at, percent, plugged, BatteryMark.END) else null

    /** Gehören die Punkte zu [night]? Das erste Wecken liegt zwischen Messbeginn und Ende. */
    fun belongsTo(track: List<BatteryPoint>, night: NightRecord): Boolean {
        val first = track.firstOrNull() ?: return false
        return !night.wokeAt.isBefore(first.at) && !night.wokeAt.isAfter(track.last().at)
    }

    /**
     * Kam [night] gleich nach ihrem Ende an (Ende der Uhr-Messung, sonst das erste Wecken), höchstens [ARRIVAL_SLACK]
     * daneben? Nur dann taugt «jetzt» als Ende der Nacht auf dem Handy.
     */
    fun arrivedFresh(night: NightRecord, now: Instant): Boolean {
        val end = night.watchBattery?.lastOrNull()?.at ?: night.wokeAt
        return Duration.between(end, now).abs() <= ARRIVAL_SLACK
    }

    /** «s,ms,prozent,0|1» je Punkt, durch «;» getrennt (ohne «|», passt in ein Feld von [Codec.encodeNight]). */
    fun encode(track: List<BatteryPoint>): String =
        track.joinToString(";") { "${it.mark.code},${it.at.toEpochMilli()},${it.percent},${if (it.plugged) 1 else 0}" }

    /** Liest [encode]; leer, fremd oder kaputt (auch eine unbekannte Art aus einer neueren Version): `null`. */
    fun decode(text: String?): List<BatteryPoint>? {
        if (text.isNullOrEmpty()) return null
        val parts = text.split(";")
        if (parts.size > MAX_POINTS) return null
        return parts.map { part ->
            val f = part.split(",")
            if (f.size != 4) return null
            val mark = BatteryMark.entries.firstOrNull { it.code == f[0] } ?: return null
            val ms = TimeBounds.checked(f[1].toLongOrNull()) ?: return null
            val percent = f[2].toIntOrNull()?.takeIf { it in 0..100 } ?: return null
            val plugged = when (f[3]) {
                "1" -> true
                "0" -> false
                else -> return null
            }
            BatteryPoint(Instant.ofEpochMilli(ms), percent, plugged, mark).takeIf(::consistent) ?: return null
        }
    }

    /** Für das Diagnose-Protokoll, z. B. «start 80% unplugged». */
    fun describe(point: BatteryPoint): String {
        val what = when (point.mark) {
            BatteryMark.START -> "start"
            BatteryMark.SAMPLE -> "sample"
            BatteryMark.PLUG -> "charge start"
            BatteryMark.UNPLUG -> "charge end"
            BatteryMark.END -> "end"
        }
        return "$what ${point.percent}% ${if (point.plugged) "plugged" else "unplugged"}"
    }

    /** Ladebeginn nur am Ladegerät, Ladeende nur ohne. */
    private fun consistent(point: BatteryPoint): Boolean =
        !(point.mark == BatteryMark.PLUG && !point.plugged) && !(point.mark == BatteryMark.UNPLUG && point.plugged)
}
