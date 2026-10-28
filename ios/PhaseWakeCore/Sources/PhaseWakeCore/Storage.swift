import Foundation
#if canImport(os)
import os
#endif

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

    /// App-Gruppe der Uhr-App und ihrer Komplikation.
    public static let watchGroup = "group.com.phasewake.app"

    /// Speicher der Uhr: Wecker in der App-Gruppe, damit die Komplikation sie lesen kann.
    public static let watchShared = PhaseWakeStorage(defaults: UserDefaults(suiteName: watchGroup) ?? .standard)

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

    /// Pro Wecker: bis zu diesem Zeitpunkt ist seine Weck-Nacht erledigt; sie wird nicht noch einmal geplant.
    public var doneUntil: [String: Date] {
        lock.lock(); defer { lock.unlock() }
        let raw = defaults.dictionary(forKey: Self.doneKey) as? [String: Double] ?? [:]
        return raw.mapValues { Date(timeIntervalSince1970: $0) }
    }

    /// Merkt die Nacht des Weckers bis `until` als erledigt; ein späterer Wert bleibt, alte fallen weg.
    public func markDone(alarmId: String, until: Date, now: Date = Date()) {
        lock.lock(); defer { lock.unlock() }
        var raw = defaults.dictionary(forKey: Self.doneKey) as? [String: Double] ?? [:]
        raw[alarmId] = max(raw[alarmId] ?? 0, until.timeIntervalSince1970)
        let keepAfter = now.addingTimeInterval(-2 * 24 * 3600).timeIntervalSince1970
        defaults.set(raw.filter { $0.value > keepAfter }, forKey: Self.doneKey)
        defaults.removeObject(forKey: Self.legacyDoneKey)
    }

    private static let doneKey = "nightDoneUntilByAlarm"
    private static let legacyDoneKey = "nightDoneUntil"

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

    /// Fügt eine Nacht hinzu (gleicher Wecker und Fensterbeginn ersetzt) und kürzt auf 90 Nächte.
    /// Ein schon angegebenes Befinden bleibt erhalten, falls die Uhr dieselbe Nacht noch einmal schickt.
    /// Alte Dateien brauchen keine Umstellung: Jede Nacht trägt ihren Wecker schon.
    public func add(_ night: NightRecord) {
        lock.lock(); defer { lock.unlock() }
        let current = nightsLocked()
        let feeling = night.feeling ?? current.first { Self.sameNight($0, night) }?.feeling
        writeNightsLocked(NightHistory.trim(current.filter { !Self.sameNight($0, night) } + [night.with(feeling: feeling)]))
    }

    /// Speichert das Befinden (1–5) zur Nacht dieses Weckers mit diesem Fensterbeginn.
    public func setFeeling(_ feeling: Int, alarmId: String, windowStart: Date) {
        lock.lock(); defer { lock.unlock() }
        writeNightsLocked(nightsLocked().map { $0.alarmId == alarmId && $0.windowStart == windowStart ? $0.with(feeling: feeling) : $0 })
    }

    /// Dieselbe Nacht: gleicher Wecker und gleicher Fensterbeginn. Zwei Wecker mit exakt gleichem
    /// Fenster bleiben so getrennt (wie `NightRepository` auf Android).
    private static func sameNight(_ a: NightRecord, _ b: NightRecord) -> Bool {
        a.alarmId == b.alarmId && a.windowStart == b.windowStart
    }

    private func nightsLocked() -> [NightRecord] {
        guard let text = try? String(contentsOf: nightsURL, encoding: .utf8) else { return [] }
        return text.components(separatedBy: Self.separator)
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .compactMap { try? Codec.decodeNight($0.trimmingCharacters(in: CharacterSet(charactersIn: "\n")) + "\n") }
    }

    /// Schreibt atomar (temporäre Datei, dann ersetzen): Scheitert das Schreiben, etwa bei vollem
    /// Speicher, bleibt die bisherige Datei unverändert stehen. Der Fehler wird protokolliert.
    private func writeNightsLocked(_ nights: [NightRecord]) {
        let text = nights.map(Codec.encodeNight).joined(separator: Self.separator)
        do {
            try Data(text.utf8).write(to: nightsURL, options: .atomic)
        } catch {
            Self.log("Verlauf nicht gespeichert, bisheriger Stand bleibt: \(error)")
        }
    }

    public func clearNights() {
        lock.lock(); defer { lock.unlock() }
        do {
            try FileManager.default.removeItem(at: nightsURL)
        } catch CocoaError.fileNoSuchFile {
            // Kein Verlauf vorhanden.
        } catch {
            Self.log("Verlauf nicht gelöscht: \(error)")
        }
    }

    private static func log(_ message: String) {
        #if canImport(os)
        logger.error("\(message, privacy: .public)")
        #endif
    }

    #if canImport(os)
    private static let logger = Logger(subsystem: "com.phasewake.app", category: "Storage")
    #endif
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
    /// Uhr → iPhone bei «ring» und «stop»: Zielzeit der Nacht und Sendezeit (ms seit 1970), bei «ring»
    /// auch die verbleibenden Schlummer-Runden. iPhone → Uhr: nur die Sendezeit. Ältere Versionen schicken nichts davon.
    public static let target = "target"
    public static let sentAt = "sentAt"
    public static let snoozesLeft = "snoozesLeft"
    /// Höchstalter eines «ring» (und eines «stop»/«snooze» vom iPhone) in Millisekunden; ein später zugestelltes wird verworfen.
    public static let maxRingAgeMs: Int64 = 2 * 60_000
    /// Uhr → iPhone im Anwendungskontext: Zielzeit (ms), für die die Uhr scharf ist; 0 = nicht scharf.
    public static let armedTarget = "armedTarget"
    /// Uhr → iPhone im Anwendungskontext: Akkustand der Uhr in Prozent und Zeitpunkt der Messung (ms).
    /// Ältere Uhr-Versionen schicken beides nicht.
    public static let battery = "battery"
    public static let batteryAt = "batteryAt"
    /// Älter als das gilt der gemeldete Akkustand als unbekannt (wie `WearSync.watchBattery()` auf Android).
    public static let maxBatteryAgeMs: Int64 = 6 * 3600_000
}
