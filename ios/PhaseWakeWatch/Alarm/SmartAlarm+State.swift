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
        // Einschlafzeit samt Beginn mit (Felder 11 und 12), damit sie nach einem Neustart mit der Nacht ans iPhone
        // geht.
        let night = NightRecord(alarmId: nightPlan.alarmId, windowStart: nightPlan.windowStart,
                                target: nightPlan.target, wokeAt: firstRing, reason: reason, snoozes: snoozes,
                                epochs: epochs,
                                bedAt: asleepAt == nil ? nil : onsetBedAt, asleepAt: asleepAt)
        var snoozedUntil: Date?
        if case .snoozed(let until, _) = phase { snoozedUntil = until }
        Self.writeState(NightRules.ringState(night, snoozedUntil: snoozedUntil))
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
        // Unlesbare Schlummer-Zeit gilt als fehlend (dann wie beim Klingeln, ``NightRules/ringNight(_:)``).
        guard let saved = NightRules.ringNight(Self.readState()) else { return }
        let night = saved.night
        let snoozed = saved.snoozedUntil
        nightPlan = WakePlan(alarmId: night.alarmId, windowStart: night.windowStart, target: night.target)
        firstRing = night.wokeAt
        reason = night.reason
        snoozes = night.snoozes
        epochs = night.epochs
        asleepAt = night.asleepAt
        onsetBedAt = night.bedAt
        if let date = snoozed {
            plan = WakePlan(alarmId: night.alarmId, windowStart: date, target: date)
            phase = .snoozed(until: date, snoozesLeft: snoozesLeft)
        } else {
            plan = nightPlan
            phase = .ringing(reason, snoozesLeft: snoozesLeft)
        }
        // Nach einem Neustart der App gibt es keine Sitzung mehr, also auch keine Vibration: abschliessen, ausser die
        // Schlummer-Sitzung startet noch. Älter als `ringStateMaxAge`: ohne Wecken (früher verworfen, mit
        // Nacht und Epochen). Erst nach dem Aufbau von `shared` (plant über WatchModel neu).
        switch NightRules.restored(target: night.target, snoozedUntil: snoozed, now: Date(),
                                   staleAfter: Self.ringStateMaxAge, tolerance: Self.startTolerance) {
        case .resume: break
        case .finishWaking: Task { @MainActor in self.finishInterrupted() }
        case .finishSilently: Task { @MainActor in self.finishSilently() }
        }
    }
}
