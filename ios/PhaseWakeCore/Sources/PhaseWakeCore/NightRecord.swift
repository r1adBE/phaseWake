import Foundation

/// Eine aufgezeichnete Weck-Nacht. Identisch zu `NightRecord` (Kotlin).
public struct NightRecord: Equatable, Sendable {
    /// Kennung des Weckers dieser Nacht.
    public let alarmId: String
    /// Beginn des Weckfensters.
    public let windowStart: Date
    /// Zielzeit (spätester Weckmoment).
    public let target: Date
    /// Zeitpunkt des ersten Weckens (vor dem Schlummern).
    public let wokeAt: Date
    /// Warum geweckt wurde.
    public let reason: WakeReason
    /// Wie oft geschlummert wurde (0 bis `Snooze.maxCount`).
    public let snoozes: Int
    /// Gemessene Epochen ab Fensterbeginn (30 s je Epoche), einschliesslich Aufwärmphase.
    public let epochs: [Epoch]
    /// Befinden am Morgen, 1 (müde) bis 5 (erholt); `nil` = nicht angegeben.
    public let feeling: Int?
    /// Akkustand der Uhr in dieser Nacht (``BatteryTrack``); `nil` = nicht gemessen (z. B. ältere Uhr-App).
    public let watchBattery: [BatteryPoint]?
    /// Akkustand des iPhones in dieser Nacht (``BatteryTrack``), nur auf dem iPhone; `nil` = nicht gemessen.
    public let phoneBattery: [BatteryPoint]?
    /// Beginn der Einschlafdauer dieser Nacht (Bettzeit der automatischen Messung bzw. «Gute Nacht»), von der Uhr
    /// gemessen; `nil` = unbekannt (nicht gemessen, ältere Uhr-App).
    public let bedAt: Date?
    /// Einschlafzeit dieser Nacht aus der Einschlaf-Messung der Uhr; `nil` = unbekannt.
    public let asleepAt: Date?

    /// Erlaubte Werte des Befindens.
    public static let feelingRange = 1...5

    /// Ein Befinden ausserhalb 1–5 wird verworfen (Kotlin lehnt es ab; der Codec prüft vorher).
    public init(alarmId: String, windowStart: Date, target: Date, wokeAt: Date, reason: WakeReason, snoozes: Int,
                epochs: [Epoch], feeling: Int? = nil,
                watchBattery: [BatteryPoint]? = nil, phoneBattery: [BatteryPoint]? = nil, bedAt: Date? = nil,
                asleepAt: Date? = nil) {
        self.alarmId = alarmId
        self.windowStart = windowStart
        self.target = target
        self.wokeAt = wokeAt
        self.reason = reason
        self.snoozes = snoozes
        self.epochs = epochs
        self.feeling = feeling.flatMap { Self.feelingRange.contains($0) ? $0 : nil }
        self.watchBattery = watchBattery
        self.phoneBattery = phoneBattery
        self.bedAt = bedAt
        self.asleepAt = asleepAt
    }

    /// Kopie mit Befinden (Akku und Einschlafen bleiben).
    public func with(feeling: Int?) -> NightRecord {
        NightRecord(alarmId: alarmId, windowStart: windowStart, target: target, wokeAt: wokeAt, reason: reason,
                    snoozes: snoozes, epochs: epochs,
                    feeling: feeling, watchBattery: watchBattery, phoneBattery: phoneBattery, bedAt: bedAt,
                    asleepAt: asleepAt)
    }

    /// Kopie mit dem Akku des iPhones (wie Kotlin `copy(phoneBattery = …)`).
    public func with(phoneBattery: [BatteryPoint]?) -> NightRecord {
        NightRecord(alarmId: alarmId, windowStart: windowStart, target: target, wokeAt: wokeAt, reason: reason,
                    snoozes: snoozes, epochs: epochs,
                    feeling: feeling, watchBattery: watchBattery, phoneBattery: phoneBattery, bedAt: bedAt,
                    asleepAt: asleepAt)
    }

    /// Kopie mit Beginn der Einschlafdauer und Einschlafzeit (wie Kotlin `copy(bedAt = …, asleepAt = …)`).
    public func with(bedAt: Date?, asleepAt: Date?) -> NightRecord {
        NightRecord(alarmId: alarmId, windowStart: windowStart, target: target, wokeAt: wokeAt, reason: reason,
                    snoozes: snoozes, epochs: epochs,
                    feeling: feeling, watchBattery: watchBattery, phoneBattery: phoneBattery, bedAt: bedAt,
                    asleepAt: asleepAt)
    }

    /// Ganze Minuten vor der Zielzeit (abgerundet, nie negativ), wie `Duration.toMinutes()`.
    public var minutesBeforeTarget: Int { max(0, Int(target.timeIntervalSince(wokeAt) / 60)) }

    /// Ganze Minuten nach der Zielzeit (abgerundet; 0, wenn nicht zu spät geweckt), wie `Duration.toMinutes()`.
    public var minutesAfterTarget: Int { max(0, Int(wokeAt.timeIntervalSince(target) / 60)) }
}

/// Wie viele eigene Nächte ein Gerät behält: höchstens `maxNights`, mit `maxAge` (Sekunden) nur die, deren Wecken
/// (`wokeAt`) höchstens so lange vor jetzt liegt (genau `maxAge`: bleibt). Identisch zu `NightKeep` (Kotlin).
public struct NightKeep: Sendable {
    /// Höchstzahl Nächte.
    public let maxNights: Int
    /// Höchstalter in Sekunden ab dem Wecken; `nil` = ohne Altersgrenze.
    public let maxAge: TimeInterval?

