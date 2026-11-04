package com.phasewake.app.ring

import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Snooze
import com.phasewake.app.core.Transient
import com.phasewake.app.core.WakePlan
import com.phasewake.app.shared.RingMessage
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Entscheidungen des Zweitweckers ohne Android-Klassen (in Unit-Tests ohne Gerät prüfbar).

/** Ergebnis von [PhoneRingService.ring]. */
enum class RingResult {
    /** Neue Klingel-Runde: vorher still oder geschlummert. */
    STARTED,

    /**
     * Es klingelte schon; dieses Wecken übernimmt das Klingeln. Andere Nacht: Kennung und Zielzeit wechseln, die erste
     * Nacht gilt als geweckt. Gleiche Nacht (zweite Weckmeldung): es klingelt weiter.
     */
    TOOK_OVER,

    /** Der Weckdienst durfte nicht starten: Nichts klingelt, die Nacht darf nicht als geweckt gelten. */
    REJECTED,
    ;

    /** Ob dieses Wecken jetzt klingelt (nur dann [BackupAlarm.backupRang]). */
    val rings: Boolean get() = this != REJECTED
}

internal object RingRules {
    /** Was ein neues «klingeln» mit dem laufenden Klingeln macht. */
    enum class Incoming {
        /** Nichts klingelt (oder es ist geschlummert): neue Runde. */
        NEW_ROUND,

        /** Dieselbe Nacht klingelt schon: weiterklingeln, Restzahl der Uhr übernehmen. */
        SAME_NIGHT,

        /** Eine andere Nacht klingelt: dieses Wecken löst sie ab. */
        TAKE_OVER,
    }

    /**
     * Wie ein «klingeln» für Wecker [alarmId] mit Zielzeit [target] wirkt, während [ringing] die Nacht
     * [currentId]/[currentTarget] klingelt. Fehlende Angaben (Schlummer-Ende, ältere Uhr) widersprechen nicht
     * ([RingMessage.sameNight]).
     */
    fun incoming(ringing: Boolean, alarmId: String?, target: Long?, currentId: String?, currentTarget: Long?): Incoming = when {
        !ringing -> Incoming.NEW_ROUND
        RingMessage(alarmId, target).sameNight(currentId, currentTarget) -> Incoming.SAME_NIGHT
        else -> Incoming.TAKE_OVER
    }

    /** Zeitgrenze einer Klingel-Runde am Handy: Danach schlummert oder endet sie ([timeout]). */
    const val TIMEOUT_MS = 10 * 60_000L

    /** Abstand des Wiederaufnahme-Alarms nach der Zeitgrenze: Der Ablauf im Prozess kommt immer zuerst. */
    const val RESUME_MARGIN_MS = 30_000L

    /** Was die Zeitgrenze einer Klingel-Runde am Handy auslöst ([PhoneRingService]). */
    enum class Timeout {
        /** Das Handy führt die Nacht und hat noch Runden: selbst schlummern (wie die Uhr). */
        SNOOZE,

        /** Die Uhr führt und hat noch Runden (sie schlummert selbst): Handy still, eigene Sicherung nach dem Schlummer-Ende. */
        STOP_WITH_SAFETY,

        /** Letzte Runde oder nichts klingelt: beenden. */
        STOP,
    }

    /** Zeitgrenze erreicht, während [ringing] mit [snoozesLeft] Runden klingelt ([fromWatch]: die Uhr führt). */
    fun timeout(ringing: Boolean, fromWatch: Boolean, snoozesLeft: Int): Timeout = when {
        !ringing || snoozesLeft <= 0 -> Timeout.STOP
        fromWatch -> Timeout.STOP_WITH_SAFETY
        else -> Timeout.SNOOZE
    }

    /**
     * Schlummer-Runden einer neuen Klingel-Runde: Probewecker keine; führt die Uhr, zählt ihre Angabe [watchSnoozesLeft]
     * (ältere Uhren schicken keine: [legacyWatchSnoozes] aus dem Prozess); führt das Handy, allein [snoozesDone] aus dem
     * Schlummer- bzw. Wiederaufnahme-Alarm (übersteht ein Prozessende).
     */
    fun snoozesLeft(alarmId: String?, fromWatch: Boolean, watchSnoozesLeft: Int?, legacyWatchSnoozes: Int, snoozesDone: Int): Int {
        val left = when {
            !Transient.allowsSnooze(alarmId) -> 0
            fromWatch -> watchSnoozesLeft ?: (Snooze.MAX_COUNT - legacyWatchSnoozes)
            else -> Snooze.MAX_COUNT - snoozesDone
        }
        return left.coerceIn(0, Snooze.MAX_COUNT)
    }

