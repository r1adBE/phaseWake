import Foundation
import PhaseWakeCore
import WatchKit

/// Entscheide beim Messen der Apple Watch ohne Sitzung, Sensoren und Mitteilungen (testbar): passende Nacht beim Start
/// der Sitzung, ungemessene Epochen, frühe Momente (kurze Nacht, Einschlafzeit noch in Rechnung), Grund zur Zielzeit,
/// geänderter Plan während der Messung, Abbruch und Ende der Messung. Gegenstück zu `WakeRules` und `onEpochs`
/// (Kotlin).
enum MeasuringRules {
    // MARK: Start der Sitzung

    /// Start der Sitzung für `plan`: am Fensterbeginn, aber höchstens `lead` vor der Zielzeit
    /// (``SmartAlarm/sessionStart(_:)``), weil die Sitzung nach 30 Minuten endet.
    static func sessionStart(_ plan: WakePlan, lead: TimeInterval) -> Date {
        max(plan.windowStart, plan.target.addingTimeInterval(-lead))
    }

    /// Die Nacht, für die eine eben gestartete Sitzung misst (`SmartAlarm.startNight`): ihr Plan `own`, wenn er der
    /// nächste Plan `next` oder der echte Wecker `real` ist (dessen Sitzung ein vorübergehender Plan bei geschlossener
    /// App nicht verdrängt hat); ohne eigenen Plan (nach einem Neustart der App) der nächste. Sonst `nil` (verwaist).
    static func matchingNight(own: WakePlan?, next: WakePlan?, real: WakePlan?) -> WakePlan? {
        guard let own else { return next }
        return [next, real].compactMap { $0 }.contains(own) ? own : nil
    }

    /// Ob `night` nachgeholt wird: der verpasste Wecker `missed` oder die ausstehende Nacht `pending` (Zielzeit schon
    /// vorbei). Dann klingelt sie sofort.
    static func isCatchUp(_ night: WakePlan, missed: WakePlan?, pending: WakePlan?) -> Bool {
        night == missed || night == pending
    }

    /// Ob die Sitzung für `night` jetzt misst: frühestens `tolerance` vor ihrem Sitzungsstart `sessionStart` und vor
    /// der Zielzeit plus `delay`; eine nachgeholte Nacht (`catchUp`) auch danach. Sonst ist die Sitzung verwaist.
    static func measures(_ night: WakePlan, catchUp: Bool, now: Date, sessionStart: Date, tolerance: TimeInterval,
                         delay: TimeInterval) -> Bool {
        now >= sessionStart.addingTimeInterval(-tolerance)
            && (catchUp || now < night.target.addingTimeInterval(delay))
    }

    /// Epochen ab Fensterbeginn `windowStart`, die beim Start der Messung (`now`) schon vorbei sind: ganze Epochen, nie
    /// negativ. Sie bleiben leer und zählen nicht fürs Aufwärmen – wie `WakeRules.unmeasuredEpochs` (Kotlin).
    static func unmeasuredEpochs(windowStart: Date, now: Date) -> Int {
        max(0, Int(now.timeIntervalSince(windowStart)) / DetectorConfig.epochSeconds)
    }

    /// Wecker «Genau» (`WakePlan.exact` der Nacht `night` bzw. `AlarmSpec.isExact` von `spec`): misst, weckt aber erst
    /// zur Zielzeit.
    static func isExact(_ night: WakePlan?, spec: AlarmSpec?) -> Bool {
        night?.exact == true || spec?.isExact == true
    }

    // MARK: Früher Moment

    /// Was mit einem frühen Moment der Erkennung geschieht.
    enum Moment: Equatable {
        /// Jetzt wecken mit diesem Grund; `duringBlock`: trotz Sperre der kurzen Nacht, weil die Person wach ist.
        case ring(WakeReason, duringBlock: Bool)
        /// Übergangen (kurze Nacht): weiter erkennen, zur Zielzeit mit `shortNight`.
        case blocked
        /// Nichts tun: «Genau» weckt erst zur Zielzeit.
        case ignore
    }

