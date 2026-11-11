import Foundation
import PhaseWakeCore

extension SyncKeys {
    /// Uhr → iPhone bei «stop» nach dem Schlummern: Ende des Schlummerns in ms seit 1970 (wie `snoozeUntil` in
    /// `RingMessage` auf Android). Das iPhone stellt dann eine eigene Sicherung 90 s danach, falls die Uhr nicht wieder
    /// klingelt. Fehlt bei einem endgültigen Stopp und bei älteren Uhr-Versionen: Dann ist die Nacht beendet.
    static let snoozeUntil = "snoozeUntil"
    /// iPhone → Uhr bei einem nachgereichten «Nacht aufzeichnen» (``RecordRetry``): Zeit des Tippens in ms seit 1970.
    static let retryTapAt = "retryTapAt"
}

/// Zeiten in Nachrichten zwischen iPhone und Uhr (ms seit 1970) ohne Abbruch lesen (Vollaudit AP2-8): Ein Wert
/// ausserhalb von ``TimeBounds`` (kaputt oder fremd, z. B. `Int64.max`) gilt als fehlend, und das Alter einer Nachricht
/// rechnet nie über `Int64` hinaus.
enum SyncTime {
    /// Zeitpunkt aus `value` (`Int64`, ms), wenn plausibel; sonst `nil`.
    static func date(_ value: Any?) -> Date? {
        TimeBounds.checked(value as? Int64).map { Date(timeIntervalSince1970: Double($0) / 1000) }
    }

    /// Alter einer Nachricht mit Sendezeit `sentAt` (ms) um `now` in ms; `nil`, wenn die Sendezeit fehlt oder nicht
    /// plausibel ist.
    static func ageMs(_ sentAt: Int64?, now: Date) -> Int64? {
        guard let sent = TimeBounds.checked(sentAt) else { return nil }
        return TimeBounds.millis(now) - sent
    }

    /// Ob eine Nachricht mit Sendezeit `sentAt` um `now` zu alt ist (über ``SyncKeys/maxRingAgeMs``). Fehlt die Zeit
    /// oder ist sie nicht plausibel, gilt sie als zu alt.
    static func stale(_ sentAt: Int64?, now: Date) -> Bool {
        guard let age = ageMs(sentAt, now: now) else { return true }
        return age > SyncKeys.maxRingAgeMs
    }
}

/// Zu welcher Nacht eine Meldung oder ein Alarm gehört: Kennung des Weckers und Zielzeit der Nacht.
/// Gleiche Regel wie `RingMessage.sameNight` auf Android: Fehlende Angaben (ältere Versionen) widersprechen nicht.
struct RingNight: Codable, Equatable, Sendable {
    /// Kennung des Weckers (bzw. des Probeweckers oder Powernaps); `nil` = unbekannt.
    let alarmId: String?
    /// Zielzeit der Nacht in ms seit 1970; `nil` = unbekannt.
    let targetMs: Int64?

    /// Erzeugt die Angabe aus Kennung und Zielzeit (auf ganze ms gerundet wie in den Nachrichten).
    init(alarmId: String?, target: Date?) {
        self.alarmId = alarmId.flatMap { $0.isEmpty ? nil : $0 }
        targetMs = target.map(TimeBounds.millis)
    }

    /// Ob beide Angaben zur selben Nacht gehören; eine fehlende Kennung oder Zielzeit widerspricht nie.
    func same(_ other: RingNight) -> Bool {
        (alarmId == nil || other.alarmId == nil || alarmId == other.alarmId)
            && (targetMs == nil || other.targetMs == nil || targetMs == other.targetMs)
    }
}

/// Ordner für Verlauf, Notizen und Diagnose: von der iCloud- und Geräte-Sicherung ausgenommen (wie
/// `allowBackup="false"` auf Android). Das Flag sitzt auf dem Ordner, nicht auf den Dateien: Atomares Schreiben
/// ersetzt die Datei und verlöre ein Flag auf der Datei.
enum PrivateFiles {
    /// Der Ordner «Application Support/Private», angelegt und von der Sicherung ausgenommen.
    static let directory: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var dir = base.appendingPathComponent("Private", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dir.setResourceValues(values)
        return dir
    }()

    /// Holt Dateien, die frühere Versionen direkt in Application Support abgelegt haben, in den Ordner (einmalig).
    /// Liegt dort schon eine Datei gleichen Namens, gilt sie, und die alte wird entfernt.
    /// - Returns: der Ordner (``directory``).
    @discardableResult
    static func adopt(_ names: [String]) -> URL {
        let manager = FileManager.default
        let base = directory.deletingLastPathComponent()
        for name in names {
            let old = base.appendingPathComponent(name)
            guard manager.fileExists(atPath: old.path) else { continue }
            let new = directory.appendingPathComponent(name)
            if manager.fileExists(atPath: new.path) {
                try? manager.removeItem(at: old)
            } else {
                // Scheitert das Verschieben, bleibt die alte Datei liegen und wird beim nächsten Start erneut versucht.
                try? manager.moveItem(at: old, to: new)
            }
        }
        return directory
    }
}
