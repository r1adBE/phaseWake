package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Lern-Übersicht (Entscheid r1AD 13.11.2026): je Nacht seit dem Beginn der Lernphase, ob sie gezählt hat, und wenn
 * nicht, warum. Grundlage sind nur Angaben, die das Handy wirklich hat: die Aufzeichnungen der Uhr, ihre Meldungen
 * ([LearnSkip]), die gemerkten Nächte mit Wecker ([LearnLog]), Zustimmung und Schalter. Dazu die Regel der Warnung am
 * Abend ([shouldWarn]). Reine Logik; identisch zu `LearnNights` (Swift), gemeinsame Fälle in
 * `shared/testdata/learn-nights.vectors`.
 */
object LearnNights {
    /** Höchstens so viele Nächte zeigt die Übersicht. */
    const val MAX_NIGHTS = 14

    /**
     * Unter diesem Anteil (Prozent) Epochen mit Puls an den Epochen mit Bewegung gilt eine Nacht als «vermutlich nicht
     * getragen». Getragen liegt er höher: Galaxy Watch in Stichproben rund 20 %, Apple Watch (Puls aus Apple Health, etwa
     * alle 5 Min) rund 10 %. Bewegung allein unterscheidet nicht: Das Rauschen der Uhr ist am Handgelenk und auf dem Tisch
     * gleich (Befund 07.11.2026). Fehlt die Pulserlaubnis, sieht es gleich aus; der Tipp nennt beides.
     */
    const val MIN_PULSE_PERCENT = 2

    /** Die Prüfung «getragen» erst ab so vielen Epochen mit Bewegung (10 Min); darunter sagt der Anteil nichts. */
    const val MIN_WORN_EPOCHS = 20

    /** Die Warnung am Abend kommt so lange vor dem Beginn der Aufzeichnung (Beginn der Einschlaf-Messung). */
    val WARN_LEAD: Duration = Duration.ofMinutes(60)

    /** Ein ungenauer Alarm bzw. eine Hintergrund-Aktualisierung darf so viel vor [warnAt] kommen. */
    val WARN_EARLY: Duration = Duration.ofMinutes(10)

    /** Von Hand gestoppt (Kennungen von `RecordEnd`): auf der Uhr oder am Handy. */
    private val STOPPED = setOf("watch", "phone")

    /** Warum eine Nacht zählt oder nicht; [counted] = Haken in der Übersicht. Reihenfolge in [reason]. */
    enum class Reason(val counted: Boolean = false) {
        /** Gezählt. */
        COUNTED(true),

        /** Gezählt, aber kaum Puls: vermutlich nicht getragen (oder keine Pulserlaubnis); der Ruhepuls fehlt dann. */
        COUNTED_NO_PULSE(true),

        /** Von der ersten bis zur letzten Messung keine 3 Std. ([Learning.MIN_SPAN_EPOCHS]). */
        TOO_SHORT,

        /** Lang genug, aber zu wenige Messungen ([Learning.MIN_MEASURED_EPOCHS]), z. B. nach Lücken. */
        TOO_FEW,

        /** Auf der Uhr oder am Handy vorzeitig gestoppt. */
        ENDED_STOPPED,

        /** Vorzeitig beendet: Akku der Uhr in der Nacht zu tief (unter 15 %). */
        ENDED_BATTERY,

        /** Vorzeitig beendet: Messdienst vom System beendet oder nicht wieder aufgenommen. */
        ENDED_SERVICE,

        /** Nicht gezählt und kaum Puls: vermutlich nicht getragen. */
        NOT_WORN,

        /** Gemeldet: Akku am Abend zu tief. */
        BATTERY,

        /** Gemeldet: keine Sensoren bzw. keine Erlaubnis für Bewegungsdaten. */
        SENSORS,

        /** Gemeldet: Eine von Hand begonnene Aufzeichnung lief, ist aber nicht angekommen. */
        MANUAL,

        /** Gemeldet (Apple Watch): App am Abend zu spät gestartet. */
        LATE,

        /** Lernphase damals aus bzw. noch nicht begonnen (Aufzeichnung vor ihrem Beginn). */
        LEARNING_OFF,

        /** Kein Wecker in dieser Nacht: Die Uhr zeichnet nur Nächte mit Wecker auf. */
        NO_ALARM,

        /** Die letzte Nacht, Aufzeichnung kann noch kommen (bis Mittag). */
        PENDING,

        /** Galaxy Watch: «Einschlafen automatisch erkennen» aus, ohne «Gute Nacht» startet nichts (aktueller Schalter). */
        AUTO_OFF,

        /** Wecker bekannt, aber nichts angekommen: Uhr aus, nicht verbunden, App nicht geöffnet (Apple Watch). */
        NO_RECORDING,

        /** Nichts angekommen, und ob es einen Wecker gab, ist unbekannt. */
        UNKNOWN,
    }

