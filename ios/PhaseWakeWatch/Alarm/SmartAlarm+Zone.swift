import Foundation
import PhaseWakeCore

extension SmartAlarm {
    // MARK: Zeitzone der Nacht

    /// Gemerkter Plan (`sessionZoneKey`): Zeitzone der Planung, Zielzeit und Wecker. Ältere Einträge (vor dem
    /// 04.11.2026) haben keinen Wecker.
    struct PlanZone {
        /// Zeitzone, in der der Plan berechnet wurde (`TimeZone.identifier`).
        let zone: String?
        /// Zielzeit des Plans.
        let target: Date?
        /// Kennung des Weckers.
        let alarmId: String?
    }

    /// Der gemerkte Plan, oder `nil`, wenn keiner gemerkt ist.
    static var savedPlanZone: PlanZone? {
        guard let saved = UserDefaults.standard.dictionary(forKey: sessionZoneKey) else { return nil }
        return PlanZone(zone: saved["zone"] as? String, target: (saved["target"] as? Double).map(Date.init(timeIntervalSince1970:)),
                        alarmId: saved["alarm"] as? String)
    }

    /// Merkt die aktuelle Zeitzone zu `plan` (beim Planen der Sitzung und beim Warten). Gehört der gemerkte Eintrag
    /// schon zur selben Nacht eines echten Weckers (gleicher Wecker, gleiche Zielzeit), bleibt er: Er nennt die Zone, in
    /// der die Nacht berechnet wurde, auch nach einer Reise (`keptNight`). Probewecker und Powernap: immer neu.
    func notePlanZone(_ plan: WakePlan) {
        if Transient.kindOf(plan.alarmId) == nil, let saved = Self.savedPlanZone, saved.alarmId == plan.alarmId,
           let target = saved.target, Self.sameTime(target, plan.target) { return }
        let entry: [String: Any] = ["zone": TimeZone.current.identifier, "target": plan.target.timeIntervalSince1970,
                                    "alarm": plan.alarmId]
        UserDefaults.standard.set(entry, forKey: Self.sessionZoneKey)
    }

    /// Begonnene Nacht über eine Reise: `night` (ohne Angabe die gemerkte) ist ein echter Wecker, der gemerkte Plan
    /// gehört zu ihr, die Ortszeit hat sich seit der Planung verschoben (`WatchRules.zoneShifted`), und sie ist in der
    /// Zone der Planung eine begonnene, unveränderte, nicht erledigte Nacht (`AlarmSchedule.begunNight`, bis `ringLimit`
    /// nach der Zielzeit wie `pendingNight`). Dann bleibt sie zur geplanten absoluten Zeit; sonst `nil` (wie bisher).
    func keptNight(_ night: WakePlan?, now: Date = Date()) -> WakePlan? {
        let model = WatchModel.shared
        guard let saved = Self.savedPlanZone, let alarmId = saved.alarmId, let target = saved.target,
              Transient.kindOf(alarmId) == nil, night.map({ $0.alarmId == alarmId && Self.sameTime($0.target, target) }) ?? true,
              WatchRules.zoneShifted(plannedZone: saved.zone, target: target, zone: .current),
              let zone = saved.zone.flatMap(TimeZone.init(identifier:)),
              let spec = model.alarms.first(where: { $0.id == alarmId }) else { return nil }
        var calendar = Calendar.gregorian(in: zone)
        calendar.locale = .autoupdatingCurrent
        return AlarmSchedule.begunNight(spec, plannedTarget: target, plannedCalendar: calendar, now: now, grace: Self.ringLimit,
                                        holidays: model.storage.holidays.dates, suppressedUntil: model.storage.doneUntil[alarmId])
    }

    /// Geplante oder wartende Nacht, die nach einer Reise statt `plan` bleibt (`keptNight`), sonst `nil`.
    func heldNight(for plan: WakePlan?) -> WakePlan? {
        switch phase {
        case .scheduled(let night), .waiting(let night):
            return night != plan && keptNight(night) != nil ? night : nil
        case .idle, .measuring, .ringing, .snoozed:
            return nil
        }
    }

    /// Rückfall einer über eine Reise behaltenen Nacht: zum festen Zeitpunkt (`LocalTrigger.fixed`), damit er nicht auf
    /// die neue Ortszeit rutscht und zweimal weckt. Wie bei einer ausstehenden Nacht nicht neu, wenn er schon fällig war
    /// (`WatchRules.fallbackDate`). Diagnose einmal je Nacht.
    func holdFallback(_ night: WakePlan) {
        if let at = WatchRules.fallbackDate(night, pending: true, now: Date(), delay: Self.fallbackDelay) {
            scheduleFallback(at: at, id: Self.fallbackId, fixed: true)
        }
        guard keptZoneNight.map({ !Self.sameTime($0, night.target) }) ?? true else { return }
        keptZoneNight = night.target
        DiagStore.shared.log(.scheduled, "zone change, keep night \(Format.time(night.target))")
    }

    /// Gleiche Zeit auf die Millisekunde (gespeicherte Zeiten sind gerundet).
    static func sameTime(_ lhs: Date, _ rhs: Date) -> Bool {
        Int64((lhs.timeIntervalSince1970 * 1000).rounded()) == Int64((rhs.timeIntervalSince1970 * 1000).rounded())
    }
}
