import Observation
import PhaseWakeCore
import SwiftUI

/// Zustand der iPhone-App: Wecker, Verlauf, Abgleich mit der Uhr.
@MainActor
@Observable
final class PhoneModel {
    static let shared = PhoneModel()

    let storage = PhaseWakeStorage()
    let backup = BackupAlarm()
    let health = HealthSleep()
    private(set) var alarms: [AlarmSpec] = []
    private(set) var nights: [NightRecord] = []
    let bedtime = BedtimeReminder()
    let sounds = SoundPlayer()
    /// Schlafnotizen je Nachtdatum und eigene Notizen (nur iPhone, nicht an die Uhr).
    private(set) var notes: [String: [String]] = [:]
    private(set) var customNotes: [CustomNote] = []
    /// Schlafziel in Minuten (360–600, Standard 450).
    private(set) var sleepGoal: Int = SleepGoal.defaultMinutes
    /// Bettzeit-Erinnerung, Standard aus.
    private(set) var bedtimeReminder = false
    private static let sleepGoalKey = "sleepGoalMinutes"
    private static let bedtimeKey = "bedtimeReminder"
    /// Zeitpunkt, zu dem die Uhr zuletzt geweckt hat – der Nachttisch-Modus holt dann das Licht nach.
    private(set) var lastRing: Date?
    /// Zielzeit, für die die Apple Watch scharf ist (laut Uhr); `nil` = nicht scharf oder unbekannt.
    private(set) var watchArmedTarget: Date?
    /// Akkustand der Apple Watch in Prozent, wie zuletzt gemeldet, und Zeitpunkt der Messung.
    private var watchBatteryLevel: (percent: Int, at: Date)?

    /// Höchstens so viele Wecker (wie Android): Die Uhr führt ohnehin nur die nächste Nacht.
    static let maxAlarms = 8
    var canAddAlarm: Bool { alarms.count < Self.maxAlarms }

