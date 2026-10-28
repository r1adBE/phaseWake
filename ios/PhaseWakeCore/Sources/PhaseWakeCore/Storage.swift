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
        return snapshotLocked()
    }

    /// Ändert die Wecker und setzt einen streng steigenden Zeitstempel. Lesen und Schreiben unter
    /// einer Sperre: Zwei gleichzeitige Änderungen gehen nicht verloren. - Returns: neuer Stand.
    @discardableResult
    public func mutate(_ change: ([AlarmSpec]) -> [AlarmSpec], now: Date = Date()) -> AlarmSnapshot {
        lock.lock(); defer { lock.unlock() }
        let current = snapshotLocked()
        let updated = change(current.alarms).sorted { ($0.hour, $0.minute, $0.id) < ($1.hour, $1.minute, $1.id) }
        let stamp = max(Int64(now.timeIntervalSince1970 * 1000), current.updatedAt + 1)
        let result = AlarmSnapshot(alarms: updated, updatedAt: stamp)
        writeLocked(result)
        return result
    }

    /// Übernimmt den Stand der Gegenseite, falls neuer. - Returns: ob übernommen.
    @discardableResult
    public func applyRemote(_ remote: AlarmSnapshot) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard remote.updatedAt > snapshotLocked().updatedAt else { return false }
        writeLocked(remote)
        return true
    }

    /// Bis zu diesem Zeitpunkt ist eine Weck-Nacht erledigt; sie wird nicht noch einmal geplant.
    public var doneUntil: Date {
        get {
            lock.lock(); defer { lock.unlock() }
            return Date(timeIntervalSince1970: defaults.double(forKey: Self.doneKey))
        }
        set {
            lock.lock(); defer { lock.unlock() }
            defaults.set(newValue.timeIntervalSince1970, forKey: Self.doneKey)
        }
    }

    private static let doneKey = "nightDoneUntil"

    private func snapshotLocked() -> AlarmSnapshot {
        let text = defaults.string(forKey: Self.alarmsKey) ?? ""
        let alarms = (try? Codec.decodeAlarms(text)) ?? []
        // Als NSNumber lesen: Millisekunden passen nicht in Int auf 32-Bit-Uhren (arm64_32).
        let updatedAt = (defaults.object(forKey: Self.updatedKey) as? NSNumber)?.int64Value ?? 0
        return AlarmSnapshot(alarms: alarms, updatedAt: updatedAt)
    }

    private func writeLocked(_ snapshot: AlarmSnapshot) {
        defaults.set(Codec.encodeAlarms(snapshot.alarms), forKey: Self.alarmsKey)
        // Nie über Int: auf Apple Watch Series 4–8, SE und Ultra 1 ist Int 32 Bit (Absturz).
        defaults.set(NSNumber(value: snapshot.updatedAt), forKey: Self.updatedKey)
    }

    // MARK: Nächte

    public func nights() -> [NightRecord] {
        lock.lock(); defer { lock.unlock() }
        return nightsLocked()
    }

    /// Fügt eine Nacht hinzu (gleicher Fensterbeginn ersetzt) und kürzt auf 90 Nächte.
    /// Ein schon angegebenes Befinden bleibt erhalten, falls die Uhr dieselbe Nacht noch einmal schickt.
    public func add(_ night: NightRecord) {
        lock.lock(); defer { lock.unlock() }
        let current = nightsLocked()
        let feeling = night.feeling ?? current.first { $0.windowStart == night.windowStart }?.feeling
        writeNightsLocked(NightHistory.trim(current.filter { $0.windowStart != night.windowStart } + [night.with(feeling: feeling)]))
    }

    /// Speichert das Befinden (1–5) zur Nacht mit diesem Fensterbeginn.
    public func setFeeling(_ feeling: Int, windowStart: Date) {
        lock.lock(); defer { lock.unlock() }
        writeNightsLocked(nightsLocked().map { $0.windowStart == windowStart ? $0.with(feeling: feeling) : $0 })
    }

    private func nightsLocked() -> [NightRecord] {
        guard let text = try? String(contentsOf: nightsURL, encoding: .utf8) else { return [] }
        return text.components(separatedBy: Self.separator)
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .compactMap { try? Codec.decodeNight($0.trimmingCharacters(in: CharacterSet(charactersIn: "\n")) + "\n") }
    }

    private func writeNightsLocked(_ nights: [NightRecord]) {
        let text = nights.map(Codec.encodeNight).joined(separator: Self.separator)
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
    /// Uhr → iPhone im Anwendungskontext: Zielzeit (ms), für die die Uhr scharf ist; 0 = nicht scharf.
    public static let armedTarget = "armedTarget"
}
