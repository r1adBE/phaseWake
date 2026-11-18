package com.phasewake.app.core

import java.time.Duration
import java.time.Instant

/** Eine aufgezeichnete Weck-Nacht. Bleibt auf dem Gerät; Grundlage für Verlauf und Kalibrierung. */
data class NightRecord(
    /** Kennung des Weckers ([AlarmSpec.id]); bleibt, auch wenn der Wecker gelöscht wird. */
    val alarmId: String,
    /** Beginn des Weckfensters. */
    val windowStart: Instant,
    /** Zielzeit (spätester Weckzeitpunkt) dieser Nacht. */
    val target: Instant,
    /** Zeitpunkt des ersten Weckens (vor dem Schlummern). */
    val wokeAt: Instant,
    /** Warum in diesem Moment geweckt wurde. */
    val reason: WakeReason,
    /** Wie oft geschlummert wurde, 0 bis [Snooze.MAX_COUNT]. */
    val snoozes: Int,
    /** Alle Epochen des Fensters ab Fensterbeginn, inklusive Aufwärmphase. */
    val epochs: List<Epoch>,
    /** Wie erholt sich der Nutzer am Morgen fühlte, 1 (müde) bis 5 (erholt); `null` = nicht angegeben. */
    val feeling: Int? = null,
    /** Akkustand der Uhr in dieser Nacht ([BatteryTrack]); `null` = nicht gemessen (z. B. ältere Uhr-App). */
    val watchBattery: List<BatteryPoint>? = null,
    /** Akkustand des Handys in dieser Nacht ([BatteryTrack]), nur auf dem Handy; `null` = nicht gemessen. */
    val phoneBattery: List<BatteryPoint>? = null,
    /**
     * Beginn der Einschlafdauer dieser Nacht (Bettzeit der automatischen Messung bzw. «Gute Nacht»), von der Uhr
     * gemessen; `null` = unbekannt (nicht gemessen, ältere Uhr-App).
     */
    val bedAt: Instant? = null,
    /** Einschlafzeit dieser Nacht aus der Einschlaf-Messung der Uhr; `null` = unbekannt. */
    val asleepAt: Instant? = null,
) {
    init {
        require(feeling == null || feeling in FEELING_MIN..FEELING_MAX) {
            "feeling muss zwischen $FEELING_MIN und $FEELING_MAX liegen"
        }
    }

    /** Wie viele Minuten vor der Zielzeit geweckt wurde (0 bei Zielzeit und danach). */
    val minutesBeforeTarget: Long get() = Duration.between(wokeAt, target).toMinutes().coerceAtLeast(0)

    /** Wie viele Minuten nach der Zielzeit geweckt wurde, z. B. nach Schlummern (0 bei Zielzeit und davor). */
    val minutesAfterTarget: Long get() = Duration.between(target, wokeAt).toMinutes().coerceAtLeast(0)

    companion object {
        /** Schlechtestes Befinden (müde). */
        const val FEELING_MIN: Int = 1

        /** Bestes Befinden (erholt). */
        const val FEELING_MAX: Int = 5
    }
}

/**
 * Wie viele eigene Nächte ein Gerät behält: höchstens [maxNights], mit [maxAge] nur die, deren Wecken
 * ([NightRecord.wokeAt]) höchstens so lange vor jetzt liegt (genau [maxAge]: bleibt). Gleich in Swift.
 */
class NightKeep(val maxNights: Int, val maxAge: Duration?) {
    /** Die behaltenen Nächte nach Fensterbeginn sortiert (älteste zuerst); ältere fallen beim Speichern weg. */
    fun trim(nights: List<NightRecord>, now: Instant): List<NightRecord> {
        val from = maxAge?.let { now.minus(it) }
        return nights.filter { from == null || !it.wokeAt.isBefore(from) }
            .sortedBy { it.windowStart }.takeLast(maxNights)
    }

    companion object {
        /**
         * Handy: der Verlauf reicht [NightHistory.DAYS] Tage (2 Jahre) zurück. Höchstens [NightHistory.PHONE_NIGHTS]
         * Nächte, damit Nächte mit zwei Weckern die Datei nicht unbegrenzt wachsen lassen.
         */
        val PHONE = NightKeep(NightHistory.PHONE_NIGHTS, Duration.ofDays(NightHistory.DAYS))

        /** Uhr: wie bisher die jüngsten [NightHistory.WATCH_NIGHTS] Nächte, ohne Altersgrenze. */
        val WATCH = NightKeep(NightHistory.WATCH_NIGHTS, null)
    }
}