    /**
     * Was über eine Nacht bekannt ist. Aufzeichnung ([recorded]: eine ab dem Beginn der Lernphase): Epochen mit Bewegung
     * [measuredEpochs], Spanne erste bis letzte von ihnen [spanEpochs], Epochen mit Puls [heartRateEpochs], Grund des Endes
     * [endCode] (`RecordEnd`). [beforeStart]: nur Aufzeichnungen, die vor dem Beginn der Lernphase begannen. Meldung der
     * Uhr [skip]. Zustimmung jetzt [consent], Wecker [alarm] (`true` gemerkt oder belegt, `false` sicher keiner, `null`
     * unbekannt), Schalter der Einschlaf-Messung [autoOnset] (Apple: immer `true`), [pending]: letzte Nacht, bis Mittag.
     */
    data class Facts(
        val recorded: Boolean = false,
        val measuredEpochs: Int = 0,
        val spanEpochs: Int = 0,
        val heartRateEpochs: Int = 0,
        val endCode: String? = null,
        val beforeStart: Boolean = false,
        val skip: LearnSkip.Reason? = null,
        val consent: Boolean = true,
        val alarm: Boolean? = null,
        val autoOnset: Boolean = true,
        val pending: Boolean = false,
    )

    /**
     * Grund einer Nacht. Reihenfolge: eine Aufzeichnung geht allem vor (zählt sie, zählt die Nacht – auch wenn die Uhr
     * vorher etwas gemeldet hat); dann Aufzeichnung vor dem Beginn, Meldung der Uhr, Zustimmung, kein Wecker, noch
     * unterwegs, Schalter, nichts angekommen.
     */
    fun reason(f: Facts): Reason {
        if (f.recorded) {
            val counts = f.measuredEpochs >= Learning.MIN_MEASURED_EPOCHS && f.spanEpochs >= Learning.MIN_SPAN_EPOCHS
            val notWorn = f.measuredEpochs >= MIN_WORN_EPOCHS && f.heartRateEpochs * 100 < f.measuredEpochs * MIN_PULSE_PERCENT
            return when {
                counts -> if (notWorn) Reason.COUNTED_NO_PULSE else Reason.COUNTED
                notWorn -> Reason.NOT_WORN
                f.endCode in STOPPED -> Reason.ENDED_STOPPED
                f.endCode == "battery" -> Reason.ENDED_BATTERY
                f.endCode == "service" -> Reason.ENDED_SERVICE
                f.spanEpochs < Learning.MIN_SPAN_EPOCHS -> Reason.TOO_SHORT
                else -> Reason.TOO_FEW
            }
        }
        return when {
            f.beforeStart -> Reason.LEARNING_OFF
            f.skip != null -> when (f.skip) {
                LearnSkip.Reason.BATTERY -> Reason.BATTERY
                LearnSkip.Reason.SENSORS -> Reason.SENSORS
                LearnSkip.Reason.MANUAL -> Reason.MANUAL
                LearnSkip.Reason.LATE -> Reason.LATE
            }
            !f.consent -> Reason.LEARNING_OFF
            f.alarm == false -> Reason.NO_ALARM
            f.pending -> Reason.PENDING
            !f.autoOnset -> Reason.AUTO_OFF
            f.alarm == true -> Reason.NO_RECORDING
            else -> Reason.UNKNOWN
        }
    }

    /** Spanne von der ersten bis zur letzten Epoche mit Bewegung (0 ohne Bewegung), wie in [Learning.counts]. */
    fun spanEpochs(night: Learning.Night): Int {
        val first = night.epochs.indexOfFirst { it.motion != null }
        if (first < 0) return 0
        return night.epochs.indexOfLast { it.motion != null } - first + 1
    }

    /** Anteil der Epochen mit Puls an denen mit Bewegung in ganzen Prozent (abgerundet, höchstens 100; 0 ohne Bewegung). */
    fun pulsePercent(measuredEpochs: Int, heartRateEpochs: Int): Int =
        if (measuredEpochs <= 0) 0 else minOf(100, heartRateEpochs * 100 / measuredEpochs)

    /** Eine Aufzeichnung für die Übersicht: [night] und Grund des Endes [endCode] (`RecordEnd.code`). */
    data class Recorded(val night: Learning.Night, val endCode: String?)

    /**
     * Eine Zeile der Übersicht: Nachtdatum [date] (Abend, an dem die Nacht beginnt), Grund [reason] und, falls
     * aufgezeichnet, gemessene Minuten (Spanne, [minutes]), Epochen mit Bewegung [measuredEpochs] und Pulsanteil
     * [pulsePercent]; bei einer Meldung zum Akku dessen Stand [battery].
     */
    data class Night(
        val date: LocalDate,
        val reason: Reason,
        val minutes: Int? = null,
        val measuredEpochs: Int? = null,
        val pulsePercent: Int? = null,
        val battery: Int? = null,
    )

