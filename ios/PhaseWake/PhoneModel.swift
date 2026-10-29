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
    /// Geräusche in der Nacht (nur auf Wunsch).
    let recorder = SoundRecorder()
    let weekly = WeeklyReview()
    /// Feiertage (Land, Region, Daten) sowie Probewecker und Powernap (Runde 3, eigene Dateien).
    let holidays = HolidayStore()
    let transient = PhoneTransient()
    /// Gewählter Reiter (eine Mitteilung kann den Verlauf öffnen).
    var selectedTab = AppTab.alarms
    /// Geräusche aufnehmen: Vorgabe für neue Nächte, Standard aus.
    private(set) var soundRecDefault = false
    /// Wahl für eine bestimmte Nacht (Nachtdatum, an/aus), falls selbst gewählt oder gestoppt.
    private(set) var soundRecChoice: (night: String, on: Bool)?
    /// Abendroutine: Punkte und Haken je Nachtdatum.
    private(set) var routineItems: [RoutineItem] = Routine.defaults
    private(set) var routineTicks: [String: [String]] = [:]
    /// Wochenrückblick am Sonntag um 18 Uhr, Standard aus.
    private(set) var weeklyReview = false
    // Schlüssel auch für Sichern und Wiederherstellen (`BackupIO`).
    static let soundRecDefaultKey = "soundRecDefault"
    private static let soundRecNightKey = "soundRecNight"
    static let routineItemsKey = "routineItems"
    static let routineTicksKey = "routineTicks"
    static let weeklyKey = "weeklyReview"
    /// Schlafnotizen je Nachtdatum und eigene Notizen (nur iPhone, nicht an die Uhr).
    private(set) var notes: [String: [String]] = [:]
    private(set) var customNotes: [CustomNote] = []
    /// Schlafziel in Minuten (360–600, Standard 450).
    private(set) var sleepGoal: Int = SleepGoal.defaultMinutes
    /// Bettzeit-Erinnerung, Standard aus.
    private(set) var bedtimeReminder = false
    static let sleepGoalKey = "sleepGoalMinutes"
    static let bedtimeKey = "bedtimeReminder"
    /// Zeitpunkt, zu dem die Uhr zuletzt geweckt hat – der Nachttisch-Modus holt dann das Licht nach.
    private(set) var lastRing: Date?
    /// Zielzeit, für die die Apple Watch scharf ist (laut Uhr); `nil` = nicht scharf oder unbekannt.
    private(set) var watchArmedTarget: Date?
    /// Felder je Weckerzeile, die die Uhr kennt (`Codec.alarmFields`); `nil` = noch kein Stand der Uhr, 0 = ältere Uhr.
    private(set) var watchAlarmFields: Int?
    /// Akkustand der Apple Watch in Prozent, wie zuletzt gemeldet, und Zeitpunkt der Messung.
    private var watchBatteryLevel: (percent: Int, at: Date)?

    /// Höchstens so viele Wecker (wie Android): Die Uhr führt ohnehin nur die nächste Nacht.
    static let maxAlarms = 8
    var canAddAlarm: Bool { alarms.count < Self.maxAlarms }

    private init() {
        loadState()
        holidays.adopt(lastSent: storage.holidays)
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
        // Einschlafklänge prüfen ihr Ende jede Minute gegen den aktuellen Plan.
        sounds.currentWindowStart = { [weak self] in
            guard let self else { return nil }
            return self.nextPlan()?.windowStart
        }
    }

    /// Liest den ganzen Stand aus dem Speicher (beim Start und nach dem Wiederherstellen einer Sicherung).
    func loadState() {
        alarms = storage.snapshot().alarms
        nights = storage.nights()
        notes = storage.notes()
        customNotes = storage.customNotes()
        let defaults = UserDefaults.standard
        let goal = defaults.integer(forKey: Self.sleepGoalKey)
        sleepGoal = goal == 0 ? SleepGoal.defaultMinutes : SleepGoal.clamp(goal)
        bedtimeReminder = defaults.bool(forKey: Self.bedtimeKey)
        soundRecDefault = defaults.bool(forKey: Self.soundRecDefaultKey)
        soundRecChoice = nil
        if let text = defaults.string(forKey: Self.soundRecNightKey) {
            let parts = text.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
            if parts.count == 2, NightDate.isValid(parts[0]) { soundRecChoice = (night: parts[0], on: parts[1] == "1") }
        }
        routineItems = Routine.decodeItems(defaults.string(forKey: Self.routineItemsKey))
        routineTicks = Routine.decodeTicks(defaults.string(forKey: Self.routineTicksKey))
        weeklyReview = defaults.bool(forKey: Self.weeklyKey)
    }

    /// Nächster Plan über alle Wecker: früh geweckte Nächte und Feiertage übersprungen.
    func nextPlan(now: Date = Date()) -> WakePlan? {
        AlarmSchedule.earliestPlan(alarms, now: now, doneUntil: doneUntil, calendar: .current, holidays: holidays.dates)
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
        DiagStore.shared.log(.alarmsReceived, "\(snapshot.alarms.count) alarms from watch")
        Task { await backup.reschedule(alarms) }
        Task { await replanBedtime() }
    }

    func add(night: NightRecord) {
        storage.add(night)
        nights = storage.nights()
        // Früh geweckt: Die nächste Nacht beginnt jetzt, die Erinnerung richtet sich danach.
        Task { await replanBedtime() }
        Task { await replanWeekly() }
    }

    func setFeeling(_ feeling: Int, for night: NightRecord) {
        storage.setFeeling(feeling, alarmId: night.alarmId, windowStart: night.windowStart)
        nights = storage.nights()
        Task { await replanWeekly() }
    }

    /// Die Uhr weckt: Einschlafklänge enden sofort (wie Android), nie das Wecken überdecken.
    func watchRang() {
        lastRing = Date()
        sounds.stop()
    }

    /// Am iPhone gestoppt oder geschlummert, während es mit der Uhr klingelt: Die Uhr macht mit (wie Android).
    func phoneRingEnded(snooze: Bool) async {
        await backup.stopRinging()
        if snooze { await backup.snoozeSafety() }
        PhoneConnectivity.shared.send(command: snooze ? SyncKeys.snooze : SyncKeys.stop)
    }

    /// Pro Wecker erledigt bis: Zielzeit einer Nacht, die die Uhr schon abgeschlossen hat, obwohl sie noch vorn liegt
    /// (früh geweckt, gleich wie `doneUntil` auf der Uhr), oder die Sperre der Sicherung, weil die Uhr die Nacht
    /// übernommen hat (Zielzeit + Nachfrist; wie `BackupAlarm.doneUntilFlow` auf Android). Der spätere Wert gilt.
    var doneUntil: [String: Date] {
        let now = Date()
        let done = nights.filter { $0.target > now }.reduce(into: [String: Date]()) { done, night in
            done[night.alarmId] = max(done[night.alarmId] ?? .distantPast, night.target)
        }
        return done.merging(backup.suppressedUntil().filter { $0.value > now }) { max($0, $1) }
    }

    func watchArmed(_ target: Date?, alarmFields: Int) {
        watchArmedTarget = target
        watchAlarmFields = alarmFields
    }

    /// Ob die Uhr Überspringen und Feiertage beachtet; sonst blendet das iPhone beides aus (Nacht-Check meldet es).
    var watchKnowsSkip: Bool { Codec.watchKnowsSkip(watchAlarmFields) }

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
        // Ebenso die Haken der Abendroutine und die Geräusch-Ausschnitte.
        routineTicks = [:]
        UserDefaults.standard.removeObject(forKey: Self.routineTicksKey)
        recorder.deleteEverything()
        PhoneConnectivity.shared.send(command: SyncKeys.clearHistory)
    }

    private func change(_ edit: ([AlarmSpec]) -> [AlarmSpec]) {
        let snapshot = storage.mutate(edit)
        alarms = snapshot.alarms
        PhoneConnectivity.shared.publish(snapshot, holidays: holidays.list)
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
        await bedtime.reschedule(alarms: alarms, goalMinutes: sleepGoal, enabled: bedtimeReminder, holidays: holidays.dates)
    }
}