/** Aufbewahrung und CSV-Export des Verlaufs. */
object NightHistory {
    /** So viele Tage reicht der Verlauf auf dem Handy zurück (2 Jahre), eigene Nächte wie Gesundheitsdaten. */
    const val DAYS: Long = 730

    /**
     * Höchstzahl eigener Nächte auf dem Handy ([NightKeep.PHONE]): [DAYS] plus rund 10 % für Nächte mit zwei Weckern
     * oder Probeweckern. Begrenzt nur die Dateigrösse, im Alltag gilt die Grenze von [DAYS] Tagen.
     */
    const val PHONE_NIGHTS: Int = 800

    /** So viele Nächte behält die Uhr ([NightKeep.WATCH]). */
    const val WATCH_NIGHTS: Int = 90

    /**
     * Kennzahlen (Übersicht, Diagramme, Trends, Wirkung der Notizen, Wochenrückblick) rechnen über die jüngsten so
     * vielen eigenen Nächte ([latest]) und die Gesundheitsdaten der letzten so vielen Tage
     * ([HealthHistory.forInsights]), unabhängig davon, wie weit der Verlauf zurückreicht.
     */
    const val INSIGHT_NIGHTS: Int = 90

    /** Höchstzahl Nachtdaten für Notizen, Befinden und Haken: so weit wie der Verlauf ([DAYS] und heute). */
    const val MAX_DATES: Int = DAYS.toInt() + 1

    /** Die jüngsten [count] Nächte nach Fensterbeginn, älteste zuerst. */
    fun latest(nights: List<NightRecord>, count: Int = INSIGHT_NIGHTS): List<NightRecord> =
        nights.sortedBy { it.windowStart }.takeLast(count)

    /**
     * Anteil der Nächte in Prozent (gerundet), in denen im leichten Schlaf geweckt wurde. Es zählen nur
     * Nächte, in denen früher wecken erlaubt war: «Genau» ([WakeReason.EXACT]) und kurze Nächte
     * ([WakeReason.SHORT_NIGHT]) fehlen auch im Nenner. `null`, wenn keine solche Nacht da ist.
     */
    fun lightShare(nights: List<NightRecord>): Int? {
        val counted = nights.filter { it.reason != WakeReason.EXACT && it.reason != WakeReason.SHORT_NIGHT }
        if (counted.isEmpty()) return null
        return Math.round(100.0 * counted.count { it.reason == WakeReason.LIGHT_SLEEP } / counted.size).toInt()
    }

    /** Bewegungen der jüngsten [WATCH_NIGHTS] Nächte für [Calibration.calibrate], älteste zuerst. */
    fun motionsForCalibration(nights: List<NightRecord>): List<List<Double?>> =
        latest(nights, WATCH_NIGHTS).map { night -> night.epochs.map { it.motion } }

    /**
     * CSV-Export ohne Kennungen und ohne Datums- oder Uhrzeitangaben, nur relative Werte; mit Bewegungs- und Pulsreihen
     * je Nacht, darum nicht anonym im engeren Sinn.
     * Alle übergebenen Nächte (der ganze Verlauf), durchnummeriert (1 = älteste). Dezimalpunkt, Komma als Trenner.
     * Letzte Spalte `notes`: Schlafnotizen der Nacht mit «;» getrennt, eigene nur als «custom»
     * (am Ende angefügt, damit ältere Leser weiter funktionieren).
     *
     * @param notes Schlafnotizen je Nachtdatum (siehe [SleepNotes.nightDate]).
     */
    fun exportCsv(
        nights: List<NightRecord>,
        notes: Map<java.time.LocalDate, List<String>> = emptyMap(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): String = buildString {
        append("night,window_minutes,reason,woke_minutes_before_target,snoozes,feeling,epoch,motion,hr,notes\n")
        nights.sortedBy { it.windowStart }.forEachIndexed { nightIndex, night ->
            val windowMinutes = Duration.between(night.windowStart, night.target).toMinutes()
            val noteText =
                notes[SleepNotes.nightDate(night, zone)].orEmpty().joinToString(";") { SleepNotes.exportId(it) }
            night.epochs.forEachIndexed { epochIndex, epoch ->
                append(nightIndex + 1).append(',')
                append(windowMinutes).append(',')
                append(night.reason.name).append(',')
                append(night.minutesBeforeTarget).append(',')
                append(night.snoozes).append(',')
                append(night.feeling?.toString() ?: "").append(',')
                append(epochIndex).append(',')
                append(epoch.motion?.let { CsvNumbers.fixed(it, 6) } ?: "").append(',')
                append(epoch.heartRate?.let { CsvNumbers.fixed(it, 1) } ?: "").append(',')
                append(noteText).append('\n')
            }
        }
    }
}