    /// Früher Moment `reason`, Einschlafzeit bekannt (`SmartAlarm.feed`): Gilt die Sperre der kurzen Nacht
    /// (`blocked`), weckt er nur, wenn die Person wach ist (`awake`, ``WakeDetector/onBlocked()``, nur dann gefragt –
    /// wie `onEpochs` in Kotlin); sonst mit seinem Grund.
    static func early(_ reason: WakeReason, blocked: Bool, awake: () -> WakeReason?) -> Moment {
        guard blocked else { return .ring(reason, duringBlock: false) }
        guard let woke = awake() else { return .blocked }
        return .ring(woke, duringBlock: true)
    }

    /// Früher Moment `reason`, während die Einschlafzeit noch gerechnet wird (`heldWake`): Er wird gemerkt, wenn noch
    /// keiner gemerkt ist (`held`) oder die Person jetzt wach ist (`awake`) und beim gemerkten nicht war (`heldAwake`)
    /// – wach geht vor. - Returns: der neu gemerkte Moment, oder `nil`, wenn der bisherige bleibt.
    static func hold(_ reason: WakeReason, awake: Bool, held: WakeReason?,
                     heldAwake: Bool) -> (reason: WakeReason, awake: Bool)? {
        guard held == nil || (awake && !heldAwake) else { return nil }
        return (reason, awake)
    }

    /// Gemerkter Moment `held` (Person dabei wach: `awake`), sobald die Einschlafzeit da ist (`SmartAlarm.onsetDone`):
    /// «Genau» (`exact`) weckt nicht; gilt die Sperre der kurzen Nacht (`blocked`, nur ohne «Genau» gefragt), weckt er
    /// nur als `awake`; sonst mit seinem Grund.
    static func heldMoment(_ held: WakeReason, awake: Bool, exact: Bool, blocked: @autoclosure () -> Bool) -> Moment {
        if exact { return .ignore }
        guard blocked() else { return .ring(held, duringBlock: false) }
        return awake ? .ring(.awake, duringBlock: true) : .blocked
    }

    /// Grund der Sperre fürs Diagnose-Protokoll (wie Android): ohne Einschlafzeit (`onsetKnown` falsch) «unknown».
    static func blockedWhy(onsetKnown: Bool) -> String {
        onsetKnown ? "short night" : "short night unknown"
    }

    /// Zeile fürs Diagnose-Protokoll zu einem Moment `reason`, der weckt; `duringBlock` mit Grund der Sperre `why`.
    static func momentLine(_ reason: WakeReason, duringBlock: Bool, why: String) -> String {
        duringBlock ? "\(reason.rawValue) during \(why)" : reason.rawValue
    }

    // MARK: Einschlafzeit

    /// Stand der Einschlafzeit der Nacht.
    struct Onset: Equatable {
        /// Einschlafzeit; `nil` = unbekannt.
        var asleepAt: Date?
        /// Beginn der Einschlafdauer.
        var bedAt: Date?
        /// Rekorder lief, Rechnung ohne Ergebnis (fail-closed, `ShortNight`).
        var measured: Bool
    }

    /// Ob ein Ergebnis der Rechnung `run` noch gilt: Sie läuft noch (`pending`), ist die letzte (`latest`), und die
    /// Nacht, für die sie begann, misst noch (`measuring`). Ein überholtes oder zu spätes Ergebnis gilt nicht.
    static func onsetCounts(pending: Bool, run: Int, latest: Int, measuring: Bool) -> Bool {
        pending && run == latest && measuring
    }

    /// Stand nach dem Ergebnis `result` ausgehend von `current`: eingeschlafen (Zeit und Beginn), unbekannt (bleibt)
    /// oder gescheitert (fail-closed).
    static func onset(_ result: WatchOnset.Result, current: Onset) -> Onset {
        var next = current
        switch result {
        case .asleep(let at, let bed):
            next.asleepAt = at
            next.bedAt = bed
        case .notMeasured: break
        case .failed: next.measured = true
        }
        return next
    }

