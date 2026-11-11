import LocalAuthentication
import PhaseWakeCore
import SwiftUI
import UniformTypeIdentifiers

/// Inhalt der Sicherung aus dem Stand des iPhones bilden und wieder einspielen. Formate wie Android
/// (`BackupPayload`); ohne Geräusch-Ausschnitte und ohne Gesundheitsdaten, mit dem Befinden zu Nächten aus anderen Apps.
@MainActor
enum BackupIO {
    private static let themeKey = "themeMode"
    /// Schalter, den es nur auf Android gibt («Wecker auf dem Sperrbildschirm»): Das iPhone behält den Wert aus einer
    /// Android-Sicherung für die nächste Sicherung; ohne gespeicherten Wert `true`.
    private static let nextAlarmNoticeKey = "backupNextAlarmNotice"
    /// «Einschlafen automatisch erkennen» (seit 10.11.2026 auch auf Apple, `WatchNight.autoOnset`); ohne Wert `true`.
    nonisolated static let autoOnsetKey = "backupAutoOnset"
    /// Verlaufsdatei von `PhaseWakeStorage` im Ordner `PrivateFiles`.
    private static let nightsFile = "nights.v1"
    /// `createdAt` der Sicherung, deren Wecker und Einstellungen in `SettingsFile.phone` stehen (in derselben Datei,
    /// also nur zusammen mit ihnen gespeichert); vor jedem Wiederherstellen entfernt.
    private static let restoredKey = "restoredBackupAt"

