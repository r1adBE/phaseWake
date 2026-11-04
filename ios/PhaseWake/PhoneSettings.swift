import Observation
import PhaseWakeCore
import SwiftUI

extension SettingsFile {
    /// Wecker und Einstellungen des iPhones als Datei «settings.v1» im von der Sicherung ausgenommenen Ordner
    /// (`PrivateFiles`), nicht in `UserDefaults`, das mit der iCloud-Sicherung ginge. Beim ersten Zugriff übernimmt
    /// sie die Werte früherer Versionen einmalig aus `UserDefaults` (``movedKeys``) und löscht sie dort.
    static let phone: SettingsFile = {
        let file = SettingsFile(url: PrivateFiles.directory.appendingPathComponent(SettingsFile.fileName))
        file.adopt(SettingsFile.movedKeys, from: .standard)
        return file
    }()

    /// Name der Datei im Ordner `PrivateFiles`.
    static let fileName = "settings.v1"

    /// Schlüssel, die in früheren Versionen in `UserDefaults.standard` lagen. In `UserDefaults` bleiben nur technische
    /// Merker ohne persönliche Angaben: `onboardingDone`, `healthRequested`, `diagUnlocked`, `diagWatchSeen`.
    static let movedKeys = [
        // Wecker, erledigte Nächte, Feiertage, Schlafziel (`PhaseWakeStorage`)
        "alarms", "alarmsUpdatedAt", "nightDoneUntilByAlarm", "nightDoneUntil", "holidays", "sleepGoalMinutes",
        // Einstellungen (`PhoneModel`, `HolidayStore`, `BackupIO`, Darstellung, Einschlafklänge)
        "bedtimeReminder", "weeklyReview", "soundRecDefault", "soundRecNight", "historyClearedAt",
        "holidayCountry", "holidayRegion", "themeMode", "backupNextAlarmNotice", "backupAutoOnset",
        "soundChoice", "soundTimerMinutes",
        // Zweitwecker (`BackupAlarm`): gestellte Alarme mit Zielzeiten, Schlummern, Zeitzone der Planung
        "backupSuppressedByAlarm", "backupSuppressedUntil", "backupRingingId", "backupRingSnoozesLeft",
        "backupOneShotTargets", "backupSnoozeSafetyId", "backupSnoozedAt", "backupSnoozeId", "backupSnoozeTone",
        "backupTransientId", "backupPlanned", "backupAlarmIds", "backupRingNights", "backupPlannedZone",
        "backupWatchPresent",
        // Probewecker und Powernap (`PhoneTransient`)
        "transientTest", "transientTestRun", "transientNap", "transientTestRingIds",
    ]
}

/// Meldet Änderungen an ``SettingsFile/phone`` an die Ansichten mit ``PrivateSetting`` (etwa nach dem Wiederherstellen).
@Observable
final class PrivateSettingChanges: @unchecked Sendable {
    static let shared = PrivateSettingChanges()
    /// Zählt jede Änderung; Ansichten lesen ihn mit und zeichnen dann neu.
    private(set) var revision = 0

    /// Nach einer Änderung an der Datei: Ansichten neu zeichnen (auf dem Main-Thread aufrufen).
    func bump() { revision &+= 1 }
}

/// Wie `@AppStorage`, aber in ``SettingsFile/phone`` (nicht in der iCloud-Sicherung). Für Text, Zahl und
/// Aufzählungen mit Text als Rohwert; alle Ansichten mit demselben Schlüssel bleiben gleich.
@propertyWrapper
struct PrivateSetting<Value>: DynamicProperty {
    private let read: () -> Value
    private let write: (Value) -> Void

    /// Gespeicherter Wert, sonst der Standard.
    var wrappedValue: Value {
        get {
            _ = PrivateSettingChanges.shared.revision
            return read()
        }
        nonmutating set {
            write(newValue)
            PrivateSettingChanges.shared.bump()
        }
    }

    /// Bindung für Auswahl und Schalter.
    var projectedValue: Binding<Value> {
        Binding(get: { wrappedValue }, set: { wrappedValue = $0 })
    }

    /// Text unter `key`.
    init(wrappedValue defaultValue: Value, _ key: String) where Value == String {
        read = { SettingsFile.phone.string(forKey: key) ?? defaultValue }
        write = { SettingsFile.phone.set($0, forKey: key) }
    }

    /// Ganze Zahl unter `key`.
    init(wrappedValue defaultValue: Value, _ key: String) where Value == Int {
        read = { SettingsFile.phone.object(forKey: key) == nil ? defaultValue : SettingsFile.phone.integer(forKey: key) }
        write = { SettingsFile.phone.set($0, forKey: key) }
    }

    /// Aufzählung mit Text als Rohwert unter `key`; Unbekanntes gilt als Standard.
    init(wrappedValue defaultValue: Value, _ key: String) where Value: RawRepresentable, Value.RawValue == String {
        read = { SettingsFile.phone.string(forKey: key).flatMap(Value.init(rawValue:)) ?? defaultValue }
        write = { SettingsFile.phone.set($0.rawValue, forKey: key) }
    }
}
