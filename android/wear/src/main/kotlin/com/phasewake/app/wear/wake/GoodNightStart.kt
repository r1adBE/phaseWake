package com.phasewake.app.wear.wake

import com.phasewake.app.wear.schedule.AutoOnset
import java.time.Instant

/**
 * Startentscheid von «Gute Nacht» ([startGoodNight]) als reine Regeln ohne Android (wie [OnsetRules]):
 * zuerst ob überhaupt gestartet wird ([gate]: laufende Nacht oder Messung, Sperre), nach dem Lesen des Weckers, ob und
 * wie gemessen wird ([decide]: Wecker, Weckfenster, Zeitgrenze, Akku). Der späte Start ([OnsetRules.lateStart]) folgt
 * danach, weil er das Schlafziel braucht. Fehlende Sensorrechte zeigt erst der Vordergrund-Dienst.
 */
internal object GoodNightStart {

    /** Ergebnis von [gate]. */
    enum class Gate {
        /** Eine Weck-Nacht läuft: nicht starten, Grund «night running» ins Protokoll. */
        NIGHT_RUNNING,

        /** «Gute Nacht» misst oder wartet schon (oder die Nacht läuft vor dem Entsperren): nichts tun. */
        BUSY,

        /** Vor dem ersten Entsperren: nicht starten, ohne Protokoll (nicht lesbar); die eingestellte Weckzeit gilt. */
        LOCKED,

        /** Starten versuchen (Vordergrund-Dienst mit Sensoren). */
        TRY,
    }

    /**
     * Vor dem Start: [nightRunning] (Weck-Nacht), [onsetRunning] (Messung läuft), [pending] («Gute Nacht» wartet),
     * [unlocked] (Speicher entsperrt).
     */
    fun gate(nightRunning: Boolean, onsetRunning: Boolean, pending: Boolean, unlocked: Boolean): Gate = when {
        nightRunning && unlocked -> Gate.NIGHT_RUNNING
        nightRunning || onsetRunning || pending -> Gate.BUSY
        !unlocked -> Gate.LOCKED
        else -> Gate.TRY
    }

    /** Ergebnis von [decide]. */
    sealed interface Decision {
        /** Nicht gestartet ([reason] ins Protokoll, z. B. «no alarm», «window begun»). */
        data class NotStarted(val reason: String) : Decision

        /** Zeitgrenze schon vorbei ([reason]: «give up» bzw. «give up (max duration)»): Nacht erledigt, Weckzeit gilt. */
        data class GiveUp(val reason: String, val latest: Instant) : Decision

        /** Automatisch mit zu wenig Akku (und nicht am Laden): diese Nacht nicht messen. */
        data class SkipBattery(val latest: Instant) : Decision

        /**
         * Messen bis zur Zeitgrenze: [latest] eingestellte Weckzeit der Nacht, [latestGiveUp] 15 Min davor, [maxEnd]
         * Beginn plus [OnsetMonitor.MAX_DURATION].
         */
        data class Measure(val latest: Instant, val latestGiveUp: Instant, val maxEnd: Instant) : Decision
    }

    /**
     * Nach dem Lesen: Wecker gefunden ([alarmFound]) mit Schlafdauer ([hasDuration]), automatisch ([automatic]), Beginn
     * von «Gute Nacht» [since], eingestellte Weckzeit der Nacht [latest] mit Beginn des Weckfensters [windowStart], Nacht
     * schon erledigt ([alreadyDone], nur automatisch), jetzt [now], Akku [battery] (Prozent, `null` unbekannt) und
     * [charging]. Reihenfolge wie bisher: Gründe gegen den Start, dann die Zeitgrenze, dann der Akku.
     */
    fun decide(
        alarmFound: Boolean,
        hasDuration: Boolean,
        automatic: Boolean,
        since: Instant,
        latest: Instant?,
        windowStart: Instant?,
        alreadyDone: Boolean,
        now: Instant,
        battery: Int?,
        charging: Boolean,
    ): Decision {
        val windowBegun = windowStart != null && !now.isBefore(windowStart)
        val notStarted = OnsetRules.notStartedReason(alarmFound, hasDuration, automatic, since, latest, windowBegun, alreadyDone)
        if (notStarted != null || !alarmFound || latest == null) return Decision.NotStarted(notStarted ?: "no alarm")
        val latestGiveUp = latest.minus(WakeService.ONSET_GIVE_UP)
        val maxEnd = since.plus(OnsetMonitor.MAX_DURATION)
        OnsetRules.giveUpReason(now, latestGiveUp, maxEnd)?.let { return Decision.GiveUp(it, latest) }
        if (automatic && AutoOnset.skipForBattery(battery, charging)) return Decision.SkipBattery(latest)
        return Decision.Measure(latest, latestGiveUp, maxEnd)
    }

    /** Grenze der Verlängerung bei spätem Einschlafen: [OnsetMonitor.MAX_EXTENDED] nach [since], nie über [windowStart]. */
    fun extendCap(since: Instant, windowStart: Instant?): Instant? = windowStart?.let { minOf(since.plus(OnsetMonitor.MAX_EXTENDED), it) }
}