// MARK: - Runde 2: Geräusche, Abendroutine, Wochenrückblick

extension PhoneModel {
    /// Ob für diese Nacht aufgenommen werden soll: eigene Wahl, sonst die Vorgabe.
    func soundRecEnabled(night: String) -> Bool {
        if let choice = soundRecChoice, choice.night == night { return choice.on }
        return soundRecDefault
    }

    /// Schalter auf der Startseite: merkt die Wahl für diese Nacht und startet bzw. stoppt die Aufnahme.
    func setSoundRec(_ on: Bool, night: String) async {
        setSoundRecChoice(night: night, on: on)
        if on {
            let started = await recorder.start(nextAlarmTarget: nextAlarmTarget())
            // Mikrofon verweigert: Schalter bleibt aus.
            if !started { setSoundRecChoice(night: night, on: false) }
        } else {
            recorder.stop()
        }
    }

    /// Startet die Aufnahme beim Öffnen der App von selbst (nur mit erteiltem Mikrofon; ein Stopp gilt für
    /// die ganze Nacht). Selbst für diese Nacht eingeschaltet: wieder starten, solange der Abend läuft
    /// (bis zum Weckfenster). Nur die Vorgabe «an»: erst ab der Bettzeit der kommenden Nacht bzw. ohne
    /// Wecker ab 21 Uhr (`SoundEvents.mayAutoStart`); davor nur, wenn der Nutzer selbst einschaltet.
    func autoStartSoundRec(now: Date = Date()) async {
        guard !recorder.running, recorder.permissionGranted else { return }
        let plan = nextPlan(now: now)
        let night = SleepNotes.nightDate(now, calendar: .current)
        if let choice = soundRecChoice, choice.night == night {
            guard choice.on, SleepNotes.entryVisible(now: now, nextWindowStart: plan?.windowStart, calendar: .current) else { return }
        } else {
            guard soundRecDefault,
                  SoundEvents.mayAutoStart(now: now, nextTarget: plan?.target, goalMinutes: sleepGoal, calendar: .current, windowStart: plan?.windowStart) else { return }
        }
        await recorder.start(nextAlarmTarget: plan?.target)
    }