    /// Aktueller Stand als Sicherungsinhalt.
    static func payload(_ model: PhoneModel, now: Date = Date()) -> BackupPayload {
        let defaults = SettingsFile.phone
        let theme = ThemeMode(rawValue: defaults.string(forKey: themeKey) ?? "") ?? .system
        return BackupPayload(
            createdAt: TimeBounds.millis(now),
            alarms: Codec.encodeAlarms(model.alarms),
            nights: model.nights.map(Codec.encodeNight),
            notes: SleepNotes.encode(model.notes),
            customNotes: SleepNotes.encodeCustom(model.customNotes),
            // Nie geändert: `nil`, dann gelten nach dem Wiederherstellen die Vorschläge.
            routineItems: model.privateNotes.routineItemsText(),
            routineTicks: Routine.encodeTicks(model.routineTicks),
            settings: BackupSettings(
                sleepGoal: model.sleepGoal, bedtimeReminder: model.bedtimeReminder, weeklyReview: model.weeklyReview,
                soundRecDefault: model.soundRecDefault, theme: theme.rawValue.uppercased(),
                holidayCountry: model.holidays.country, holidayRegion: model.holidays.region,
                historyClearedAt: model.historyClearedAt.map(TimeBounds.millis),
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
    /// in einem Schritt; danach geht alles an die Uhr. Zuerst entstehen Verlauf und Notizen als Dateien im
    /// Arbeitsordner von ``RestoreJournal`` und werden dann getauscht. Wecker und Einstellungen (`SettingsFile.phone`)
    /// sowie die Abendroutine (kleine Dateien in `PrivateFiles`) folgen erst nach dem gelungenen Tausch
    /// (``applySettings(_:to:)``, wiederholbar); der Merker bleibt, bis auch sie in der Datei stehen. Ab dem Merker
    /// (``RestoreJournal/begin(_:payload:)``) übersteht die Wiederherstellung auch ein Ende der App: Der nächste Start
    /// führt sie zu Ende oder holt den alten Stand zurück.
    /// - Throws: wenn eine Datei nicht geschrieben oder getauscht werden konnte; dann ist nichts geändert (ein nicht
    ///   ganz gelungenes Zurückholen holt der nächste Start nach). Ebenso, solange eine frühere Wiederherstellung offen ist.
    static func apply(_ payload: BackupPayload, to model: PhoneModel) throws {
        // Eine abgebrochene frühere Wiederherstellung zuerst abschliessen; bleibt sie offen, wirft `prepare`.
        RestoreJournal.recover(model: model)
        try RestoreJournal.prepare()
        let marker: RestoreJournal.Marker
        do {
            // Merker der letzten Wiederherstellung weg, bevor etwas getauscht wird (danach zeigt er nur diese an).
            SettingsFile.phone.removeObject(forKey: restoredKey)
            guard SettingsFile.phone.flush() else { throw CocoaError(.fileWriteOutOfSpace) }
            let names = try stage(payload, in: RestoreJournal.staging)
            marker = try RestoreJournal.copyLive(names)
            try RestoreJournal.begin(marker, payload: payload)
        } catch {
            // Vor dem Merker ist nichts getauscht: Reste weg, der bisherige Stand bleibt.
            RestoreJournal.finish()
            throw error
        }
        do {
            try RestoreJournal.swap(marker)
        } catch {
            RestoreJournal.rollBack(marker, model: model)
            throw error
        }
        // Einstellungen nicht gespeichert (Speicher voll): Sie gelten schon im Speicher; der Merker bleibt, damit der
        // nächste Start sie aus der Sicherung noch einmal schreibt.
        if applySettings(payload, to: model) { RestoreJournal.finish() }
    }

    /// Wecker, Abendroutine und Einstellungen aus der Sicherung übernehmen, Stand neu laden und an die Uhr geben.
    /// Wiederholbar (auch nach einem Abbruch beim nächsten Start, ``RestoreJournal/recover(model:)``).
    /// - Returns: ob Wecker und Einstellungen in `SettingsFile.phone` gespeichert sind (sonst später wiederholen).
    @discardableResult
    static func applySettings(_ payload: BackupPayload, to model: PhoneModel) -> Bool {
        let alarms = (try? Codec.decodeAlarms(payload.alarms)) ?? []
        model.storage.replaceAll(Array(alarms.prefix(PhoneModel.maxAlarms)))
        // Abendroutine als Dateien in `PrivateFiles` (nicht in der iCloud-Sicherung); ohne Punkte die Vorschläge.
        model.privateNotes.setRoutineItemsText(payload.routineItems)
        model.privateNotes.setRoutineTicks(Routine.decodeTicks(payload.routineTicks))
        let defaults = SettingsFile.phone
        let s = payload.settings
        defaults.set(SleepGoal.clamp(s.sleepGoal), forKey: PhoneModel.sleepGoalKey)
        defaults.set(s.bedtimeReminder, forKey: PhoneModel.bedtimeKey)
        defaults.set(s.weeklyReview, forKey: PhoneModel.weeklyKey)
        defaults.set(s.soundRecDefault, forKey: PhoneModel.soundRecDefaultKey)
        defaults.set((ThemeMode(rawValue: s.theme.lowercased()) ?? .system).rawValue, forKey: themeKey)
        defaults.set(s.nextAlarmNotice, forKey: nextAlarmNoticeKey)
        defaults.set(s.autoOnset, forKey: autoOnsetKey)
        // Unplausibel → keiner, in der Zukunft → jetzt (Vollaudit DA-1).
        if let cleared = HistoryClear.clamped(ms: s.historyClearedAt, now: Date()) {
            defaults.set(NSNumber(value: cleared), forKey: PhoneModel.historyClearedKey)
        } else {
            defaults.removeObject(forKey: PhoneModel.historyClearedKey)
        }
        model.holidays.select(country: s.holidayCountry, region: s.holidayRegion)
        model.loadState()
        model.holidaysChanged()
        Task { await model.replanWeekly() }
        // Darstellung neu zeichnen (`PrivateSetting`).
        PrivateSettingChanges.shared.bump()
        defaults.set(NSNumber(value: payload.createdAt), forKey: restoredKey)
        let saved = defaults.flush()
        DiagStore.shared.log(saved ? DiagKind.sync : .error, saved ? "restored \(alarms.count) alarms" : "restored settings not saved, retry")
        return saved
    }

    /// Ob Wecker und Einstellungen dieser Sicherung schon in `SettingsFile.phone` stehen (``restoredKey``), etwa weil
    /// eine spätere Änderung sie mitgeschrieben hat; dann holt ``RestoreJournal/recover(model:)`` sie nicht noch einmal.
    static func settingsApplied(_ payload: BackupPayload) -> Bool {
        (SettingsFile.phone.object(forKey: restoredKey) as? NSNumber)?.int64Value == payload.createdAt
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
