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
    /// Kürzestes Weckfenster in Minuten; darunter bleibt nach der Aufwärmphase kaum Zeit zum Erkennen.
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
    /// Länge des Weckfensters vor der Zielzeit in Minuten (10–30).
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
        sunriseDevice: SunriseDevice = .phone
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
        self.sunriseDevice = sunriseDevice
    }

    /// Ob das iPhone als Zweitwecker mitweckt.
    public var phoneBackup: Bool { phone != .off }

    /// Einmaliger Wecker: wird nach dem Klingeln ausgeschaltet.
    public var isOneShot: Bool { days.isEmpty }

    /// Sonnenaufgang auf dem Handy-Bildschirm; `.off`, wenn er nur auf der Uhr leuchtet (wie Kotlin).
    public var phoneSunrise: Sunrise { sunriseDevice.onPhone ? sunrise : .off }

    /// Sonnenaufgang auf dem Uhr-Bildschirm; `.off`, wenn er nur auf dem Handy leuchtet (wie Kotlin).
    public var watchSunrise: Sunrise { sunriseDevice.onWatch ? sunrise : .off }

    /// Kopie mit geändertem Ein/Aus-Zustand.
    public func with(enabled: Bool) -> AlarmSpec {
        // Werte stammen aus einem gültigen Wecker; die Prüfung kann nicht scheitern.
        try! AlarmSpec(
            id: id, hour: hour, minute: minute, days: days, windowMinutes: windowMinutes,
            enabled: enabled, phone: phone, watchSound: watchSound, sleepDurationMinutes: sleepDurationMinutes,
            sound: sound, label: label, sunrise: sunrise, wakeTask: wakeTask, sunriseDevice: sunriseDevice
        )
    }
}

/// Zeitplan einer Weck-Nacht.
public struct WakePlan: Equatable, Sendable {
    /// Kennung des Weckers, zu dem der Plan gehört.
    public let alarmId: String
    /// Beginn des Weckfensters.
    public let windowStart: Date
    /// Zielzeit: spätester Weckmoment.
    public let target: Date

    /// Erzeugt einen Plan; `windowStart` liegt vor `target`.
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

    /// Plan für die nächste Nacht des Weckers (Zielzeit echt nach `now`), oder `nil`, wenn er aus ist.
    public static func nextPlan(_ spec: AlarmSpec, now: Date, calendar: Calendar) -> WakePlan? {
        guard let target = nextTarget(spec, now: now, calendar: calendar) else { return nil }
        return WakePlan(alarmId: spec.id, windowStart: windowStart(target, windowMinutes: spec.windowMinutes), target: target)
    }

    /// Der früheste Plan über alle Wecker, oder `nil`, wenn keiner aktiv ist. Bei gleicher Zielzeit gewinnt die kleinere Kennung, damit beide Plattformen denselben Wecker wählen.
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

    /// Beginn des Weckfensters: `windowMinutes` Minuten vor `target`.
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
    /// Schlummerdauer in Minuten.
    public static let minutes = 5
    /// Höchstzahl Schlummern pro Weckvorgang; danach nur noch Stoppen, damit niemand den Morgen verschlummert.
    public static let maxCount = 3

    /// Ob nach `count` bisherigen Schlummern noch einmal geschlummert werden darf.
    public static func canSnooze(_ count: Int) -> Bool { count < maxCount }

    /// Zeitpunkt des nächsten Klingelns: `now` plus 5 Minuten.
    public static func nextRing(_ now: Date) -> Date { now.addingTimeInterval(TimeInterval(minutes * 60)) }
}

/// Einschlaf-Erkennung (Regel, Abschnitt 6).
public enum SleepOnset {
    /// 30 Epochen = 15 Minuten durchgehende Ruhe.
    public static let stillEpochs = 30

    /// Sucht die erste ruhige Strecke von `stillEpochs` Epochen.
    /// - Parameters:
    ///   - motions: Bewegung je Epoche seit «Gute Nacht», in zeitlicher Reihenfolge; `nil` (leere Epoche) unterbricht die Strecke.
    ///   - threshold: Leicht-Schwelle in m/s²; ruhig heisst Bewegung vorhanden und darunter.
    /// - Returns: Index der ersten Epoche der ersten ruhigen Strecke, oder `nil`, solange es keine gibt.
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
