import LocalAuthentication
import PhaseWakeCore
import SwiftUI
import UniformTypeIdentifiers

/// Inhalt der Sicherung aus dem Stand des iPhones bilden und wieder einspielen. Formate wie Android
/// (`BackupPayload`); ohne Geräusch-Ausschnitte und ohne Gesundheitsdaten, mit dem Befinden zu Nächten aus anderen Apps.
@MainActor
enum BackupIO {
    private static let themeKey = "themeMode"
    /// Schalter, die es nur auf Android gibt («Wecker auf dem Sperrbildschirm», «Einschlafen automatisch erkennen»):
    /// Das iPhone behält den Wert aus einer Android-Sicherung für die nächste Sicherung; ohne gespeicherten Wert `true`.
    private static let nextAlarmNoticeKey = "backupNextAlarmNotice"
    private static let autoOnsetKey = "backupAutoOnset"
    /// Verlaufsdatei von `PhaseWakeStorage` im Ordner `PrivateFiles`.
    private static let nightsFile = "nights.v1"

    /// Aktueller Stand als Sicherungsinhalt.
    static func payload(_ model: PhoneModel, now: Date = Date()) -> BackupPayload {
        let defaults = UserDefaults.standard
        let theme = ThemeMode(rawValue: defaults.string(forKey: themeKey) ?? "") ?? .system
        return BackupPayload(
            createdAt: Int64(now.timeIntervalSince1970 * 1000),
            alarms: Codec.encodeAlarms(model.alarms),
            nights: model.nights.map(Codec.encodeNight),
            notes: SleepNotes.encode(model.notes),
            customNotes: SleepNotes.encodeCustom(model.customNotes),
            // Nie geändert: `nil`, dann gelten nach dem Wiederherstellen die Vorschläge.
            routineItems: defaults.string(forKey: PhoneModel.routineItemsKey),
            routineTicks: Routine.encodeTicks(model.routineTicks),
            settings: BackupSettings(
                sleepGoal: model.sleepGoal, bedtimeReminder: model.bedtimeReminder, weeklyReview: model.weeklyReview,
                soundRecDefault: model.soundRecDefault, theme: theme.rawValue.uppercased(),
                holidayCountry: model.holidays.country, holidayRegion: model.holidays.region,
                historyClearedAt: model.historyClearedAt.map { Int64($0.timeIntervalSince1970 * 1000) },
                nextAlarmNotice: defaults.object(forKey: nextAlarmNoticeKey) as? Bool ?? true,
                autoOnset: defaults.object(forKey: autoOnsetKey) as? Bool ?? true
            ),
            healthFeelings: HealthFeelings.encode(model.healthFeelings)
        )
    }

    /// Verschlüsselt den Stand mit zufälligem Salz und Nonce (im Hintergrund, PBKDF2 braucht etwas Zeit).
    static func encrypt(_ payload: BackupPayload, password: String) async throws -> Data {
        let json = Data(payload.toJson().utf8)
        return try await Task.detached(priority: .userInitiated) {
            var rng = SystemRandomNumberGenerator()
            let salt = Data((0..<BackupFile.saltBytes).map { _ in UInt8.random(in: .min ... .max, using: &rng) })
            let nonce = Data((0..<BackupFile.nonceBytes).map { _ in UInt8.random(in: .min ... .max, using: &rng) })
            return try BackupFile.encrypt(json, password: password, salt: salt, nonce: nonce)
        }.value
    }

    /// Entschlüsselt und liest eine Datei. - Throws: ``BackupError`` (fremd, falsches Passwort, unlesbar).
    static func decrypt(_ file: Data, password: String) async throws -> BackupPayload {
        try await Task.detached(priority: .userInitiated) {
            let plain = try BackupFile.decrypt(file, password: password)
            do {
                let payload = try BackupPayload.fromJson(String(decoding: plain, as: UTF8.self))
                _ = try Codec.decodeAlarms(payload.alarms)
                return payload
            } catch {
                throw BackupError(.unreadable, "Inhalt unlesbar: \(error)")
            }
        }.value
    }

    /// Vorschau: Wecker, Nächte, Tage mit Notizen.
    static func counts(_ payload: BackupPayload) -> (alarms: Int, nights: Int, noteDays: Int) {
        ((try? Codec.decodeAlarms(payload.alarms).count) ?? 0,
         payload.nights.filter { (try? Codec.decodeNight($0)) != nil }.count,
         SleepNotes.decode(payload.notes).count)
    }

