import Foundation

/// Wochentag nach ISO 8601 (1 = Montag … 7 = Sonntag), wie `java.time.DayOfWeek`.
public enum Weekday: Int, CaseIterable, Comparable, Sendable {
    case monday = 1, tuesday, wednesday, thursday, friday, saturday, sunday

    /// Ordnet nach ISO-Reihenfolge, damit Montag vor Sonntag steht (für Format und Anzeige).
    public static func < (lhs: Weekday, rhs: Weekday) -> Bool { lhs.rawValue < rhs.rawValue }

    /// Wochentag eines Datums im gegebenen Kalender.
    public init(date: Date, calendar: Calendar) {
        // Calendar: 1 = Sonntag … 7 = Samstag
        let gregorian = calendar.component(.weekday, from: date)
        self = Weekday(rawValue: (gregorian + 5) % 7 + 1)!
    }
}

/// Weckart des Handy-Zweitweckers. Rohwerte wie die Kotlin-Namen.
public enum PhoneAlert: String, CaseIterable, Sendable {
    case off = "OFF", vibrate = "VIBRATE", sound = "SOUND"
}

/// Sonnenaufgang auf dem Bildschirm: erst schwach dunkelorange, dann volles warmes Licht. Identisch zu Kotlin.
public enum Sunrise: String, CaseIterable, Sendable {
    case off = "OFF", ring = "RING", window = "WINDOW"

    /// Dauer des Sonnenaufgangs beim Modus `ring` in Sekunden.
    public static let ringSeconds = 120
    /// Aufholzeit in Sekunden, wenn die Uhr beim Modus `window` vor der Zielzeit weckt: das Licht wird dann in dieser Zeit voll hell.
    public static let catchUpSeconds = 60

    /// Fortschritt 0–1 eines Lichts von `start` bis `end`.
    public static func progress(start: Date, end: Date, now: Date) -> Double {
        let total = end.timeIntervalSince(start)
        guard total > 0 else { return 1 }
        return min(1, max(0, now.timeIntervalSince(start) / total))
    }

    /// Bildschirmhelligkeit 0.01–1: am Anfang sehr dunkel, dann schneller heller.
    public static func brightness(_ progress: Double) -> Double {
        let p = min(1, max(0, progress))
        return 0.01 + 0.99 * p * p
    }
}

/// Wo der Sonnenaufgang leuchtet. Rohwerte wie die Kotlin-Namen.
/// Auf Apple nur im Format: Die Apple Watch hält ihren Bildschirm in der Weck-Sitzung nicht zuverlässig
/// an, darum bietet der iPhone-Editor keine Wahl, und das iPhone zeigt den Sonnenaufgang bei jedem Wert.
public enum SunriseDevice: String, CaseIterable, Sendable {
    /// Nur auf dem Handy (Standard, wie vor der Gerätewahl).
    case phone = "PHONE"
    /// Nur auf der Uhr (nur Android).
    case watch = "WATCH"
    /// Auf Handy und Uhr (nur Android).
    case both = "BOTH"

    /// Ob das Handy leuchtet (alles ausser `watch`).
    public var onPhone: Bool { self != .watch }
    /// Ob die Uhr leuchtet (alles ausser `phone`).
    public var onWatch: Bool { self != .phone }
}

/// Weckaufgabe: was es zum Beenden braucht. Rohwerte wie die Kotlin-Namen.
/// Auf Apple nur im Format: AlarmKit lässt den Stopp-Knopf des Systems nicht ersetzen, darum bietet
/// der iPhone-Editor die Aufgabe nicht an, und die Apple Watch behandelt jeden Wert wie `none` (Halten).
public enum WakeTask: String, CaseIterable, Sendable {
    /// Halten zum Beenden (Standard).
    case none = "NONE"
    /// Rechenaufgaben am Handy (nur Android).
    case math = "MATH"
}

/// Ein Wecker, wie ihn der Nutzer einstellt. Identisch zu `AlarmSpec` in `android/core`.
public struct AlarmSpec: Equatable, Sendable, Identifiable {
    /// Standard-Weckfenster in Minuten (zugleich das Höchste, siehe `maxWindowMinutes`).
    public static let defaultWindowMinutes = 30
    /// Fensterwert für «Genau» (`isExact`): kein frühes Wecken.
    public static let exactWindow = 0
    /// Bei «Genau» misst die Uhr so viele Minuten vor der Zielzeit, nur für den Verlauf.
    public static let measureMinutes = 30
    /// Kürzestes Weckfenster in Minuten (ausser `exactWindow`); darunter bleibt nach der Aufwärmphase kaum Zeit zum Erkennen.
    public static let minWindowMinutes = 10
    /// Grenze der Smart-Alarm-Sitzung von watchOS, auf beiden Plattformen gleich.
    public static let maxWindowMinutes = 30
    /// Kürzeste Schlafdauer im Einschlaf-Modus in Minuten (1 Stunde).
    public static let minSleepMinutes = 60
    /// Längste Schlafdauer im Einschlaf-Modus in Minuten (12 Stunden).
    public static let maxSleepMinutes = 12 * 60