    /// Grenze aus Höchstzahl und Höchstalter (Sekunden, `nil` = ohne).
    public init(maxNights: Int, maxAge: TimeInterval?) {
        self.maxNights = maxNights
        self.maxAge = maxAge
    }

    /// Die behaltenen Nächte nach Fensterbeginn sortiert (älteste zuerst); ältere fallen beim Speichern weg.
    public func trim(_ nights: [NightRecord], now: Date) -> [NightRecord] {
        let from = maxAge.map { now.addingTimeInterval(-$0) }
        let kept = nights.filter { night in from.map { night.wokeAt >= $0 } ?? true }
        return Array(kept.sorted { $0.windowStart < $1.windowStart }.suffix(maxNights))
    }

    /// iPhone: der Verlauf reicht ``NightHistory/days`` Tage (2 Jahre) zurück, höchstens
    /// ``NightHistory/phoneNights`` Nächte (Nächte mit zwei Weckern).
    public static let phone = NightKeep(maxNights: NightHistory.phoneNights, maxAge: Double(NightHistory.days) * 86_400)

    /// Uhr: wie bisher die jüngsten ``NightHistory/watchNights`` Nächte, ohne Altersgrenze.
    public static let watch = NightKeep(maxNights: NightHistory.watchNights, maxAge: nil)
}

/// Aufbewahrung und CSV-Export. Identisch zu `NightHistory` (Kotlin).
public enum NightHistory {
    /// So viele Tage reicht der Verlauf auf dem iPhone zurück (2 Jahre), eigene Nächte wie Gesundheitsdaten.
    public static let days = 730

    /// Höchstzahl eigener Nächte auf dem iPhone (``NightKeep/phone``): ``days`` plus rund 10 % für Nächte mit zwei
    /// Weckern oder Probeweckern. Begrenzt nur die Dateigrösse, im Alltag gilt die Grenze von ``days`` Tagen.
    public static let phoneNights = 800

    /// So viele Nächte behält die Uhr (``NightKeep/watch``).
    public static let watchNights = 90

    /// Kennzahlen (Übersicht, Diagramme, Trends, Wirkung der Notizen, Wochenrückblick) rechnen über die jüngsten so
    /// vielen eigenen Nächte (``latest(_:count:)``) und die Gesundheitsdaten der letzten so vielen Tage
    /// (`HealthHistory.forInsights`), unabhängig davon, wie weit der Verlauf zurückreicht.
    public static let insightNights = 90

    /// Höchstzahl Nachtdaten für Notizen, Befinden und Haken: so weit wie der Verlauf (``days`` und heute).
    public static let maxDates = days + 1

    /// Die jüngsten `count` Nächte nach Fensterbeginn, älteste zuerst.
    public static func latest(_ nights: [NightRecord], count: Int = insightNights) -> [NightRecord] {
        Array(nights.sorted { $0.windowStart < $1.windowStart }.suffix(count))
    }

    /// Anteil der Nächte in Prozent (gerundet), in denen im leichten Schlaf geweckt wurde. Es zählen nur
    /// Nächte, in denen früher wecken erlaubt war: «Genau» (``WakeReason/exact``) und kurze Nächte
    /// (``WakeReason/shortNight``) fehlen auch im Nenner. `nil`, wenn keine solche Nacht da ist.
    public static func lightShare(_ nights: [NightRecord]) -> Int? {
        let counted = nights.filter { $0.reason != .exact && $0.reason != .shortNight }
        guard !counted.isEmpty else { return nil }
        let light = counted.filter { $0.reason == .lightSleep }.count
        // Wie `Math.round` in Kotlin (nur positive Werte).
        return Int((100 * Double(light) / Double(counted.count)).rounded())
    }

    /// Bewegung je Epoche der jüngsten ``watchNights`` Nächte, älteste zuerst, als Eingabe für ``Calibration``.
    public static func motionsForCalibration(_ nights: [NightRecord]) -> [[Double?]] {
        latest(nights, count: watchNights).map { $0.epochs.map(\.motion) }
    }

    /// CSV-Export aller übergebenen Nächte (der ganze Verlauf, 1 = älteste) ohne Kennungen und Datumsangaben, aber mit
    /// Bewegungs- und Pulsreihen (nicht anonym im engeren Sinn): gleiche Spalten und Zahlenformate wie Android. Die
    /// Spalte `notes` steht zuletzt, damit ältere Leser weiter funktionieren: Notizen je Nachtdatum (siehe
    /// `SleepNotes`), mit «;» getrennt, eigene nur als «custom».
    public static func exportCsv(_ nights: [NightRecord], notes: [String: [String]] = [:],
                                 calendar: Calendar = .current) -> String {
        var out = "night,window_minutes,reason,woke_minutes_before_target,snoozes,feeling,epoch,motion,hr,notes\n"
        for (nightIndex, night) in nights.sorted(by: { $0.windowStart < $1.windowStart }).enumerated() {
            let windowMinutes = Int(night.target.timeIntervalSince(night.windowStart) / 60)
            let noteValue = SleepNotes.csvValue(notes[SleepNotes.nightDate(of: night, calendar: calendar)] ?? [])
            for (epochIndex, epoch) in night.epochs.enumerated() {
                let motion = epoch.motion.map { CsvNumbers.fixed($0, decimals: 6) } ?? ""
                let hr = epoch.heartRate.map { CsvNumbers.fixed($0, decimals: 1) } ?? ""
                out += "\(nightIndex + 1),\(windowMinutes),\(night.reason.rawValue),\(night.minutesBeforeTarget),"
                    + "\(night.snoozes),\(night.feeling.map(String.init) ?? ""),\(epochIndex),"
                    + "\(motion),\(hr),\(noteValue)\n"
            }
        }
        return out
    }
}