    /** Bisherige Runden nach dem Schlummern einer Runde mit [snoozesLeft]; `null`, wenn keine Runde mehr bleibt. */
    fun snoozeDone(snoozesLeft: Int): Int? = if (snoozesLeft <= 0) null else Snooze.MAX_COUNT - snoozesLeft + 1

    /**
     * Wiederaufnahme-Alarm einer Klingel-Runde ab [nowMs]: nur wenn das Handy führt ([fromWatch] = false; sonst hat die
     * Uhr ihren eigenen), kurz nach der Zeitgrenze. Stirbt der Prozess, klingelt es damit wieder; `null` = keiner.
     */
    fun resumeAt(fromWatch: Boolean, nowMs: Long): Long? = if (fromWatch) null else nowMs + TIMEOUT_MS + RESUME_MARGIN_MS

    /** Ergebnis von [PhoneRingService.ring]: [started] = Dienst durfte starten, [ringing] = es klingelte schon. */
    fun result(started: Boolean, ringing: Boolean): RingResult = when {
        !started -> RingResult.REJECTED
        ringing -> RingResult.TOOK_OVER
        else -> RingResult.STARTED
    }

    /**
     * Ob ein «stop» der Uhr ([message]) das Klingeln des Handys beendet: Es läuft eines ([active]), die Meldung ist nicht
     * veraltet (älter als [RingMessage.MAX_AGE_MS], wie das iPhone in die andere Richtung) und gehört zur Nacht, die
     * gerade klingelt ([currentId]/[currentTarget]). Ein verspätetes «stop» einer anderen Nacht beendet sie nicht.
     */
    fun stopApplies(message: RingMessage, nowMs: Long, active: Boolean, currentId: String?, currentTarget: Long?): Boolean =
        active && !message.isStale(nowMs) && message.sameNight(currentId, currentTarget)

    /**
     * Ohne Meldung der Uhr so lange (Review N-5): keine Uhr mehr gekoppelt oder getragen. Eine Uhr in Gebrauch meldet
     * ihren Status mindestens stündlich, sobald sie neu plant.
     */
    val WATCH_GONE: Duration = Duration.ofDays(7)

    /**
     * Nachfrist des Zweitweckers: [grace], solange eine Uhr da ist; 0 (pünktlich zur Zielzeit, das Handy ist der Wecker),
     * wenn ihr Status ([watchSeenAtMs], «zuletzt gesehen») fehlt oder älter als [WATCH_GONE] ist (Review N-5, wie das
     * iPhone ohne gekoppelte Uhr). Eine Meldung aus der «Zukunft» (Uhrzeit zurückgestellt) gilt als da.
     */
    fun backupGrace(watchSeenAtMs: Long?, nowMs: Long, grace: Duration): Duration =
        if (watchSeenAtMs == null || nowMs - watchSeenAtMs > WATCH_GONE.toMillis()) Duration.ZERO else grace

    /** Ob ein «klingeln» der Uhr den Zweitwecker dieser Nacht aussetzt: nur, wenn es nicht veraltet ist (Review LATE). */
    fun ringPausesBackup(message: RingMessage, nowMs: Long): Boolean = !message.isStale(nowMs)