    /// Eindeutige Kennung; nie leer, verknüpft Wecker, Nächte und Abgleich mit der Uhr.
    public let id: String
    /// Stunde der Zielzeit (0–23), Ortszeit.
    public let hour: Int
    /// Minute der Zielzeit (0–59).
    public let minute: Int
    /// Wochentage, an denen der Wecker klingelt; leer = einmalig.
    public let days: Set<Weekday>
    /// Länge des Weckfensters vor der Zielzeit in Minuten (10–30), oder `exactWindow` (0) für «Genau».
    public let windowMinutes: Int
    /// Ob der Wecker aktiv ist.
    public let enabled: Bool
    /// Weckart des iPhones als Zweitwecker.
    public let phone: PhoneAlert
    /// Ob die Uhr zusätzlich zur Vibration einen Ton spielt.
    public let watchSound: Bool
    /// Weckton des iPhones; leer = Standard.
    public let sound: String
    /// Gewünschte Schlafdauer ab Einschlafen in Minuten (60–720); `nil` = feste Zielzeit ohne Einschlaf-Modus.
    public let sleepDurationMinutes: Int?
    /// Eigene Bezeichnung; leer = keine.
    public let label: String
    /// Sonnenaufgang auf dem Handy-Bildschirm.
    public let sunrise: Sunrise
    /// Wo der Sonnenaufgang leuchtet; auf Apple ohne Wirkung, wird aber erhalten und mit der Uhr abgeglichen.
    public let sunriseDevice: SunriseDevice
    /// Weckaufgabe; auf Apple ohne Wirkung, wird aber erhalten und mit der Uhr abgeglichen.
    public let wakeTask: WakeTask
    /// «Nächstes Mal überspringen»: Termine mit einer Zielzeit bis einschliesslich hier fallen aus; 0 = keiner.
    /// Wandzeit in ms (`AlarmSchedule.wallMillis`: Ortsdatum und -uhrzeit, gezählt wie UTC), damit auf Reisen derselbe
    /// Kalendertag ausfällt. Der Wecker bleibt eingeschaltet. Nur für wiederholte Wecker (`AlarmSchedule.skipNext`).
    public let skipUntil: Int64
    /// An Feiertagen (Land und Region in den Einstellungen) nicht wecken. Nur für wiederholte Wecker.
    public let skipHolidays: Bool

    /// - Throws: ``DetectorConfigError`` bei ungültigen Werten (gleiche Regeln wie Kotlin).
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
    ) throws {
        guard !id.trimmingCharacters(in: .whitespaces).isEmpty else { throw DetectorConfigError(message: "id darf nicht leer sein") }
        guard (0...23).contains(hour) else { throw DetectorConfigError(message: "hour muss zwischen 0 und 23 liegen") }
        guard (0...59).contains(minute) else { throw DetectorConfigError(message: "minute muss zwischen 0 und 59 liegen") }
        guard windowMinutes == Self.exactWindow || (Self.minWindowMinutes...Self.maxWindowMinutes).contains(windowMinutes) else {
            throw DetectorConfigError(message: "windowMinutes muss 0 oder zwischen 10 und 30 liegen")
        }
        if let sleep = sleepDurationMinutes, !(Self.minSleepMinutes...Self.maxSleepMinutes).contains(sleep) {
            throw DetectorConfigError(message: "sleepDurationMinutes ausserhalb 60–720")
        }
        self.id = id
        self.hour = hour
        self.minute = minute
        self.days = days
        self.windowMinutes = windowMinutes
        self.enabled = enabled
        self.phone = phone
        self.watchSound = watchSound
        self.sound = sound
        self.sleepDurationMinutes = sleepDurationMinutes
        self.label = label
        self.sunrise = sunrise
        self.wakeTask = wakeTask
        self.sunriseDevice = sunriseDevice
        self.skipUntil = skipUntil
        self.skipHolidays = skipHolidays
    }

    /// «Genau»: weckt zur Zielzeit, nie früher; die Uhr misst trotzdem `measureMinutes` Minuten für den Verlauf.
    public var isExact: Bool { windowMinutes == Self.exactWindow }

    /// So viele Minuten vor der Zielzeit misst die Uhr: das Weckfenster, bei «Genau» `measureMinutes`.
    public var measureWindowMinutes: Int { isExact ? Self.measureMinutes : windowMinutes }

    /// Ob das iPhone als Zweitwecker mitweckt.
    public var phoneBackup: Bool { phone != .off }

    /// Einmaliger Wecker: wird nach dem Klingeln ausgeschaltet.
    public var isOneShot: Bool { days.isEmpty }

    /// Sonnenaufgang auf dem Handy-Bildschirm; `.off`, wenn er nur auf der Uhr leuchtet (wie Kotlin).
    public var phoneSunrise: Sunrise { sunriseDevice.onPhone ? sunrise : .off }

    /// Sonnenaufgang auf dem Uhr-Bildschirm; `.off`, wenn er nur auf dem Handy leuchtet (wie Kotlin).
    public var watchSunrise: Sunrise { sunriseDevice.onWatch ? sunrise : .off }

    /// Kopie mit geändertem Ein/Aus-Zustand.
    public func with(enabled: Bool) -> AlarmSpec { copy(enabled: enabled) }

    /// Kopie mit anderem `skipUntil` (Wandzeit-ms, 0 = nichts überspringen).
    public func with(skipUntil: Int64) -> AlarmSpec { copy(skipUntil: skipUntil) }

    /// Kopie mit anderer Feiertagsregel.
    public func with(skipHolidays: Bool) -> AlarmSpec { copy(skipHolidays: skipHolidays) }

    private func copy(enabled: Bool? = nil, skipUntil: Int64? = nil, skipHolidays: Bool? = nil) -> AlarmSpec {
        // Werte stammen aus einem gültigen Wecker; die Prüfung kann nicht scheitern.
        try! AlarmSpec(
            id: id, hour: hour, minute: minute, days: days, windowMinutes: windowMinutes,
            enabled: enabled ?? self.enabled, phone: phone, watchSound: watchSound, sleepDurationMinutes: sleepDurationMinutes,
            sound: sound, label: label, sunrise: sunrise, wakeTask: wakeTask, sunriseDevice: sunriseDevice,
            skipUntil: skipUntil ?? self.skipUntil, skipHolidays: skipHolidays ?? self.skipHolidays
        )
    }
}
