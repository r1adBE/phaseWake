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
     * (jedes «klingeln» trägt sie, eines ohne wird vorher verworfen); sonst allein [snoozesDone] aus dem Schlummer- bzw.
     * Wiederaufnahme-Alarm (übersteht ein Prozessende).
     */
    fun snoozesLeft(alarmId: String?, fromWatch: Boolean, watchSnoozesLeft: Int?, snoozesDone: Int): Int {
        val left = when {
            !Transient.allowsSnooze(alarmId) -> 0
            fromWatch && watchSnoozesLeft != null -> watchSnoozesLeft
            else -> Snooze.MAX_COUNT - snoozesDone
        }
        return left.coerceIn(0, Snooze.MAX_COUNT)
    }

    /** Bisherige Runden nach dem Schlummern einer Runde mit [snoozesLeft]; `null`, wenn keine Runde mehr bleibt. */
    fun snoozeDone(snoozesLeft: Int): Int? = if (snoozesLeft <= 0) null else Snooze.MAX_COUNT - snoozesLeft + 1

    /**
     * Wiederaufnahme-Alarm einer Klingel-Runde ab [nowMs], kurz nach der Zeitgrenze: Stirbt der Prozess, klingelt das
     * Handy damit wieder. Auch wenn die Uhr führt (docs/ENTSCHEIDE.md, 04.11.2026); ihr «stop» (auch beim Schlummern)
     * und ihre Nacht heben ihn auf ([watchEndsPhoneSnooze]), ein neues «klingeln» stellt ihn neu.
     */
    fun resumeAt(nowMs: Long): Long = nowMs + TIMEOUT_MS + RESUME_MARGIN_MS

    /**
     * Bisherige Runden für den Wiederaufnahme-Alarm einer Runde mit [snoozesLeft]: Führt die Uhr ([fromWatch]), nach
     * ihrer Restzahl (klingelt es wieder, dann mit den Runden, die der Uhr noch bleiben), sonst [snoozesDone].
     */
    fun resumeSnoozesDone(fromWatch: Boolean, snoozesLeft: Int, snoozesDone: Int): Int =
        (if (fromWatch) Snooze.MAX_COUNT - snoozesLeft else snoozesDone).coerceIn(0, Snooze.MAX_COUNT)

    /**
     * Ob ein «stop» der Uhr oder ihre gemeldete Nacht ([alarmId]/[target]) das abgelegte Schlummern bzw. die
     * Wiederaufnahme am Handy ([savedId]/[savedTarget]) aufhebt: nur für dieselbe Nacht ([RingMessage.sameNight], wie
     * [stopApplies]); fehlende Angaben widersprechen nicht.
     */
    fun watchEndsPhoneSnooze(alarmId: String?, target: Long?, savedId: String?, savedTarget: Long?): Boolean =
        RingMessage(alarmId, target).sameNight(savedId, savedTarget)

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
     * - dem Probewecker oder Powernap [transient] (auch in der Nachfrist: er kommt nicht wieder), wenn nicht ausgesetzt,
     * - einem nachzuholenden Termin eines anderen Weckers ([catchUp]).
     * Bei gleicher Zielzeit die kleinere Kennung. `null`, wenn keiner.
     *
     * Bleibt die Nacht [planned] über einen Zonenwechsel ([keptBackup]), gilt sie bis zum Klingeln bzw. Aussetzen allein
     * (neben einem Probewecker oder Powernap): Ein anderer Wecker, dessen neue Ortszeit in ihr Fenster fällt, verdrängt
     * sie nicht, er folgt danach.
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
        planned: PlannedBackup? = null,
    ): WakePlan? {
        val kept = keptBackup(backups, planned, suppressed, now, grace, zone, holidays)
        val extra = transient?.takeIf { plan ->
            plan.target.plus(grace).isAfter(now) && (suppressed[plan.alarmId] ?: Instant.EPOCH) < plan.target
        }
        if (kept != null) return listOfNotNull(kept, extra).minWithOrNull(compareBy({ it.target }, { it.alarmId }))
        val upcoming = backups.mapNotNull { spec ->
            AlarmSchedule.nextPlan(spec, maxOf(now, suppressed[spec.id] ?: Instant.EPOCH), zone, holidays)
        }
        val inGrace = backups.firstOrNull { it.id == plannedId }
            ?.let { AlarmSchedule.inGrace(it, plannedTarget, now, grace, zone, holidays, suppressed[it.id]) }
        val missed = backups.mapNotNull { catchUp(it, plannedId, plannedTarget, planned, suppressed, now, grace, zone, holidays) }
        return (upcoming + missed + listOfNotNull(inGrace, extra)).minWithOrNull(compareBy({ it.target }, { it.alarmId }))
    }

    /**
     * Wie lange nach Ablauf der Nachfrist ein verdrängter Zweitwecker noch nachgeholt wird ([catchUp]): Es gibt nur
     * einen geplanten Zweitwecker; klingelt ein Wecker in der Nachfrist eines anderen, ist dessen Zeit danach schon vorbei.
     */
    val CATCH_UP: Duration = Duration.ofMinutes(2)

    /** Frühestens so bald nach dem Planen klingelt ein nachgeholter Zweitwecker ([backupAt]). */
    private val CATCH_UP_DELAY: Duration = Duration.ofSeconds(1)

    /**
     * Nachholen in der Nachfrist (Review 04.11. e): Ein Termin von [spec] in [now] − ([grace] + [CATCH_UP]) bis [now],
     * der noch gilt (`nextTarget` ab Fensterbeginn − 1 ms, in [zone] mit [holidays]) und nicht ausgesetzt ist
     * ([suppressed] vor der Zielzeit: nicht geklungen, die Uhr hat ihn nicht übernommen), klingelt gleich – statt erst
     * am nächsten Termin (zweiter Wecker eine Minute nach dem ersten, beide auf derselben Minute). Nur Termine ab der
     * Zielzeit des geplanten Zweitweckers [plannedTarget] (verdrängt; ein soeben eingestellter Wecker in der
     * Vergangenheit klingelt nicht) und nie dieser selbst ([plannedId], auch mit der eingestellten Zielzeit aus
     * [planned]): Er hat schon ausgelöst oder gilt über [AlarmSchedule.inGrace]; abgewiesen klingelt er nicht in
     * Schleife. Sonst `null`.
     */
    fun catchUp(
        spec: AlarmSpec,
        plannedId: String?,
        plannedTarget: Instant?,
        planned: PlannedBackup?,
        suppressed: Map<String, Instant>,
        now: Instant,
        grace: Duration,
        zone: ZoneId,
        holidays: Set<LocalDate>,
    ): WakePlan? {
        val since = plannedTarget ?: return null
        val from = now.minus(grace).minus(CATCH_UP).minusMillis(1)
        val target = AlarmSchedule.nextTarget(spec, from, zone, holidays) ?: return null
        if (target.isAfter(now) || target.isBefore(since)) return null
        if (spec.id == plannedId && (target == plannedTarget || target == planned?.target)) return null
        if ((suppressed[spec.id] ?: Instant.EPOCH) >= target) return null
        return AlarmSchedule.planAt(spec, target)
    }

    /**
     * Wann der Zweitwecker [plan] auslöst: Zielzeit + [grace], ist das schon vorbei (nachgeholt, [catchUp]), gleich nach
     * [now].
     */
    fun backupAt(plan: WakePlan, grace: Duration, now: Instant): Instant = maxOf(plan.target.plus(grace), now.plus(CATCH_UP_DELAY))

    /**
     * Geplanter Zweitwecker für [keptBackup]: Wecker [alarmId], eingestellte Zielzeit [target] dieser Nacht (ohne
     * Einschlaf-Modus), Zone der Planung [zone] (`null` bei älteren Daten) und, wenn die Uhr die Zielzeit vorverlegt hat,
     * die frühere Zielzeit [earlierTarget] (`OnsetShift`).
     */
    data class PlannedBackup(val alarmId: String, val target: Instant, val zone: ZoneId?, val earlierTarget: Instant? = null)

    /**
     * Zeitzonenwechsel im laufenden Weckfenster (docs/ENTSCHEIDE.md, 04.11.2026): Hat sich die Ortszeit seit der
     * Planung von [planned] verschoben ([AlarmSchedule.zoneShifted] gegenüber [zone]) und ist dessen eingestellte
     * Zielzeit in der Zone der Planung eine begonnene Nacht ([AlarmSchedule.begunNight] mit der Nachfrist [grace],
     * ausgesetzt bis [suppressed]), bleibt sie zur geplanten absoluten Zeit; eine frühere Zielzeit des Einschlaf-Modus
     * legt der Aufrufer danach wie gewohnt darüber. Mit früherer Zielzeit beginnt die Nacht schon mit deren Fenster
     * (frühere Zielzeit, höchstens der eingestellte Fensterbeginn, wie `OnsetShift.applyTo`). Sonst `null` (Ortszeit
     * gleich, ohne Zone, Wecker geändert, Fenster noch nicht begonnen).
     */
    fun keptBackup(
        backups: List<AlarmSpec>,
        planned: PlannedBackup?,
        suppressed: Map<String, Instant>,
        now: Instant,
        grace: Duration,
        zone: ZoneId,
        holidays: Set<LocalDate>,
    ): WakePlan? {
        if (planned == null) return null
        val plannedZone = planned.zone ?: return null
        if (!AlarmSchedule.zoneShifted(planned.target, plannedZone, zone)) return null
        val spec = backups.firstOrNull { it.id == planned.alarmId } ?: return null
        val windowStart = AlarmSchedule.planAt(spec, planned.target).windowStart
        val begunFrom = planned.earlierTarget?.let { minOf(windowStart, it) }
        val at = if (begunFrom != null && !now.isBefore(begunFrom)) maxOf(now, windowStart) else now
        return AlarmSchedule.begunNight(spec, planned.target, plannedZone, at, grace, holidays, suppressed[spec.id])
    }

    /**
     * Zweitwecker ab [first] für die Zeit vor dem ersten Entsperren (nur dann ist allein der geräteverschlüsselte Speicher
     * lesbar): [first] und danach pro Wecker seine folgenden Termine, zusammengeführt nach Zielzeit (bei Gleichstand die
     * kleinere Kennung, wie `BackupPlan.nights` auf dem iPhone). Andere Wecker ab der Zielzeit von [first] (auch auf
     * derselben Minute), nicht ausgesetzte ([suppressed]); jede Nacht gilt als geklungen – bis Zielzeit + [pause]
     * ausgesetzt (wie `BackupAlarm.backupRang`), ein Einmal-Wecker danach aus. Dazu der Probewecker oder Powernap
     * [transient], solange seine Nachfrist [grace] nach [first] läuft. Höchstens [count] Einträge; [first] ist immer
     * dabei, und die ganze Liste ist nach Zielzeit geordnet (ein Powernap mit Zielzeit vor [first] steht vorne, Audit I-05).
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
        if (count <= 1) return listOf(first)
        val following = backups.flatMap { spec ->
            val nights = mutableListOf<WakePlan>()
            if (spec.id == first.alarmId && spec.isOneShot) return@flatMap nights
            val start = if (spec.id == first.alarmId) first.target.plus(pause) else first.target.minusMillis(1)
            var from = maxOf(start, suppressed[spec.id] ?: Instant.EPOCH)
            while (nights.size < count - 1) {
                val night = AlarmSchedule.nextPlan(spec, from, zone, holidays) ?: break
                nights += night
                if (spec.isOneShot) break
                from = night.target.plus(pause)
            }
            nights
        }
        val nap = transient?.takeIf { plan ->
            plan.alarmId != first.alarmId && plan.target.plus(grace).isAfter(first.target) &&
                (suppressed[plan.alarmId] ?: Instant.EPOCH) < plan.target
        }
        val order = compareBy<WakePlan>({ it.target }, { it.alarmId })
        val others = (following + listOfNotNull(nap)).sortedWith(order).take(count - 1)
        return (others + first).sortedWith(order)
    }
}