    /**
     * Zweitwecker, der als Nächstes gilt: der früheste von
     * - dem nächsten Termin jedes Weckers in [backups] (erst nach [suppressed], siehe [BackupAlarm.watchHandled]),
     * - dem schon geplanten Zweitwecker [plannedId]/[plannedTarget], solange seine Nachfrist läuft ([AlarmSchedule.inGrace]),
     * - demselben, wenn sich die Ortszeit seit der Planung in [plannedZone] verschoben hat und sein Fenster läuft ([keptBackup]),
     * - dem Probewecker oder Powernap [transient] (auch in der Nachfrist: er kommt nicht wieder), wenn nicht ausgesetzt.
     * Bei gleicher Zielzeit die kleinere Kennung. `null`, wenn keiner.
     */
    fun backupPlan(
        backups: List<AlarmSpec>,
        transient: WakePlan?,
        plannedId: String?,
        plannedTarget: Instant?,
        suppressed: Map<String, Instant>,
        now: Instant,
        grace: Duration,
        zone: ZoneId,
        holidays: Set<LocalDate>,
        plannedZone: ZoneId? = null,
    ): WakePlan? {
        val kept = keptBackup(backups, plannedId, plannedTarget, plannedZone, suppressed, now, grace, zone, holidays)
        val upcoming = backups.mapNotNull { spec ->
            AlarmSchedule.nextPlan(spec, maxOf(now, suppressed[spec.id] ?: Instant.EPOCH), zone, holidays)
        }
        val inGrace = backups.firstOrNull { it.id == plannedId }
            ?.let { AlarmSchedule.inGrace(it, plannedTarget, now, grace, zone, holidays, suppressed[it.id]) }
        val extra = transient?.takeIf { plan ->
            plan.target.plus(grace).isAfter(now) && (suppressed[plan.alarmId] ?: Instant.EPOCH) < plan.target
        }
        return (upcoming + listOfNotNull(inGrace, kept, extra)).minWithOrNull(compareBy({ it.target }, { it.alarmId }))
    }

    /**
     * Zeitzonenwechsel im laufenden Weckfenster (docs/ENTSCHEIDE.md, 04.11.2026): Wurde der geplante Zweitwecker
     * [plannedId]/[plannedTarget] in der Zone [plannedZone] geplant, hat sich die Ortszeit seitdem verschoben
     * ([AlarmSchedule.zoneShifted] gegenüber [zone]) und ist er dort eine begonnene
     * Nacht ([AlarmSchedule.begunNight] mit der Nachfrist [grace], ausgesetzt bis [suppressed]), bleibt er zur geplanten
     * absoluten Zeit. Sonst `null` (Ortszeit gleich, ohne Zone, Wecker geändert, Fenster noch nicht begonnen).
     */
    fun keptBackup(
        backups: List<AlarmSpec>,
        plannedId: String?,
        plannedTarget: Instant?,
        plannedZone: ZoneId?,
        suppressed: Map<String, Instant>,
        now: Instant,
        grace: Duration,
        zone: ZoneId,
        holidays: Set<LocalDate>,
    ): WakePlan? {
        if (plannedZone == null || plannedTarget == null || !AlarmSchedule.zoneShifted(plannedTarget, plannedZone, zone)) return null
        val spec = backups.firstOrNull { it.id == plannedId } ?: return null
        return AlarmSchedule.begunNight(spec, plannedTarget, plannedZone, now, grace, holidays, suppressed[spec.id])
    }

    /**
     * Zweitwecker ab [first] für die Zeit vor dem ersten Entsperren (nur dann ist allein der geräteverschlüsselte Speicher
     * lesbar): [first] und danach die folgenden, jeweils als hätte der vorige geklungen – seine Nacht bis Zielzeit +
     * [pause] ausgesetzt (wie `BackupAlarm.backupRang`), ein Einmal-Wecker danach aus. Höchstens [count] Einträge.
     */
    fun directBootPlans(
        first: WakePlan,
        backups: List<AlarmSpec>,
        transient: WakePlan?,
        suppressed: Map<String, Instant>,
        grace: Duration,
        pause: Duration,
        zone: ZoneId,
        holidays: Set<LocalDate>,
        count: Int,
    ): List<WakePlan> {
        val plans = mutableListOf(first)
        var specs = backups
        var paused = suppressed
        while (plans.size < count) {
            val last = plans.last()
            val until = last.target.plus(pause)
            paused = paused + (last.alarmId to maxOf(paused[last.alarmId] ?: Instant.EPOCH, until))
            specs = specs.filterNot { it.id == last.alarmId && it.isOneShot }
            plans += backupPlan(specs, transient, null, null, paused, last.target, grace, zone, holidays) ?: break
        }
        return plans
    }
}
