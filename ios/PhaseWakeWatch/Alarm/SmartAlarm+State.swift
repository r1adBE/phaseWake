import Foundation
import PhaseWakeCore

extension SmartAlarm {
    // MARK: Zustand sichern

    /// Klingel- und Schlummer-Zustand überstehen das Beenden der App durch das System.
    func save() {
        guard let nightPlan, let firstRing else { return }
        let night = NightRecord(alarmId: nightPlan.alarmId, windowStart: nightPlan.windowStart, target: nightPlan.target,
                                wokeAt: firstRing, reason: reason, snoozes: snoozes, epochs: epochs)
        var state: [String: Any] = ["night": Codec.encodeNight(night)]
        if case .snoozed(let until, _) = phase { state["snoozedUntil"] = until.timeIntervalSince1970 }
        UserDefaults.standard.set(state, forKey: Self.stateKey)
    }

    func clearSaved() {
        UserDefaults.standard.removeObject(forKey: Self.stateKey)
    }

    func restore() {
        guard let state = UserDefaults.standard.dictionary(forKey: Self.stateKey),
              let text = state["night"] as? String,
              let night = try? Codec.decodeNight(text) else { return }
        // Älter als zwei Stunden nach der Zielzeit: nicht mehr wecken, nur verwerfen.
        guard night.target.timeIntervalSinceNow > -2 * 3600 else {
            clearSaved()
            return
        }
        nightPlan = WakePlan(alarmId: night.alarmId, windowStart: night.windowStart, target: night.target)
        firstRing = night.wokeAt
        reason = night.reason
        snoozes = night.snoozes
        epochs = night.epochs
        let interrupted: Bool
        if let until = state["snoozedUntil"] as? Double {
            let date = Date(timeIntervalSince1970: until)
            plan = WakePlan(alarmId: night.alarmId, windowStart: date, target: date)
            phase = .snoozed(until: date, snoozesLeft: Snooze.maxCount - snoozes)
            // Die Schlummer-Sitzung hätte längst starten müssen.
            interrupted = date.addingTimeInterval(Self.startTolerance) < Date()
        } else {
            plan = nightPlan
            phase = .ringing(reason, snoozesLeft: Snooze.maxCount - snoozes)
            // Nach einem Neustart der App gibt es keine Sitzung mehr, also auch keine Vibration.
            interrupted = true
        }
        // Erst nach dem Aufbau von `shared` abschliessen (plant über WatchModel neu).
        if interrupted { Task { @MainActor in self.finishInterrupted() } }
    }
}
