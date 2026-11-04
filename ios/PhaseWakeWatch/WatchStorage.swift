import Foundation
import PhaseWakeCore

extension PhaseWakeStorage {
    /// Speicher der Uhr-App: Wecker, erledigte Nächte, Feiertage und Schlafziel in ``SettingsFile/watchAlarms`` (die
    /// Komplikation liest dieselbe Datei über `watchShared`), Nächte im von der Sicherung ausgenommenen Ordner
    /// (`PrivateFiles`) wie auf dem iPhone. Die Sicherung der Uhr hängt an der des iPhones und ginge sonst mit Weckern,
    /// Bewegung und Puls je Epoche in iCloud. Frühere Versionen legten `nights.v1` direkt in Application Support ab;
    /// `adopt` holt die Datei einmalig herüber.
    static let watch = PhaseWakeStorage(defaults: SettingsFile.watchAlarms, directory: PrivateFiles.adopt(["nights.v1"]))
}

extension SettingsFile {
    /// Wecker, erledigte Nächte, Feiertage und Schlafziel der Uhr als Datei «settings.v1» im ausgenommenen Ordner der
    /// App-Gruppe (`PhaseWakeStorage.watchGroupDirectory`), damit die Komplikation sie lesen kann; nur die Uhr-App
    /// schreibt. Ohne App-Gruppe im Ordner `PrivateFiles` (die Komplikation zeigt dann keinen Wecker). Beim ersten
    /// Zugriff übernimmt sie die Werte früherer Versionen einmalig aus den `UserDefaults` der App-Gruppe (und aus
    /// `UserDefaults.standard`, wohin frühere Versionen ohne App-Gruppe schrieben) und löscht sie dort.
    static let watchAlarms: SettingsFile = {
        let manager = FileManager.default
        let fallback = PrivateFiles.directory.appendingPathComponent(PhaseWakeStorage.watchSettingsName)
        var url = fallback
        if let dir = PhaseWakeStorage.watchGroupDirectory(create: true) {
            url = dir.appendingPathComponent(PhaseWakeStorage.watchSettingsName)
            // Eine Datei aus einem Start ohne App-Gruppe kommt einmalig herüber, falls es dort noch keine gibt.
            if !manager.fileExists(atPath: url.path), manager.fileExists(atPath: fallback.path) {
                try? manager.moveItem(at: fallback, to: url)
            }
        }
        let file = SettingsFile(url: url)
        if let group = UserDefaults(suiteName: PhaseWakeStorage.watchGroup) { file.adopt(SettingsFile.alarmKeys, from: group) }
        file.adopt(SettingsFile.alarmKeys, from: .standard)
        return file
    }()

    /// Schlüssel, die bis Build 54 in den `UserDefaults` der App-Gruppe lagen (`PhaseWakeStorage`).
    static let alarmKeys = ["alarms", "alarmsUpdatedAt", "nightDoneUntilByAlarm", "nightDoneUntil", "holidays", "sleepGoalMinutes"]

    /// Merker der laufenden Planung als Datei «session.v1» im ausgenommenen Ordner `PrivateFiles`: Zeitzone des Plans
    /// (`SmartAlarm.sessionZoneKey`), Probewecker oder Powernap und Zeitraum zum Nachholen (`WatchModel`). Nur die
    /// Uhr-App braucht sie, darum nicht in der App-Gruppe (wie der Klingel-Zustand `ring-state.v1`). Übernimmt sie
    /// einmalig aus `UserDefaults.standard`; dort bleibt nur der technische Merker `diagUnlocked`.
    static let watchSession: SettingsFile = {
        let file = SettingsFile(url: PrivateFiles.directory.appendingPathComponent("session.v1"))
        file.adopt(SettingsFile.sessionKeys, from: .standard)
        return file
    }()

    /// Schlüssel, die bis Build 54 in `UserDefaults.standard` der Uhr lagen.
    static let sessionKeys = ["sessionZone", "transientPlan", "catchUpWindow"]
}