    // MARK: Zielzeit und Ende

    /// Grund beim Wecken zur Zielzeit (Zeitgeber oder Ende der Sitzung): «Genau» (`exact`), übergangene kurze Nacht
    /// (`earlyBlocked`) oder, was die Erkennung zum Schluss meldet (`detected`, nur dann gefragt) – wie
    /// `WakeRules.deadlineReason` (Kotlin).
    static func targetReason(exact: Bool, earlyBlocked: Bool, detected: () -> WakeReason) -> WakeReason {
        if exact { return .exact }
        if earlyBlocked { return .shortNight }
        return detected()
    }

    /// Was eine laufende Messung tut, wenn `schedule` einen Plan bringt.
    enum Change: Equatable {
        /// Derselbe Plan: weiter messen.
        case same
        /// Vorübergehender Plan neben der Messung eines echten Weckers: Sie misst weiter, der Plan weckt per
        /// Mitteilung.
        case transientByNotification(WakePlan)
        /// Nach einer Reise begonnene Nacht (`keptNight`): weiter messen, Rückfall fest (`holdFallback`).
        case keptOverZone
        /// Sonst (Wecker geändert, aus- oder abgeschaltet): Messung ohne Aufzeichnung beenden, `schedule` plant weiter.
        case cancel
    }

    /// Entscheid für die Messung `running`, wenn `schedule` `plan` bringt (`SmartAlarm.keepsNight`). Ein
    /// vorübergehender Plan bricht nie die Messung eines echten Weckers ab. `kept` (nach einer Reise behalten) wird nur
    /// gefragt, wenn es darauf ankommt.
    static func change(running: WakePlan, plan: WakePlan?, kept: () -> Bool) -> Change {
        guard running != plan else { return .same }
        if let plan, Transient.kindOf(plan.alarmId) != nil, Transient.kindOf(running.alarmId) == nil {
            return .transientByNotification(plan)
        }
        return kept() ? .keptOverZone : .cancel
    }

    /// Nacht, die nach einem Abbruch der Messung vor der Zielzeit auf der Uhr bleibt (bis die Nacht regulär endet und
    /// sie ersetzt): die Epochen `epochs` der Nacht `night`, Weckzeit gleich Zielzeit, Grund `deadline`. Ohne Nacht,
    /// ohne Epochen und für Probewecker und Powernap keine.
    static func interruptedNight(_ night: WakePlan?, epochs: [Epoch]) -> NightRecord? {
        guard let night, !epochs.isEmpty, Transient.kindOf(night.alarmId) == nil else { return nil }
        return NightRecord(alarmId: night.alarmId, windowStart: night.windowStart, target: night.target,
                           wokeAt: night.target, reason: .deadline, snoozes: 0, epochs: epochs)
    }

    /// Ob das Ende einer Messung ohne Wecken (`SmartAlarm.cancelMeasuring`) kurz vibriert: nur bei laufender Sitzung
    /// (`state`) und offener App (`active`, nur dann gefragt); im Hintergrund zeigte `notifyUser` den Weckhinweis des
    /// Systems mitten in der Nacht.
    static func confirmsCancel(_ state: WKExtendedRuntimeSessionState?, active: @autoclosure () -> Bool) -> Bool {
        state == .running && active()
    }

    /// Zeile zum Ende der Messung (`SmartAlarm.logMeasureEnd`): Epochen gesamt und davon mit Puls, Kennzahlen der
    /// Schwellen `summary` und Zusatz `note`.
    static func measureEndLine(_ epochs: [Epoch], summary: String, note: String) -> String {
        let withHeartRate = epochs.filter { $0.heartRate != nil }.count
        return "\(epochs.count) epochs, hr epochs \(withHeartRate)/\(epochs.count), \(summary)\(note)"
    }
}
