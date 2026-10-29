import Foundation

/// Schlafziel in Minuten. Identisch zu `SleepGoal` (Kotlin).
public enum SleepGoal {
    /// Standardziel: 7,5 Stunden.
    public static let defaultMinutes = 450
    /// Kleinstes Ziel: 6 Stunden.
    public static let minMinutes = 360
    /// Grösstes Ziel: 10 Stunden.
    public static let maxMinutes = 600
    /// Raster der Einstellung in Minuten.
    public static let stepMinutes = 15
    /// So viele Nächte zählt der Wochenstatus.
    public static let weekNights = 7

    /// Auf den erlaubten Bereich und das 15-Minuten-Raster gebracht.
    public static func clamp(_ minutes: Int) -> Int {
        let stepped = Int((Double(minutes) / Double(stepMinutes)).rounded()) * stepMinutes
        return min(maxMinutes, max(minMinutes, stepped))
    }

    /// Ob `asleepMinutes` das Ziel erreicht (genau gleich zählt als erreicht).
    public static func reached(asleepMinutes: Int, goalMinutes: Int) -> Bool { asleepMinutes >= goalMinutes }

    /// Wie viele der letzten 7 Nächte (die 7 jüngsten Nachtdaten) das Ziel erreicht haben: (erreicht, gezählt).
    /// Mehrere Einträge zum selben Nachtdatum zählen als eine Nacht mit dem längsten Schlaf. Ohne Daten (0, 0).
    public static func weekStatus(_ nights: [(date: String, asleepMinutes: Int)], goalMinutes: Int) -> (reached: Int, total: Int) {
        var byDate: [String: Int] = [:]
        for night in nights { byDate[night.date] = max(byDate[night.date] ?? 0, night.asleepMinutes) }
        let recent = byDate.keys.sorted().suffix(weekNights)
        let count = recent.filter { reached(asleepMinutes: byDate[$0] ?? 0, goalMinutes: goalMinutes) }.count
        return (count, recent.count)
    }

    /// Nächte aus der Gesundheits-App als (Nachtdatum «yyyy-MM-dd», geschlafene Minuten) für `weekStatus`.
    /// Das Nachtdatum richtet sich nach dem Ende der Nacht (wie Kotlin).
    public static func nightsOf(_ sleeps: [SleepSummary], calendar: Calendar) -> [(date: String, asleepMinutes: Int)] {
        sleeps.map { (date: SleepNotes.nightDate($0.end, calendar: calendar), asleepMinutes: $0.asleepMinutes) }
    }
}

/// Bettzeit vor dem nächsten Wecker: Bettzeit = Zielzeit des nächsten Weckers − Schlafziel; erinnert
/// `leadMinutes` Minuten davor. Beim Einschlaf-Modus gilt die eingestellte (späteste) Weckzeit. Identisch zu Kotlin.
public enum Bedtime {
    /// Erinnerung so viele Minuten vor der Bettzeit.
    public static let leadMinutes = 30

    /// Wie oft höchstens zur nächsten Nacht weitergesucht wird (Wecker mehrmals am Morgen, verpasste Erinnerungen).
    private static let maxSteps = 32

    /// Eine geplante Erinnerung für die Nacht vor `target`.
    public struct Reminder: Equatable, Sendable {
        /// Zeitpunkt der Erinnerung (`bedtime` minus Vorlauf).
        public let at: Date
        /// Bettzeit: Zielzeit minus Schlafziel.
        public let bedtime: Date
        /// Zielzeit des Weckers, zu dem die Erinnerung gehört.
        public let target: Date

        /// Erzeugt eine Erinnerung; `at` liegt vor `bedtime`, diese vor `target`.
        public init(at: Date, bedtime: Date, target: Date) {
            self.at = at
            self.bedtime = bedtime
            self.target = target
        }
    }

    /// Zielzeit des nächsten eingeschalteten Weckers minus Schlafziel, als feste Dauer (bei einer
    /// Zeitumstellung in der Nacht bleibt die Schlafdauer gleich, die Uhrzeit verschiebt sich).
    public static func bedtime(nextAlarmTarget: Date, goalMinutes: Int) -> Date {
        nextAlarmTarget.addingTimeInterval(-TimeInterval(goalMinutes * 60))
    }

    /// Zeitpunkt der Bettzeit-Erinnerung: `leadMinutes` Minuten vor der Bettzeit.
    public static func reminderAt(nextAlarmTarget: Date, goalMinutes: Int, leadMinutes: Int = Bedtime.leadMinutes) -> Date {
        bedtime(nextAlarmTarget: nextAlarmTarget, goalMinutes: goalMinutes).addingTimeInterval(-TimeInterval(leadMinutes * 60))
    }

    /// Nächste Erinnerung, die echt nach `from` liegt: zum frühesten Wecker nach `from`; liegt dessen
    /// Erinnerung schon zurück, gilt diese Nacht als verpasst und es geht ab seiner Zielzeit weiter
    /// (so erinnert ein zweiter Wecker am selben Morgen nicht nachträglich). `nil` ohne Wecker.
    /// - Parameters:
    ///   - from: Jetzt, oder nach einer gezeigten bzw. geplanten Erinnerung deren Zeitpunkt.
    ///   - holidays: Feiertage «yyyy-MM-dd» für Wecker mit `skipHolidays` (wie `AlarmSchedule`).
    public static func next(
        _ specs: [AlarmSpec],
        from: Date,
        calendar: Calendar,
        goalMinutes: Int,
        leadMinutes: Int = Bedtime.leadMinutes,
        holidays: Set<String> = []
    ) -> Reminder? {
        var cursor = from
        var remaining = specs
        for _ in 0..<maxSteps {
            guard let plan = AlarmSchedule.earliestPlan(remaining, now: cursor, calendar: calendar, holidays: holidays) else { return nil }
            let at = reminderAt(nextAlarmTarget: plan.target, goalMinutes: goalMinutes, leadMinutes: leadMinutes)
            if at > cursor { return Reminder(at: at, bedtime: bedtime(nextAlarmTarget: plan.target, goalMinutes: goalMinutes), target: plan.target) }
            // Ein Einmal-Wecker klingelt nur diese eine Nacht; er kommt danach nicht wieder.
            remaining.removeAll { $0.id == plan.alarmId && $0.isOneShot }
            cursor = plan.target
        }
        return nil
    }
}
