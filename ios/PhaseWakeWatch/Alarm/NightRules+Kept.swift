import Foundation
import PhaseWakeCore

/// Entscheide zu Zeitzone der Nacht, «stop» vom iPhone ohne Klingeln und gesichertem Klingel-Zustand (testbar, ohne
/// Sitzung und Dateien).
extension NightRules {
    // MARK: Zeitzone der Nacht

    /// Gemerkter Plan aus `saved` (`SmartAlarm.sessionZoneKey`): Zeitzone, Zielzeit und Wecker, je `nil`, wenn fehlend
    /// oder von falschem Typ. Ohne Eintrag `nil`.
    @MainActor
    static func planZone(_ saved: [String: Any]?) -> SmartAlarm.PlanZone? {
        guard let saved else { return nil }
        return SmartAlarm.PlanZone(zone: saved["zone"] as? String,
                                   target: (saved["target"] as? Double).map(Date.init(timeIntervalSince1970:)),
                                   alarmId: saved["alarm"] as? String)
    }

    /// Ob der gemerkte Plan `saved` beim Merken für `plan` bleibt (`SmartAlarm.notePlanZone`): echter Wecker, und der
    /// Eintrag gehört schon zur selben Nacht (gleicher Wecker, gleiche Zielzeit auf die Millisekunde) – er nennt die
    /// Zone, in der die Nacht berechnet wurde, auch nach einer Reise. `saved` wird nur für echte Wecker gelesen.
    @MainActor
    static func keepsPlanZone(_ saved: @autoclosure () -> SmartAlarm.PlanZone?, plan: WakePlan) -> Bool {
        guard Transient.kindOf(plan.alarmId) == nil, let saved = saved(), saved.alarmId == plan.alarmId,
              let target = saved.target else { return false }
        return SmartAlarm.sameTime(target, plan.target)
    }

    /// Nacht der Planung, die über eine Reise behalten werden kann: Wecker, Zielzeit und Zone der Planung.
    struct PlannedNight: Equatable {
        /// Kennung des Weckers.
        let alarmId: String
        /// Zielzeit der Planung (absolute Zeit).
        let target: Date
        /// Zeitzone, in der die Nacht berechnet wurde.
        let zone: TimeZone
    }

    /// Vorprüfung von `SmartAlarm.keptNight`: Der gemerkte Plan `saved` ist ein echter Wecker, gehört zu `night` (ohne
    /// Angabe zu jeder), die Ortszeit in `current` hat sich seit der Planung verschoben (`WatchRules.zoneShifted`), und
    /// die Zone der Planung ist lesbar. Sonst `nil` (wie bisher: neu planen).
    @MainActor
    static func plannedNight(_ saved: SmartAlarm.PlanZone?, night: WakePlan?, current: TimeZone) -> PlannedNight? {
        guard let saved, let alarmId = saved.alarmId, let target = saved.target,
              Transient.kindOf(alarmId) == nil,
              night.map({ $0.alarmId == alarmId && SmartAlarm.sameTime($0.target, target) }) ?? true,
              WatchRules.zoneShifted(plannedZone: saved.zone, target: target, zone: current),
              let zone = saved.zone.flatMap(TimeZone.init(identifier:)) else { return nil }
        return PlannedNight(alarmId: alarmId, target: target, zone: zone)
    }

    /// Geplante oder wartende Nacht im Zustand `phase`, die nach einer Reise statt `plan` bleibt
    /// (`SmartAlarm.heldNight`): eine andere als `plan`, die `kept` (``SmartAlarm/keptNight(_:now:)``) behält.
    @MainActor
    static func heldNight(_ phase: SmartAlarm.Phase, plan: WakePlan?, kept: (WakePlan) -> Bool) -> WakePlan? {
        switch phase {
        case .scheduled(let night), .waiting(let night):
            night != plan && kept(night) ? night : nil
        case .idle, .measuring, .ringing, .snoozed:
            nil
        }
    }

    // MARK: «stop» vom iPhone ohne Klingeln

    /// Die Nacht der Uhr, die ein «stop» vom iPhone beenden kann, während sie nicht klingelt
    /// (``phoneStoppedNight(_:alarmId:target:now:tolerance:)``, erster Kandidat): die wartende, geplante oder
    /// messende.
    @MainActor
    static func phoneStopCandidate(_ phase: SmartAlarm.Phase) -> WakePlan? {
        switch phase {
        case .waiting(let night), .scheduled(let night), .measuring(let night): night
        case .idle, .ringing, .snoozed: nil
        }
    }

    /// Wie ein «stop» vom iPhone die Nacht `night` beendet, während die Uhr nicht klingelt.
    enum PhoneStopEnd: Equatable {
        /// Sie misst gerade: wie ein Stopp beim Klingeln abschliessen (Epochen in den Verlauf, Weckzeit jetzt).
        case finishMeasuring
        /// Echter Wecker: erledigt, nicht nochmals planen.
        case markDone
        /// Probewecker oder Powernap: den vorübergehenden Plan beenden.
        case endTransient
    }

    /// Entscheid für die gestoppte Nacht `night` im Zustand `phase`.
    @MainActor
    static func phoneStopEnd(_ phase: SmartAlarm.Phase, night: WakePlan) -> PhoneStopEnd {
        if case .measuring(let running) = phase, running == night { return .finishMeasuring }
        return Transient.kindOf(night.alarmId) == nil ? .markDone : .endTransient
    }

    // MARK: Klingel-Zustand

    /// Zu sichernder Klingel- oder Schlummer-Zustand: die Nacht (`Codec`) und, beim Schlummern, dessen Ende.
    static func ringState(_ night: NightRecord, snoozedUntil: Date?) -> [String: Any] {
        var state: [String: Any] = ["night": Codec.encodeNight(night)]
        if let snoozedUntil { state["snoozedUntil"] = snoozedUntil.timeIntervalSince1970 }
        return state
    }

    /// Gelesener Zustand `state`: Nacht und Schlummer-Ende. Ohne Zustand oder mit unlesbarer Nacht `nil`; eine
    /// unlesbare Schlummer-Zeit (falscher Typ, nicht endlich) gilt als fehlend (dann wie beim Klingeln).
    static func ringNight(_ state: [String: Any]?) -> (night: NightRecord, snoozedUntil: Date?)? {
        guard let state, let text = state["night"] as? String, let night = try? Codec.decodeNight(text) else {
            return nil
        }
        let snoozed = (state["snoozedUntil"] as? Double).flatMap { $0.isFinite ? Date(timeIntervalSince1970: $0) : nil }
        return (night, snoozed)
    }
}
