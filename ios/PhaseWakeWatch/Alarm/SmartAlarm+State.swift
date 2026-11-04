import Foundation
import PhaseWakeCore

extension SmartAlarm {
    // MARK: Zustand sichern

    /// Datei des Klingel- und Schlummer-Zustands im von der Sicherung ausgenommenen Ordner: Er enthält die Nacht samt
    /// Bewegung und Puls je Epoche und gehört wie der Verlauf nicht in die Sicherung (früher in `UserDefaults`).
    private static var stateURL: URL { PrivateFiles.directory.appendingPathComponent("ring-state.v1") }

    /// Klingel- und Schlummer-Zustand überstehen das Beenden der App durch das System.
    func save() {
        guard let nightPlan, let firstRing else { return }
        let night = NightRecord(alarmId: nightPlan.alarmId, windowStart: nightPlan.windowStart, target: nightPlan.target,
                                wokeAt: firstRing, reason: reason, snoozes: snoozes, epochs: epochs)
        var state: [String: Any] = ["night": Codec.encodeNight(night)]
        if case .snoozed(let until, _) = phase { state["snoozedUntil"] = until.timeIntervalSince1970 }
        Self.writeState(state)
    }

    func clearSaved() {
        try? FileManager.default.removeItem(at: Self.stateURL)
        UserDefaults.standard.removeObject(forKey: Self.stateKey)
    }

    private static func writeState(_ state: [String: Any]) {
        do {
            let data = try PropertyListSerialization.data(fromPropertyList: state, format: .binary, options: 0)
            try data.write(to: stateURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            DiagStore.shared.log(.error, "ring state not saved \(type(of: error))")
        }
    }

    /// Gesicherter Zustand; einer aus `UserDefaults` (frühere Versionen) wandert einmalig in die Datei.
    private static func readState() -> [String: Any]? {
        if let legacy = UserDefaults.standard.dictionary(forKey: stateKey) {
            writeState(legacy)
            UserDefaults.standard.removeObject(forKey: stateKey)
            return legacy
        }
        guard let data = try? Data(contentsOf: stateURL) else { return nil }
        return (try? PropertyListSerialization.propertyList(from: data, options: [], format: nil)) as? [String: Any]
    }

    func restore() {
        guard let state = Self.readState(),
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
            phase = .snoozed(until: date, snoozesLeft: snoozesLeft)
            // Die Schlummer-Sitzung hätte längst starten müssen.
            interrupted = date.addingTimeInterval(Self.startTolerance) < Date()
        } else {
            plan = nightPlan
            phase = .ringing(reason, snoozesLeft: snoozesLeft)
            // Nach einem Neustart der App gibt es keine Sitzung mehr, also auch keine Vibration.
            interrupted = true
        }
        // Erst nach dem Aufbau von `shared` abschliessen (plant über WatchModel neu).
        if interrupted { Task { @MainActor in self.finishInterrupted() } }
    }
}