    /// Ersetzt Wecker, Verlauf, Notizen (samt Befinden zu Nächten aus anderen Apps), Abendroutine und Einstellungen
    /// in einem Schritt; danach geht alles an die Uhr. Zuerst entstehen Verlauf und Notizen als Dateien in einem
    /// eigenen Ordner und werden dann getauscht (``exchange(_:from:to:rollback:)``). Wecker und Einstellungen liegen in
    /// `UserDefaults`, dessen Schreiben nicht scheitert; sie folgen erst nach dem gelungenen Tausch.
    /// - Throws: wenn eine Datei nicht geschrieben oder getauscht werden konnte; dann ist nichts geändert.
    static func apply(_ payload: BackupPayload, to model: PhoneModel) throws {
        let manager = FileManager.default
        let live = PrivateFiles.directory
        // Arbeitsordner im selben Volume wie die Dateien: Tauschen ist dann ein atomares Umbenennen.
        let work = try manager.url(for: .itemReplacementDirectory, in: .userDomainMask, appropriateFor: live, create: true)
        defer { try? manager.removeItem(at: work) }
        let staging = work.appendingPathComponent("staging", isDirectory: true)
        let rollback = work.appendingPathComponent("rollback", isDirectory: true)
        try manager.createDirectory(at: staging, withIntermediateDirectories: true)
        try manager.createDirectory(at: rollback, withIntermediateDirectories: true)
        let names = try stage(payload, in: staging)
        try exchange(names, from: staging, to: live, rollback: rollback)

        let alarms = (try? Codec.decodeAlarms(payload.alarms)) ?? []
        model.storage.mutate { _ in Array(alarms.prefix(PhoneModel.maxAlarms)) }
        let defaults = UserDefaults.standard
        defaults.set(payload.routineItems, forKey: PhoneModel.routineItemsKey)
        defaults.set(payload.routineTicks, forKey: PhoneModel.routineTicksKey)
        let s = payload.settings
        defaults.set(SleepGoal.clamp(s.sleepGoal), forKey: PhoneModel.sleepGoalKey)
        defaults.set(s.bedtimeReminder, forKey: PhoneModel.bedtimeKey)
        defaults.set(s.weeklyReview, forKey: PhoneModel.weeklyKey)
        defaults.set(s.soundRecDefault, forKey: PhoneModel.soundRecDefaultKey)
        defaults.set((ThemeMode(rawValue: s.theme.lowercased()) ?? .system).rawValue, forKey: themeKey)
        defaults.set(s.nextAlarmNotice, forKey: nextAlarmNoticeKey)
        defaults.set(s.autoOnset, forKey: autoOnsetKey)
        if let cleared = s.historyClearedAt {
            defaults.set(NSNumber(value: cleared), forKey: PhoneModel.historyClearedKey)
        } else {
            defaults.removeObject(forKey: PhoneModel.historyClearedKey)
        }
        model.holidays.select(country: s.holidayCountry, region: s.holidayRegion)
        model.loadState()
        model.holidaysChanged()
        Task { await model.replanWeekly() }
        DiagStore.shared.log(.sync, "restored \(alarms.count) alarms")
    }

    /// Schreibt Verlauf und Notizen der Sicherung als Dateien nach `staging`, mit denselben Regeln wie im Betrieb
    /// (gleiche Nächte einmal, höchstens 90). - Returns: Namen der Dateien. - Throws: wenn eine Datei fehlt
    /// (nicht geschrieben, z. B. Speicher voll).
    private static func stage(_ payload: BackupPayload, in staging: URL) throws -> [String] {
        PhaseWakeStorage(directory: staging).replaceNights(payload.nights.compactMap { try? Codec.decodeNight($0) })
        let notes = PrivateNotes(directory: staging, defaults: nil)
        notes.replaceNotes(SleepNotes.decode(payload.notes))
        notes.setCustomNotes(SleepNotes.decodeCustom(payload.customNotes))
        notes.replaceHealthFeelings(HealthFeelings.decode(payload.healthFeelings))
        let names = [nightsFile] + PrivateNotes.fileNames
        for name in names where !FileManager.default.fileExists(atPath: staging.appendingPathComponent(name).path) {
            throw CocoaError(.fileWriteUnknown, userInfo: [NSFilePathErrorKey: name])
        }
        return names
    }

    /// Tauscht die Dateien `names` aus `staging` nach `live`, je Datei atomar. Vorher kommen Kopien der bisherigen
    /// Dateien nach `rollback` (auf APFS ein Klon ohne zusätzlichen Platz); scheitert ein Tausch, werden die schon
    /// getauschten aus den Kopien zurückgeholt (nur Umbenennen) oder, wenn es sie vorher nicht gab, entfernt.
    /// - Throws: wenn eine Kopie oder ein Tausch scheitert; die bisherigen Dateien sind dann wieder da.
    private static func exchange(_ names: [String], from staging: URL, to live: URL, rollback: URL) throws {
        let manager = FileManager.default
        var existed: Set<String> = []
        for name in names where manager.fileExists(atPath: live.appendingPathComponent(name).path) {
            try manager.copyItem(at: live.appendingPathComponent(name), to: rollback.appendingPathComponent(name))
            existed.insert(name)
        }
        var swapped: [String] = []
        do {
            for name in names {
                try place(staging.appendingPathComponent(name), at: live.appendingPathComponent(name), replacing: existed.contains(name))
                swapped.append(name)
            }
        } catch {
            for name in swapped.reversed() {
                let target = live.appendingPathComponent(name)
                do {
                    if existed.contains(name) {
                        try place(rollback.appendingPathComponent(name), at: target, replacing: true)
                    } else {
                        try manager.removeItem(at: target)
                    }
                } catch let rollbackError {
                    DiagStore.shared.log(.error, "restore rollback \(name): \(rollbackError.localizedDescription)")
                }
            }
            throw error
        }
    }

    /// Setzt `source` an die Stelle von `target`: atomar ersetzen oder, ohne bisherige Datei, verschieben.
    private static func place(_ source: URL, at target: URL, replacing: Bool) throws {
        if replacing {
            _ = try FileManager.default.replaceItemAt(target, withItemAt: source)
        } else {
            try FileManager.default.moveItem(at: source, to: target)
        }
    }
}

/// Bestätigung mit Face ID, Touch ID oder Code vor dem Löschen und Ersetzen von Daten.
@MainActor
enum DeviceAuth {
    /// `true` nach Bestätigung, oder ohne Gerätesperre (dann gilt nur die Rückfrage davor). Abbruch: `false`.
    static func confirm(reason: String) async -> Bool {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else { return true }
        do {
            return try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
        } catch {
            DiagStore.shared.log(.other, "auth cancelled")
            return false
        }
    }
}

/// Die verschlüsselte Datei für den Sichern-Dialog des Systems.
struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    let data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