    func setSoundRecDefault(_ on: Bool) async {
        if on, !(await SoundRecorder.requestPermission()) { return }
        soundRecDefault = on
        UserDefaults.standard.set(on, forKey: Self.soundRecDefaultKey)
    }

    private func setSoundRecChoice(night: String, on: Bool) {
        soundRecChoice = (night: night, on: on)
        UserDefaults.standard.set("\(night)|\(on ? "1" : "0")", forKey: Self.soundRecNightKey)
    }

    /// Zielzeit des nächsten Weckers (früh geweckte Nächte übersprungen).
    private func nextAlarmTarget(now: Date = Date()) -> Date? { nextPlan(now: now)?.target }

    // MARK: Abendroutine

    var canAddRoutineItem: Bool { routineItems.count < Routine.maxItems }

    func addRoutineItem(_ text: String) {
        let clean = Routine.cleanText(text)
        guard canAddRoutineItem, !clean.isEmpty else { return }
        setRoutineItems(routineItems + [RoutineItem(id: Routine.customId(UUID().uuidString.lowercased()), text: clean)])
    }

    func removeRoutineItem(_ id: String) { setRoutineItems(routineItems.filter { $0.id != id }) }

    private func setRoutineItems(_ items: [RoutineItem]) {
        let text = Routine.encodeItems(items)
        UserDefaults.standard.set(text, forKey: Self.routineItemsKey)
        routineItems = Routine.decodeItems(text)
    }

    func routineDone(night: String) -> [String] { routineTicks[night] ?? [] }

    func toggleRoutine(_ id: String, night: String) {
        routineTicks = Routine.toggle(routineTicks, date: night, id: id)
        UserDefaults.standard.set(Routine.encodeTicks(routineTicks), forKey: Self.routineTicksKey)
    }

    /// Anzeigename: Vorschläge übersetzt, eigene mit ihrem Text.
    func routineName(_ item: RoutineItem) -> String {
        switch item.id {
        case Routine.phone: L("routine_item_phone")
        case Routine.light: L("routine_item_light")
        case Routine.water: L("routine_item_water")
        default: item.text
        }
    }

    // MARK: Wochenrückblick

    func setWeeklyReview(_ enabled: Bool) {
        weeklyReview = enabled
        UserDefaults.standard.set(enabled, forKey: Self.weeklyKey)
        Task {
            if enabled { await bedtime.requestPermission() }
            await replanWeekly()
        }
    }

    /// Plant die Mitteilung zum nächsten Sonntag 18 Uhr mit dem aktuellen Stand neu.
    func replanWeekly() async {
        await weekly.reschedule(model: self)
    }
}

/// Reiter der App.
enum AppTab: Hashable {
    case alarms, history, settings
}

extension SleepNote {
    /// Textschlüssel wie Android («note_caffeine» …).
    var textKey: String { "note_" + rawValue.lowercased() }
}
