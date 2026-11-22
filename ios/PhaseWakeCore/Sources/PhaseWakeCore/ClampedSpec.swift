import Foundation

/// Ein Wert, den ``ClampedSpec`` auf den gültigen Bereich gesetzt hat. Identisch zu `SpecChange` (Kotlin).
public struct SpecChange: Equatable, Sendable, CustomStringConvertible {
    /// Wert vorher einer leeren Kennung.
    public static let emptyId = "empty"
    /// Wert nachher einer leeren Kennung: Sie wird durch eine neue UUID ersetzt.
    public static let newId = "new"

    /// Name des Felds wie in ``AlarmSpec``, z. B. `windowMinutes`.
    public let field: String
    /// Wert vorher; bei leerer Kennung ``emptyId``.
    public let from: String
    /// Wert nachher; bei neuer Kennung ``newId`` (die Kennung selbst steht nicht in der Diagnose).
    public let to: String

    /// Erzeugt eine Änderung.
    public init(field: String, from: String, to: String) {
        self.field = field
        self.from = from
        self.to = to
    }

    /// «feld vorher->nachher», z. B. «windowMinutes 45->30».
    public var description: String { "\(field) \(from)->\(to)" }
}

/// Wecker aus Werten, die ungültig sein können, ohne Absturz: Jeder Wert ausserhalb der Regeln von ``AlarmSpec`` wird
/// auf den nächsten gültigen gesetzt und in ``changes`` gemeldet; gültige Werte bleiben unverändert (dann ist
/// ``changes`` leer).
/// - `hour` auf 0–23, `minute` auf 0–59;
/// - `windowMinutes`: `AlarmSpec.exactWindow` (0, «Genau») bleibt, jeder andere Wert (auch negativ) auf
///   `AlarmSpec.minWindowMinutes`–`AlarmSpec.maxWindowMinutes` (Apple 30, Android 60);
/// - `sleepDurationMinutes`: `nil` bleibt, sonst auf `AlarmSpec.minSleepMinutes`–`AlarmSpec.maxSleepMinutes`;
/// - leere Kennung: neue UUID.
/// Alle anderen Felder haben keine Regel und werden unverändert übernommen. Gleiche Regeln und Reihenfolge der
/// Änderungen wie `ClampedSpec` (Kotlin); der Kern schreibt keine Diagnose, das tut die Aufrufstelle mit ``diagLine``.
public struct ClampedSpec: Equatable, Sendable {
    /// Anfang der Diagnose-Zeile (``diagLine``); auf allen Geräten gleich.
    public static let diagPrefix = "SPEC clamped"

    /// Der gültige Wecker.
    public let spec: AlarmSpec
    /// Die begrenzten Werte in ihrer Reihenfolge: id, hour, minute, windowMinutes, sleepDurationMinutes.
    public let changes: [SpecChange]

    /// Begrenzt die Werte; Parameter und Vorgaben wie der `init` von ``AlarmSpec``, der bei ungültigen Werten wirft.
    public init(
        id: String,
        hour: Int,
        minute: Int,
        days: Set<Weekday> = [],
        windowMinutes: Int = AlarmSpec.defaultWindowMinutes,
        enabled: Bool = true,
        phone: PhoneAlert = .sound,
        watchSound: Bool = false,
        sleepDurationMinutes: Int? = nil,
        sound: String = "",
        label: String = "",
        sunrise: Sunrise = .off,
        wakeTask: WakeTask = WakeTask.none,
        sunriseDevice: SunriseDevice = .phone,
        skipUntil: Int64 = 0,
        skipHolidays: Bool = false
    ) {
        var found: [SpecChange] = []
        func note(_ field: String, _ from: Int?, _ to: Int?) -> Int? {
            if from != to {
                found.append(SpecChange(field: field, from: ClampedSpec.text(from), to: ClampedSpec.text(to)))
            }
            return to
        }
        var validId = id
        if id.trimmingCharacters(in: .whitespaces).isEmpty {
            found.append(SpecChange(field: "id", from: SpecChange.emptyId, to: SpecChange.newId))
            validId = UUID().uuidString
        }
        let validHour = note("hour", hour, min(23, max(0, hour))) ?? 0
        let validMinute = note("minute", minute, min(59, max(0, minute))) ?? 0
        let validWindow = note("windowMinutes", windowMinutes, ClampedSpec.window(windowMinutes)) ?? windowMinutes
        let validSleep = note("sleepDurationMinutes", sleepDurationMinutes, ClampedSpec.sleep(sleepDurationMinutes))
        changes = found
        spec = AlarmSpec(
            valid: validId, hour: validHour, minute: validMinute, days: days, windowMinutes: validWindow,
            enabled: enabled, phone: phone, watchSound: watchSound, sleepDurationMinutes: validSleep, sound: sound,
            label: label, sunrise: sunrise, wakeTask: wakeTask, sunriseDevice: sunriseDevice, skipUntil: skipUntil,
            skipHolidays: skipHolidays
        )
    }

    /// Diagnose-Zeile, wenn etwas begrenzt wurde: ``diagPrefix`` und die Änderungen, mit «, » getrennt, z. B.
    /// «SPEC clamped hour 24->23, windowMinutes 45->30». `nil`, wenn alle Werte gültig waren.
    public var diagLine: String? {
        changes.isEmpty ? nil : Self.diagPrefix + " " + changes.map(\.description).joined(separator: ", ")
    }

    /// Gültiges Weckfenster zu `minutes`: «Genau» bleibt, sonst auf 10 bis zum grössten Fenster dieser Plattform.
    public static func window(_ minutes: Int) -> Int {
        minutes == AlarmSpec.exactWindow
            ? minutes : min(AlarmSpec.maxWindowMinutes, max(AlarmSpec.minWindowMinutes, minutes))
    }

    /// Gültige Schlafdauer zu `minutes`: `nil` bleibt, sonst auf 60–720 Minuten.
    public static func sleep(_ minutes: Int?) -> Int? {
        minutes.map { min(AlarmSpec.maxSleepMinutes, max(AlarmSpec.minSleepMinutes, $0)) }
    }

    /// Wert für die Diagnose wie `toString()` in Kotlin: `null` für keinen Wert.
    private static func text(_ value: Int?) -> String { value.map(String.init) ?? "null" }
}