    private init() {
        alarms = storage.snapshot().alarms
        nights = storage.nights()
        notes = storage.notes()
        customNotes = storage.customNotes()
        let goal = UserDefaults.standard.integer(forKey: Self.sleepGoalKey)
        sleepGoal = goal == 0 ? SleepGoal.defaultMinutes : SleepGoal.clamp(goal)
        bedtimeReminder = UserDefaults.standard.bool(forKey: Self.bedtimeKey)
        // Zeitzone oder Uhrzeit geändert (Reise): Die AlarmKit-Wecker stehen auf festen Zeitpunkten, also neu planen.
        let names: [Notification.Name] = [.NSSystemTimeZoneDidChange, .NSSystemClockDidChange, UIApplication.significantTimeChangeNotification]
        for name in names {
            _ = NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                Task { @MainActor in
                    await PhoneModel.shared.backup.reschedule(PhoneModel.shared.alarms)
                    await PhoneModel.shared.replanBedtime()
                }
            }
        }
    }

    func upsert(_ spec: AlarmSpec) {
        change { list in
            var list = list
            if let i = list.firstIndex(where: { $0.id == spec.id }) { list[i] = spec } else if list.count < Self.maxAlarms { list.append(spec) }
            return list
        }
    }

    func remove(_ id: String) { change { $0.filter { $0.id != id } } }

    func setEnabled(_ alarm: AlarmSpec, _ enabled: Bool) {
        change { list in list.map { $0.id == alarm.id ? $0.with(enabled: enabled) : $0 } }
    }

    func applyRemote(_ snapshot: AlarmSnapshot) {
        guard storage.applyRemote(snapshot) else { return }
        alarms = snapshot.alarms
        Task { await backup.reschedule(alarms) }
        Task { await replanBedtime() }
    }

    func add(night: NightRecord) {
        storage.add(night)
        nights = storage.nights()
        // Früh geweckt: Die nächste Nacht beginnt jetzt, die Erinnerung richtet sich danach.
        Task { await replanBedtime() }
    }

    func setFeeling(_ feeling: Int, for night: NightRecord) {
        storage.setFeeling(feeling, alarmId: night.alarmId, windowStart: night.windowStart)
        nights = storage.nights()
    }

    func watchRang() { lastRing = Date() }

    /// Am iPhone gestoppt oder geschlummert, während es mit der Uhr klingelt: Die Uhr macht mit (wie Android).
    func phoneRingEnded(snooze: Bool) async {
        await backup.stopRinging()
        if snooze { await backup.snoozeSafety() }
        PhoneConnectivity.shared.send(command: snooze ? SyncKeys.snooze : SyncKeys.stop)
    }

    /// Pro Wecker: Zielzeit einer Nacht, die die Uhr schon abgeschlossen hat, obwohl sie noch vorn liegt
    /// (früh geweckt). Gleich wie `doneUntil` auf der Uhr.
    var doneUntil: [String: Date] {
        let now = Date()
        return nights.filter { $0.target > now }.reduce(into: [:]) { done, night in
            done[night.alarmId] = max(done[night.alarmId] ?? .distantPast, night.target)
        }
    }

    func watchArmed(_ target: Date?) {
        watchArmedTarget = target
    }

    func setWatchBattery(_ level: (percent: Int, at: Date)?) { watchBatteryLevel = level }

    /// Akkustand der Uhr; `nil`, wenn unbekannt oder älter als 6 Stunden.
    func watchBattery(now: Date = Date()) -> Int? {
        guard let level = watchBatteryLevel,
              now.timeIntervalSince(level.at) * 1000 < Double(SyncKeys.maxBatteryAgeMs) else { return nil }
        return level.percent
    }

    func clearHistory() {
        storage.clearNights()
        nights = []
        // Notizen gehören zum Verlauf und gehen mit.
        storage.clearNotes()
        notes = [:]
        PhoneConnectivity.shared.send(command: SyncKeys.clearHistory)
    }

    private func change(_ edit: ([AlarmSpec]) -> [AlarmSpec]) {
        let snapshot = storage.mutate(edit)
        alarms = snapshot.alarms
        PhoneConnectivity.shared.publish(snapshot)
        Task { await backup.reschedule(alarms) }
        Task { await replanBedtime() }
    }

    // MARK: Schlafnotizen

    /// Notizen einer Nacht; unbekannte (gelöschte) eigene Notizen fallen weg.
    func selectedNotes(_ nightDate: String) -> [String] {
        (notes[nightDate] ?? []).filter { id in !SleepNotes.isCustom(id) || customNotes.contains { $0.id == id } }
    }

    func toggleNote(_ id: String, nightDate: String) {
        notes = storage.setNotes(SleepNotes.toggle(id, in: notes[nightDate] ?? []), nightDate: nightDate)
    }

    /// Name zur Kennung: feste Notizen übersetzt, eigene mit ihrem Namen; `nil` = unbekannt.
    func noteName(_ id: String) -> String? {
        if let fixed = SleepNote(rawValue: id) { return L(fixed.textKey) }
        return customNotes.first { $0.id == id }?.name
    }

    var canAddCustomNote: Bool { customNotes.count < SleepNotes.maxCustom }

    func addCustomNote(_ name: String) {
        let note = CustomNote(id: SleepNotes.newCustomId(), name: name)
        guard canAddCustomNote, !note.name.isEmpty else { return }
        setCustom(customNotes + [note])
    }

    func renameCustomNote(_ id: String, _ name: String) {
        let clean = SleepNotes.cleanName(name)
        guard !clean.isEmpty else { return }
        setCustom(customNotes.map { $0.id == id ? CustomNote(id: id, name: clean) : $0 })
    }

    /// Entfernt die Notiz aus der Auswahl; vergangene Nächte behalten die Kennung (zeigen sie aber nicht).
    func deleteCustomNote(_ id: String) { setCustom(customNotes.filter { $0.id != id }) }

    private func setCustom(_ list: [CustomNote]) {
        storage.setCustomNotes(list)
        customNotes = storage.customNotes()
    }

    // MARK: Schlafziel und Bettzeit

    func setSleepGoal(_ minutes: Int) {
        sleepGoal = SleepGoal.clamp(minutes)
        UserDefaults.standard.set(sleepGoal, forKey: Self.sleepGoalKey)
        Task { await replanBedtime() }
    }

    func setBedtimeReminder(_ enabled: Bool) {
        bedtimeReminder = enabled
        UserDefaults.standard.set(enabled, forKey: Self.bedtimeKey)
        Task {
            if enabled { await bedtime.requestPermission() }
            await replanBedtime()
        }
    }

    /// Plant die Bettzeit-Erinnerung neu (Wecker, Ziel, Einstellung, Zeitzone oder neue Nacht geändert).
    func replanBedtime() async {
        await bedtime.reschedule(alarms: alarms, doneUntil: doneUntil, goalMinutes: sleepGoal, enabled: bedtimeReminder)
    }
}

extension SleepNote {
    /// Textschlüssel wie Android («note_caffeine» …).
    var textKey: String { "note_" + rawValue.lowercased() }
}
