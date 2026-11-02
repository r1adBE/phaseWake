package com.phasewake.app.ring

import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
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
     * Zweitwecker, der als Nächstes gilt: der früheste von
     * - dem nächsten Termin jedes Weckers in [backups] (erst nach [suppressed], siehe [BackupAlarm.watchHandled]),
     * - dem schon geplanten Zweitwecker [plannedId]/[plannedTarget], solange seine Nachfrist läuft ([AlarmSchedule.inGrace]),
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
    ): WakePlan? {
        val upcoming = backups.mapNotNull { spec ->
            AlarmSchedule.nextPlan(spec, maxOf(now, suppressed[spec.id] ?: Instant.EPOCH), zone, holidays)
        }
        val inGrace = backups.firstOrNull { it.id == plannedId }
            ?.let { AlarmSchedule.inGrace(it, plannedTarget, now, grace, zone, holidays, suppressed[it.id]) }
        val extra = transient?.takeIf { plan ->
            plan.target.plus(grace).isAfter(now) && (suppressed[plan.alarmId] ?: Instant.EPOCH) < plan.target
        }
        return (upcoming + listOfNotNull(inGrace, extra)).minWithOrNull(compareBy({ it.target }, { it.alarmId }))
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
