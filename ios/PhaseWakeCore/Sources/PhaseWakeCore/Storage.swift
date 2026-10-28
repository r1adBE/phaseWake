import Foundation

/// Wecker samt Zeitpunkt der letzten Änderung (für den Abgleich iPhone ↔ Uhr; letzte Änderung gewinnt).
public struct AlarmSnapshot: Equatable, Sendable {
    public let alarms: [AlarmSpec]
    public let updatedAt: Int64

    public init(alarms: [AlarmSpec], updatedAt: Int64) {
        self.alarms = alarms
        self.updatedAt = updatedAt
    }
}

/// Lokaler Speicher für Wecker und Nächte, auf iPhone und Uhr gleich. Gleiches Textformat wie Android.
public final class PhaseWakeStorage: @unchecked Sendable {
    private let defaults: UserDefaults
    private let nightsURL: URL
    private let lock = NSLock()

    private static let alarmsKey = "alarms"
    private static let updatedKey = "alarmsUpdatedAt"
    private static let separator = "\n%%\n"

    public init(defaults: UserDefaults = .standard, directory: URL? = nil) {
        self.defaults = defaults
        let dir = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        nightsURL = dir.appendingPathComponent("nights.v1")
    }

    // MARK: Wecker

    public func snapshot() -> AlarmSnapshot {
        lock.lock(); defer { lock.unlock() }
        let text = defaults.string(forKey: Self.alarmsKey) ?? ""
        let alarms = (try? Codec.decodeAlarms(text)) ?? []
        return AlarmSnapshot(alarms: alarms, updatedAt: Int64(defaults.integer(forKey: Self.updatedKey)))
    }

    /// Ändert die Wecker und setzt einen streng steigenden Zeitstempel. - Returns: neuer Stand.
    @discardableResult
    public func mutate(_ change: ([AlarmSpec]) -> [AlarmSpec], now: Date = Date()) -> AlarmSnapshot {
        let current = snapshot()
        let updated = change(current.alarms).sorted { ($0.hour, $0.minute, $0.id) < ($1.hour, $1.minute, $1.id) }
        let stamp = max(Int64(now.timeIntervalSince1970 * 1000), current.updatedAt + 1)
        write(AlarmSnapshot(alarms: updated, updatedAt: stamp))
        return AlarmSnapshot(alarms: updated, updatedAt: stamp)
    }

    /// Übernimmt den Stand der Gegenseite, falls neuer. - Returns: ob übernommen.
    @discardableResult
    public func applyRemote(_ remote: AlarmSnapshot) -> Bool {
        guard remote.updatedAt > snapshot().updatedAt else { return false }
        write(remote)
        return true
    }

    private func write(_ snapshot: AlarmSnapshot) {
        lock.lock(); defer { lock.unlock() }
        defaults.set(Codec.encodeAlarms(snapshot.alarms), forKey: Self.alarmsKey)
        defaults.set(Int(snapshot.updatedAt), forKey: Self.updatedKey)
    }

    // MARK: Nächte

    public func nights() -> [NightRecord] {
        lock.lock(); defer { lock.unlock() }
        guard let text = try? String(contentsOf: nightsURL, encoding: .utf8) else { return [] }
        return text.components(separatedBy: Self.separator)
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .compactMap { try? Codec.decodeNight($0.trimmingCharacters(in: CharacterSet(charactersIn: "\n")) + "\n") }
    }

    /// Fügt eine Nacht hinzu (gleicher Fensterbeginn ersetzt) und kürzt auf 90 Nächte.
    public func add(_ night: NightRecord) {
        let updated = NightHistory.trim(nights().filter { $0.windowStart != night.windowStart } + [night])
        lock.lock(); defer { lock.unlock() }
        let text = updated.map(Codec.encodeNight).joined(separator: Self.separator)
        try? text.write(to: nightsURL, atomically: true, encoding: .utf8)
    }

    public func clearNights() {
        lock.lock(); defer { lock.unlock() }
        try? FileManager.default.removeItem(at: nightsURL)
    }
}

/// Schlüssel der Verbindung iPhone ↔ Uhr (WatchConnectivity).
public enum SyncKeys {
    public static let alarms = "alarms"
    public static let updatedAt = "updatedAt"
    public static let night = "night"
    public static let command = "command"
    public static let ring = "ring"
    public static let stop = "stop"
    public static let snooze = "snooze"
    public static let clearHistory = "clearHistory"
}
