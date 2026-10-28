import Foundation

/// Wochentag nach ISO 8601 (1 = Montag … 7 = Sonntag), wie `java.time.DayOfWeek`.
public enum Weekday: Int, CaseIterable, Comparable, Sendable {
    case monday = 1, tuesday, wednesday, thursday, friday, saturday, sunday

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

/// Sonnenaufgang auf dem Handy: erst schwach dunkelorange, dann volles warmes Licht. Identisch zu Kotlin.
public enum Sunrise: String, CaseIterable, Sendable {
    case off = "OFF", ring = "RING", window = "WINDOW"

    public static let ringSeconds = 120
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
    public static let defaultWindowMinutes = 30
    public static let minWindowMinutes = 10
    /// Grenze der Smart-Alarm-Sitzung von watchOS, auf beiden Plattformen gleich.
    public static let maxWindowMinutes = 30
    public static let minSleepMinutes = 60
    public static let maxSleepMinutes = 12 * 60

    public let id: String
    public let hour: Int
    public let minute: Int
    public let days: Set<Weekday>
    public let windowMinutes: Int
    public let enabled: Bool
    public let phone: PhoneAlert
    public let watchSound: Bool
    /// Weckton des iPhones; leer = Standard.
    public let sound: String
    public let sleepDurationMinutes: Int?
    public let label: String
    /// Sonnenaufgang auf dem Handy-Bildschirm.
    public let sunrise: Sunrise
    /// Weckaufgabe; auf Apple ohne Wirkung, wird aber erhalten und mit der Uhr abgeglichen.
    public let wakeTask: WakeTask

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
        wakeTask: WakeTask = WakeTask.none
    ) throws {
        guard !id.trimmingCharacters(in: .whitespaces).isEmpty else { throw DetectorConfigError(message: "id darf nicht leer sein") }
        guard (0...23).contains(hour) else { throw DetectorConfigError(message: "hour muss zwischen 0 und 23 liegen") }
        guard (0...59).contains(minute) else { throw DetectorConfigError(message: "minute muss zwischen 0 und 59 liegen") }
        guard (Self.minWindowMinutes...Self.maxWindowMinutes).contains(windowMinutes) else {
            throw DetectorConfigError(message: "windowMinutes ausserhalb 10–30")
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
    }

    /// Ob das iPhone als Zweitwecker mitweckt.
    public var phoneBackup: Bool { phone != .off }

    /// Einmaliger Wecker: wird nach dem Klingeln ausgeschaltet.
    public var isOneShot: Bool { days.isEmpty }

    /// Kopie mit geändertem Ein/Aus-Zustand.
    public func with(enabled: Bool) -> AlarmSpec {
        // Werte stammen aus einem gültigen Wecker; die Prüfung kann nicht scheitern.
        try! AlarmSpec(
            id: id, hour: hour, minute: minute, days: days, windowMinutes: windowMinutes,
            enabled: enabled, phone: phone, watchSound: watchSound, sleepDurationMinutes: sleepDurationMinutes,
            sound: sound, label: label, sunrise: sunrise, wakeTask: wakeTask
        )
    }
}

/// Zeitplan einer Weck-Nacht.
public struct WakePlan: Equatable, Sendable {
    public let alarmId: String
    public let windowStart: Date
    public let target: Date

    public init(alarmId: String, windowStart: Date, target: Date) {
        self.alarmId = alarmId
        self.windowStart = windowStart
        self.target = target
    }
}

/// Berechnet, wann ein Wecker das nächste Mal fällig ist. Identisch zu `AlarmSchedule` (Kotlin).
public enum AlarmSchedule {
    private static let searchDays = 8

    /// Nächste Zielzeit, die echt nach `now` liegt, oder `nil`, wenn der Wecker aus ist.
    /// Sommer-/Winterzeit: in der Lücke die erste gültige Zeit danach, in der Überlappung die frühere.
    public static func nextTarget(_ spec: AlarmSpec, now: Date, calendar: Calendar) -> Date? {
        guard spec.enabled else { return nil }
        let today = calendar.startOfDay(for: now)
        for offset in 0...searchDays {
            guard let day = calendar.date(byAdding: .day, value: offset, to: today) else { continue }
            if !spec.isOneShot, !spec.days.contains(Weekday(date: day, calendar: calendar)) { continue }
            var parts = calendar.dateComponents([.year, .month, .day], from: day)
            parts.hour = spec.hour
            parts.minute = spec.minute
            guard let target = resolve(parts, calendar: calendar) else { continue }
            if target > now { return target }
        }
        return nil
    }

    public static func nextPlan(_ spec: AlarmSpec, now: Date, calendar: Calendar) -> WakePlan? {
        guard let target = nextTarget(spec, now: now, calendar: calendar) else { return nil }
        return WakePlan(alarmId: spec.id, windowStart: windowStart(target, windowMinutes: spec.windowMinutes), target: target)
    }

    public static func earliestPlan(_ specs: [AlarmSpec], now: Date, calendar: Calendar) -> WakePlan? {
        earliestPlan(specs, now: now, doneUntil: [:], calendar: calendar)
    }

    /// Wie oben, aber pro Wecker erst ab `doneUntil[id]` (erledigte bzw. ausgesetzte Nacht).
    /// Ein zweiter Wecker am selben Morgen bleibt davon unberührt.
    public static func earliestPlan(_ specs: [AlarmSpec], now: Date, doneUntil: [String: Date], calendar: Calendar) -> WakePlan? {
        specs.compactMap { nextPlan($0, now: max(now, doneUntil[$0.id] ?? .distantPast), calendar: calendar) }
            .min { ($0.target, $0.alarmId) < ($1.target, $1.alarmId) }
    }

    /// Einschlafzeit plus Schlafdauer, nie später als `latest`.
    public static func sleepTarget(onset: Date, sleepDurationMinutes: Int, latest: Date) -> Date {
        min(onset.addingTimeInterval(TimeInterval(sleepDurationMinutes * 60)), latest)
    }

    public static func windowStart(_ target: Date, windowMinutes: Int) -> Date {
        target.addingTimeInterval(-TimeInterval(windowMinutes * 60))
    }

    /// Wie `ZonedDateTime.of`: In der Lücke verschiebt Foundation um die Lückenlänge nach vorn,
    /// in der Überlappung wählt es die frühere Zeit (beides durch Tests abgesichert).
    private static func resolve(_ parts: DateComponents, calendar: Calendar) -> Date? {
        calendar.date(from: parts)
    }
}

/// Schlummern: 5 Minuten, höchstens 3-mal.
public enum Snooze {
    public static let minutes = 5
    public static let maxCount = 3

    public static func canSnooze(_ count: Int) -> Bool { count < maxCount }

    public static func nextRing(_ now: Date) -> Date { now.addingTimeInterval(TimeInterval(minutes * 60)) }
}

/// Einschlaf-Erkennung (Regel, Abschnitt 6).
public enum SleepOnset {
    public static let stillEpochs = 30

    public static func find(_ motions: [Double?], threshold: Double = DetectorConfig.defaultLightThreshold) -> Int? {
        var runStart = 0
        var runLength = 0
        for (index, motion) in motions.enumerated() {
            if let motion, motion < threshold {
                if runLength == 0 { runStart = index }
                runLength += 1
                if runLength == stillEpochs { return runStart }
            } else {
                runLength = 0
            }
        }
        return nil
    }
}
