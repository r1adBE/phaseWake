import LocalAuthentication
import PhaseWakeCore
import SwiftUI
import UniformTypeIdentifiers

/// Inhalt der Sicherung aus dem Stand des iPhones bilden und wieder einspielen. Formate wie Android
/// (`BackupPayload`); ohne Geräusch-Ausschnitte und ohne Gesundheitsdaten, mit dem Befinden zu Nächten aus anderen Apps.
@MainActor
enum BackupIO {
    private static let themeKey = "themeMode"

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
                historyClearedAt: model.historyClearedAt.map { Int64($0.timeIntervalSince1970 * 1000) }
            ),
            healthFeelings: HealthFeelings.encode(model.healthFeelings)
        )
    }

    /// Verschlüsselt den Stand mit zufälligem Salz und Nonce (im Hintergrund: 210 000 Runden PBKDF2).
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

    /// Ersetzt Wecker, Verlauf, Notizen (samt Befinden zu Nächten aus anderen Apps), Abendroutine und Einstellungen;
    /// danach geht alles an die Uhr.
    static func apply(_ payload: BackupPayload, to model: PhoneModel) {
        let storage = model.storage
        let alarms = (try? Codec.decodeAlarms(payload.alarms)) ?? []
        storage.mutate { _ in Array(alarms.prefix(PhoneModel.maxAlarms)) }
        storage.replaceNights(payload.nights.compactMap { try? Codec.decodeNight($0) })
        storage.replaceNotes(SleepNotes.decode(payload.notes))
        storage.setCustomNotes(SleepNotes.decodeCustom(payload.customNotes))
        storage.replaceHealthFeelings(HealthFeelings.decode(payload.healthFeelings))
        let defaults = UserDefaults.standard
        defaults.set(payload.routineItems, forKey: PhoneModel.routineItemsKey)
        defaults.set(payload.routineTicks, forKey: PhoneModel.routineTicksKey)
        let s = payload.settings
        defaults.set(SleepGoal.clamp(s.sleepGoal), forKey: PhoneModel.sleepGoalKey)
        defaults.set(s.bedtimeReminder, forKey: PhoneModel.bedtimeKey)
        defaults.set(s.weeklyReview, forKey: PhoneModel.weeklyKey)
        defaults.set(s.soundRecDefault, forKey: PhoneModel.soundRecDefaultKey)
        defaults.set((ThemeMode(rawValue: s.theme.lowercased()) ?? .system).rawValue, forKey: themeKey)
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