    /**
     * Die Nächte seit dem Beginn der Lernphase [since] bis jetzt ([now], in [zone]), höchstens [MAX_NIGHTS], jüngste
     * zuerst. Die laufende Nacht erscheint nur mit Aufzeichnung oder Meldung, sonst erst ab Mitternacht (dann «noch
     * unterwegs», bis Mittag). Je Nacht die beste Aufzeichnung (eine zählende vor einer mit mehr Messungen). Wecker:
     * [alarmNights] (gemerkt oder belegt); ab [alarmsKnownFrom] gilt ein fehlendes Datum als «kein Wecker», davor als
     * unbekannt.
     */
    fun nights(
        recordings: List<Recorded>,
        skips: List<LearnSkip>,
        alarmNights: Set<LocalDate>,
        alarmsKnownFrom: LocalDate?,
        since: Instant,
        consent: Boolean,
        autoOnset: Boolean,
        now: Instant,
        zone: ZoneId,
    ): List<Night> {
        val today = SleepNotes.nightDate(now, zone)
        val first = SleepNotes.nightDate(since, zone)
        val byDate = recordings.groupBy { SleepNotes.nightDate(it.night.start, zone) }
        val skipByDate = skips.associateBy { SleepNotes.nightDate(it.target, zone) }
        val result = mutableListOf<Night>()
        var date = today
        while (!date.isBefore(first) && result.size < MAX_NIGHTS) {
            val all = byDate[date].orEmpty()
            val own = all.filter { !it.night.start.isBefore(since) }
            val best = own.maxWithOrNull(compareBy({ Learning.counts(it.night) }, { Learning.measuredEpochs(it.night) }))
            val skip = skipByDate[date]
            val afterMidnight = now.atZone(zone).toLocalDate() == date.plusDays(1)
            val current = date == today
            if (!current || best != null || skip != null || all.isNotEmpty() || afterMidnight) {
                result += night(date, best, all.isNotEmpty() && own.isEmpty(), skip, consent, alarmOf(date, alarmNights, alarmsKnownFrom), autoOnset, current)
            }
            date = date.minusDays(1)
        }
        return result
    }

    private fun alarmOf(date: LocalDate, nights: Set<LocalDate>, knownFrom: LocalDate?): Boolean? = when {
        date in nights -> true
        knownFrom != null && !date.isBefore(knownFrom) -> false
        else -> null
    }

    private fun night(
        date: LocalDate, best: Recorded?, beforeStart: Boolean, skip: LearnSkip?, consent: Boolean, alarm: Boolean?,
        autoOnset: Boolean, pending: Boolean,
    ): Night {
        val measured = best?.let { Learning.measuredEpochs(it.night) } ?: 0
        val span = best?.let { spanEpochs(it.night) } ?: 0
        val pulse = best?.night?.epochs?.count { it.heartRate != null } ?: 0
        val facts = Facts(
            recorded = best != null, measuredEpochs = measured, spanEpochs = span, heartRateEpochs = pulse,
            endCode = best?.endCode, beforeStart = beforeStart, skip = skip?.reason, consent = consent,
            alarm = alarm ?: if (skip != null) true else null, autoOnset = autoOnset, pending = pending,
        )
        return Night(
            date = date,
            reason = reason(facts),
            minutes = best?.let { span * DetectorConfig.EPOCH_SECONDS / 60 },
            measuredEpochs = best?.let { measured },
            pulsePercent = best?.let { pulsePercent(measured, pulse) },
            battery = skip?.battery?.takeIf { skip.reason == LearnSkip.Reason.BATTERY && best == null },
        )
    }

    /** Gründe, die auf der Startseite nicht als «letzte Nacht keine Lerndaten» erscheinen: erwartet bzw. noch offen. */
    private val QUIET = setOf(Reason.PENDING, Reason.NO_ALARM, Reason.LEARNING_OFF)

    /**
     * Startseite: die letzte Nacht aus [nights] (jüngste zuerst), wenn sie nicht gezählt hat und das nicht erwartet war
     * (nicht kein Wecker, nicht Lernphase aus, nicht noch unterwegs); sonst `null`. «Letzte» heisst: Nachtdatum heute
     * oder gestern ([SleepNotes.nightDate] von [now]).
     */
    fun lastNightMiss(nights: List<Night>, now: Instant, zone: ZoneId): Night? {
        val newest = nights.firstOrNull() ?: return null
        if (newest.date.isBefore(SleepNotes.nightDate(now, zone).minusDays(1))) return null
        return newest.takeIf { !it.reason.counted && it.reason !in QUIET }
    }

    /** Zeitpunkt der Warnung am Abend: [WARN_LEAD] vor dem Beginn der Aufzeichnung [recordStart]. */
    fun warnAt(recordStart: Instant): Instant = recordStart.minus(WARN_LEAD)

    /**
     * Ob die Uhr um [now] warnt «Lade die Uhr, sonst zählt die Lernnacht nicht»: Lernphase läuft ([active]), Akku
     * bekannt und unter [Learning.MIN_BATTERY], die Uhr lädt nicht ([charging]), und [now] liegt ab [warnAt] (höchstens
     * [WARN_EARLY] früher) vor dem Beginn [recordStart]. Unbekannter Akku warnt nicht (wie er auch nicht hindert).
     */
    fun shouldWarn(active: Boolean, battery: Int?, charging: Boolean, now: Instant, recordStart: Instant): Boolean =
        active && !charging && battery != null && battery < Learning.MIN_BATTERY &&
            !now.isBefore(warnAt(recordStart).minus(WARN_EARLY)) && now.isBefore(recordStart)
}
