import Foundation

/// Einstellungen in der Sicherung (nur das iPhone; Uhr-Einstellungen gibt es nicht). Identisch zu Kotlin.
public struct BackupSettings: Equatable, Sendable {
    /// Schlafziel in Minuten.
    public var sleepGoal: Int
    /// Bettzeit-Erinnerung an.
    public var bedtimeReminder: Bool
    /// Wochenrückblick an.
    public var weeklyReview: Bool
    /// Geräusche aufnehmen für neue Nächte.
    public var soundRecDefault: Bool
    /// Darstellung als Name («SYSTEM», «LIGHT», «DARK»); unbekannt = System.
    public var theme: String
    /// Land der Feiertage (z. B. «CH»); leer = keine.
    public var holidayCountry: String
    /// Region der Feiertage (z. B. «BE»); leer = ganzes Land.
    public var holidayRegion: String

    /// Erzeugt Einstellungen; ohne Angaben die Vorgaben der App.
    public init(sleepGoal: Int = SleepGoal.defaultMinutes, bedtimeReminder: Bool = false, weeklyReview: Bool = false,
                soundRecDefault: Bool = false, theme: String = "SYSTEM", holidayCountry: String = "", holidayRegion: String = "") {
        self.sleepGoal = sleepGoal
        self.bedtimeReminder = bedtimeReminder
        self.weeklyReview = weeklyReview
        self.soundRecDefault = soundRecDefault
        self.theme = theme
        self.holidayCountry = holidayCountry
        self.holidayRegion = holidayRegion
    }
}

/// Inhalt einer Sicherung: die gespeicherten Texte in ihren eigenen Formaten (Wecker, Nächte, Notizen,
/// Abendroutine) und die Einstellungen. Ohne Geräusch-Ausschnitte und ohne Gesundheitsdaten. Identisch zu Kotlin.
public struct BackupPayload: Equatable, Sendable {
    /// Wert von «format».
    public static let format = "phasewake-backup"
    /// Wert von «version»; neuere Versionen werden gelesen, soweit die Schlüssel bekannt sind.
    public static let version = 1

    /// Erstellt (ms seit 1970).
    public var createdAt: Int64
    /// Wecker im Format von `Codec.encodeAlarms`.
    public var alarms: String
    /// Nächte, je eine im Format von `Codec.encodeNight`.
    public var nights: [String]
    /// Notizen je Nachtdatum (`SleepNotes.encode`).
    public var notes: String
    /// Eigene Notizen (`SleepNotes.encodeCustom`).
    public var customNotes: String
    /// Punkte der Abendroutine (`Routine.encodeItems`); `nil` = nie geändert (Vorschläge).
    public var routineItems: String?
    /// Haken der Abendroutine (`Routine.encodeTicks`).
    public var routineTicks: String
    /// Einstellungen.
    public var settings: BackupSettings

    /// Erzeugt einen Inhalt.
    public init(createdAt: Int64, alarms: String, nights: [String], notes: String, customNotes: String,
                routineItems: String?, routineTicks: String, settings: BackupSettings) {
        self.createdAt = createdAt
        self.alarms = alarms
        self.nights = nights
        self.notes = notes
        self.customNotes = customNotes
        self.routineItems = routineItems
        self.routineTicks = routineTicks
        self.settings = settings
    }

    /// Als JSON-Objekt (UTF-8); Schlüssel wie Kotlin, Reihenfolge ohne Bedeutung.
    public func toJson() -> String {
        let s = settings
        let root: [String: Any] = [
            "format": Self.format, "version": Self.version, "createdAt": NSNumber(value: createdAt), "alarms": alarms,
            "nights": nights, "notes": notes, "customNotes": customNotes, "routineItems": routineItems.map { $0 as Any } ?? NSNull(),
            "routineTicks": routineTicks,
            "settings": [
                "sleepGoal": s.sleepGoal, "bedtimeReminder": s.bedtimeReminder, "weeklyReview": s.weeklyReview,
                "soundRecDefault": s.soundRecDefault, "theme": s.theme, "holidayCountry": s.holidayCountry,
                "holidayRegion": s.holidayRegion,
            ] as [String: Any],
        ]
        // Nur Texte, Zahlen, Wahrheitswerte, Listen und `NSNull`: Schreiben kann nicht scheitern.
        let data = (try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys, .withoutEscapingSlashes])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    /// Liest `toJson`. Unbekannte Schlüssel werden ignoriert, fehlende ergeben die Vorgaben.
    /// - Throws: ``CodecError`` bei kaputtem JSON, ohne Objekt, mit falschem «format» oder ohne Wecker.
    public static func fromJson(_ text: String) throws -> BackupPayload {
        let parsed: Any
        do {
            parsed = try JSONSerialization.jsonObject(with: Data(text.utf8))
        } catch {
            throw CodecError(description: "JSON unlesbar: \(error)")
        }
        guard let root = parsed as? [String: Any] else { throw CodecError(description: "Kein JSON-Objekt") }
        guard root["format"] as? String == format else { throw CodecError(description: "Keine Phase-Wake-Sicherung") }
        guard let alarms = root["alarms"] as? String else { throw CodecError(description: "Wecker fehlen") }
        let s = root["settings"] as? [String: Any] ?? [:]
        let d = BackupSettings()
        return BackupPayload(
            createdAt: integer(root["createdAt"]) ?? 0,
            alarms: alarms,
            nights: (root["nights"] as? [Any] ?? []).compactMap { $0 as? String },
            notes: root["notes"] as? String ?? "",
            customNotes: root["customNotes"] as? String ?? "",
            routineItems: root["routineItems"] as? String,
            routineTicks: root["routineTicks"] as? String ?? "",
            settings: BackupSettings(
                sleepGoal: integer(s["sleepGoal"]).map { Int($0) } ?? d.sleepGoal,
                bedtimeReminder: bool(s["bedtimeReminder"]) ?? d.bedtimeReminder,
                weeklyReview: bool(s["weeklyReview"]) ?? d.weeklyReview,
                soundRecDefault: bool(s["soundRecDefault"]) ?? d.soundRecDefault,
                theme: s["theme"] as? String ?? d.theme,
                holidayCountry: s["holidayCountry"] as? String ?? d.holidayCountry,
                holidayRegion: s["holidayRegion"] as? String ?? d.holidayRegion
            )
        )
    }

    /// Ganze Zahl wie Kotlin (`Long`): kein Wahrheitswert und kein echter Bruch.
    private static func integer(_ value: Any?) -> Int64? {
        guard let number = value as? NSNumber, !isBool(number) else { return nil }
        let double = number.doubleValue
        guard double.rounded() == double, abs(double) < 9e18 else { return nil }
        return number.int64Value
    }

    /// Wahrheitswert wie Kotlin: nur `true`/`false`, keine Zahl.
    private static func bool(_ value: Any?) -> Bool? {
        guard let number = value as? NSNumber, isBool(number) else { return nil }
        return number.boolValue
    }

    private static func isBool(_ number: NSNumber) -> Bool { CFGetTypeID(number) == CFBooleanGetTypeID() }
}
