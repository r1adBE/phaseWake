import Foundation

/// Schlafnotizen, Befinden zu Nächten aus anderen Apps, Feiertage und Schlafziel von ``PhaseWakeStorage`` (aus
/// `Storage.swift` ausgelagert, Zeilengrenze).
extension PhaseWakeStorage {
    // MARK: Schlafnotizen (nur iPhone, nicht an die Uhr)

    private static let notesKey = "sleepNotes"
    private static let customNotesKey = "customNotes"
    private static let healthFeelingsKey = "healthFeelings"

    /// Notizen je Nachtdatum «yyyy-MM-dd».
    public func notes() -> [String: [String]] {
        lock.lock(); defer { lock.unlock() }
        return SleepNotes.decode(defaults.string(forKey: Self.notesKey) ?? "")
    }

    /// Setzt die Notizen einer Nacht (leer = entfernen) und behält höchstens 90 Nachtdaten. - Returns: neuer Stand.
    @discardableResult
    public func setNotes(_ ids: [String], nightDate: String) -> [String: [String]] {
        lock.lock(); defer { lock.unlock() }
        var all = SleepNotes.decode(defaults.string(forKey: Self.notesKey) ?? "")
        all[nightDate] = SleepNotes.normalize(ids)
        let text = SleepNotes.encode(all)
        defaults.set(text, forKey: Self.notesKey)
        return SleepNotes.decode(text)
    }

    /// Löscht die Notizen aller Nächte und das Befinden zu Nächten aus anderen Apps (mit dem Verlauf); die eigenen
    /// Notizen bleiben zur Auswahl.
    public func clearNotes() {
        lock.lock(); defer { lock.unlock() }
        defaults.removeObject(forKey: Self.notesKey)
        defaults.removeObject(forKey: Self.healthFeelingsKey)
    }

    /// Befinden (1–5) je Nachtdatum zu Nächten aus anderen Apps, z. B. Apple Health (`HealthFeelings`); nie in HealthKit.
    public func healthFeelings() -> [String: Int] {
        lock.lock(); defer { lock.unlock() }
        return HealthFeelings.decode(defaults.string(forKey: Self.healthFeelingsKey) ?? "")
    }

    /// Setzt das Befinden zur Nacht aus einer anderen App mit Nachtdatum `nightDate`. - Returns: neuer Stand.
    @discardableResult
    public func setHealthFeeling(_ feeling: Int, nightDate: String) -> [String: Int] {
        lock.lock(); defer { lock.unlock() }
        let all = HealthFeelings.decode(defaults.string(forKey: Self.healthFeelingsKey) ?? "")
        let text = HealthFeelings.encode(HealthFeelings.set(all, date: nightDate, feeling: feeling))
        defaults.set(text, forKey: Self.healthFeelingsKey)
        return HealthFeelings.decode(text)
    }

    /// Ersetzt das Befinden zu Nächten aus anderen Apps (Wiederherstellen; ältere Sicherung ohne: leer). - Returns: neuer Stand.
    @discardableResult
    public func replaceHealthFeelings(_ feelings: [String: Int]) -> [String: Int] {
        lock.lock(); defer { lock.unlock() }
        let text = HealthFeelings.encode(feelings)
        defaults.set(text, forKey: Self.healthFeelingsKey)
        return HealthFeelings.decode(text)
    }

    /// Ersetzt die Notizen aller Nächte (Wiederherstellen einer Sicherung). - Returns: neuer Stand.
    @discardableResult
    public func replaceNotes(_ notes: [String: [String]]) -> [String: [String]] {
        lock.lock(); defer { lock.unlock() }
        let text = SleepNotes.encode(notes)
        defaults.set(text, forKey: Self.notesKey)
        return SleepNotes.decode(text)
    }

    /// Die eigenen Notizen in gespeicherter Reihenfolge, höchstens 5.
    public func customNotes() -> [CustomNote] {
        lock.lock(); defer { lock.unlock() }
        return SleepNotes.decodeCustom(defaults.string(forKey: Self.customNotesKey) ?? "")
    }

    /// Speichert die eigenen Notizen (höchstens 5). Vergangene Nächte behalten gelöschte Kennungen.
    public func setCustomNotes(_ notes: [CustomNote]) {
        lock.lock(); defer { lock.unlock() }
        defaults.set(SleepNotes.encodeCustom(notes.filter { !$0.name.isEmpty }), forKey: Self.customNotesKey)
    }

    // MARK: Feiertage (Uhr: vom iPhone; iPhone: zuletzt gesendet)

    private static let holidaysKey = "holidays"

    /// Feiertage im Format von `Holidays.encode`; leer, wenn nie empfangen.
    public var holidays: HolidayList {
        get {
            lock.lock(); defer { lock.unlock() }
            return Holidays.decode(defaults.string(forKey: Self.holidaysKey))
        }
        set {
            lock.lock(); defer { lock.unlock() }
            defaults.set(Holidays.encode(newValue), forKey: Self.holidaysKey)
        }
    }

    // MARK: Schlafziel (Uhr: vom iPhone, nur für die Anzeige)

    private static let sleepGoalKey = "sleepGoalMinutes"

    /// Schlafziel in Minuten (mit `SleepGoal.clamp`); `SleepGoal.defaultMinutes`, wenn nie empfangen.
    public var sleepGoal: Int {
        get {
            lock.lock(); defer { lock.unlock() }
            let stored = defaults.integer(forKey: Self.sleepGoalKey)
            return stored == 0 ? SleepGoal.defaultMinutes : SleepGoal.clamp(stored)
        }
        set {
            lock.lock(); defer { lock.unlock() }
            defaults.set(SleepGoal.clamp(newValue), forKey: Self.sleepGoalKey)
        }
    }
}
