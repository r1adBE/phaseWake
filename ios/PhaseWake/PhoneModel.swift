import Observation
import PhaseWakeCore
import SwiftUI

/// Zustand der iPhone-App: Wecker, Verlauf, Abgleich mit der Uhr.
@MainActor
@Observable
final class PhoneModel {
    static let shared = PhoneModel()

    /// Wecker (`SettingsFile.phone`) und Nächte im von der Sicherung ausgenommenen Ordner (`PrivateFiles`).
    let storage = PhaseWakeStorage(defaults: SettingsFile.phone, directory: PrivateFiles.adopt(["nights.v1"]))
    /// Notizen und Befinden, ebenfalls ausgenommen.
    let privateNotes = PrivateNotes()
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
    /// Setzer intern statt privat (wie die übrigen Werte der Runde 2): Die Erweiterung in `PhoneModel+Round2.swift` setzt sie.
    var soundRecDefault = false
    /// Wahl für eine bestimmte Nacht (Nachtdatum, an/aus), falls selbst gewählt oder gestoppt.
    var soundRecChoice: (night: String, on: Bool)?
    /// Abendroutine: Punkte und Haken je Nachtdatum (in `privateNotes`, nicht in der iCloud-Sicherung).
    var routineItems: [RoutineItem] = Routine.defaults
    var routineTicks: [String: [String]] = [:]
    /// Wochenrückblick am Sonntag um 18 Uhr, Standard aus.
    var weeklyReview = false
    // Schlüssel auch für Sichern und Wiederherstellen (`BackupIO`).
    static let soundRecDefaultKey = "soundRecDefault"
    static let soundRecNightKey = "soundRecNight"
    static let weeklyKey = "weeklyReview"
    /// «Verlauf löschen» (ms seit 1970): Nächte aus Apple Health, die nicht danach enden, bleiben ausgeblendet.
    private(set) var historyClearedAt: Date?
    static let historyClearedKey = "historyClearedAt"
    /// Schlafnotizen je Nachtdatum und eigene Notizen (nur iPhone, nicht an die Uhr).
    private(set) var notes: [String: [String]] = [:]
    private(set) var customNotes: [CustomNote] = []
    /// Befinden (1–5) je Nachtdatum zu Nächten aus anderen Apps; nur bei Phase Wake gespeichert, nie in HealthKit.
    private(set) var healthFeelings: [String: Int] = [:]
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
    /// Akkustand der Apple Watch in Prozent, wie zuletzt gemeldet, und Zeitpunkt der Messung.
    private var watchBatteryLevel: (percent: Int, at: Date)?
    /// Stand der Verbindung, den die Uhr-App meldet (`WatchProtocol`); `nil` = noch kein Kontext der Uhr.
    private(set) var watchProtocol: Int?
    /// Build-Nummer der Uhr-App (``BuildNumber``); `nil` = unbekannt (ältere Uhr, lokaler Build).
    private(set) var watchBuild: Int?
    /// Erlaubnis für Mitteilungen auf der Uhr (Rückfall), wie zuletzt gemeldet, und wann die Uhr sie geprüft hat (`nil`: ältere Uhr); `nil` = unbekannt.
    private(set) var watchNotifications: (state: WatchNotifications, checkedAt: Date?)?

    /// Ob `loadState` den gespeicherten Stand lesen konnte. Vor dem ersten Entsperren nach einem Neustart nicht (Datei
    /// geschützt): Dann sind Wecker und Einstellungen hier leer, und die App plant und löscht nichts daraus, bis
    /// `catchUpState` sie gelesen hat (Tiefenaudit AP3-1).
    private(set) var stateLoaded = false

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
        // Erstes Entsperren nach einem Neustart: Stand nachlesen und alles planen (Tiefenaudit AP3-1). Läuft die App
        // dann nicht, holt es das nächste Öffnen bzw. Neuplanen nach (`catchUpState`).
        _ = NotificationCenter.default.addObserver(forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil,
                                                   queue: .main) { _ in
            Task { @MainActor in PhoneModel.shared.catchUpState() }
        }
        // Akku je Nacht: Ladebeginn und -ende, solange die App läuft (r1AD 14.11.2026).
        PhoneBattery.listen()
        // Einschlafklänge prüfen ihr Ende jede Minute gegen den aktuellen Plan.
        sounds.currentWindowStart = { [weak self] in
            guard let self else { return nil }
            return self.nextPlan()?.windowStart
        }
    }

    /// Liest den ganzen Stand aus dem Speicher (beim Start und nach dem Wiederherstellen einer Sicherung).
    func loadState() {
        // Zuerst: Ist die Datei nicht lesbar, ist alles Folgende leer (AP3-1).
        stateLoaded = SettingsFile.phone.isReadable
        alarms = storage.snapshot().alarms
        nights = storage.nights()
        notes = privateNotes.notes()
        customNotes = privateNotes.customNotes()
        healthFeelings = privateNotes.healthFeelings()
        let defaults = SettingsFile.phone
        let goal = defaults.integer(forKey: Self.sleepGoalKey)
        sleepGoal = goal == 0 ? SleepGoal.defaultMinutes : SleepGoal.clamp(goal)
        bedtimeReminder = defaults.bool(forKey: Self.bedtimeKey)
        soundRecDefault = defaults.bool(forKey: Self.soundRecDefaultKey)
        soundRecChoice = nil
        if let text = defaults.string(forKey: Self.soundRecNightKey) {
            let parts = text.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
            if parts.count == 2, NightDate.isValid(parts[0]) { soundRecChoice = (night: parts[0], on: parts[1] == "1") }
        }
        routineItems = Routine.decodeItems(privateNotes.routineItemsText())
        routineTicks = privateNotes.routineTicks()
        weeklyReview = defaults.bool(forKey: Self.weeklyKey)
        // Höchstens jetzt (Vollaudit DA-1), gleich so gespeichert, damit ein späterer Wert nicht mitwandert.
        let stored = (defaults.object(forKey: Self.historyClearedKey) as? NSNumber)?.int64Value, cleared = HistoryClear.clamped(ms: stored, now: Date())
        if cleared != stored { defaults.set(cleared.map { NSNumber(value: $0) }, forKey: Self.historyClearedKey) }
        historyClearedAt = cleared.map { Date(timeIntervalSince1970: Double($0) / 1000) }
    }

    /// Nächster Plan über alle Wecker: früh geweckte Nächte und Feiertage übersprungen; «Gute Nacht» der Uhr verlegt die Zielzeit vor (`WatchNight`).
    func nextPlan(now: Date = Date()) -> WakePlan? {
        WatchNight.shared.apply(AlarmSchedule.earliestPlan(alarms, now: now, doneUntil: doneUntil, calendar: .gregorianLocal, holidays: holidays.dates), now: now)
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
        // Stand noch nicht gelesen (vor dem ersten Entsperren, AP3-1): nicht auf den leeren Stand anwenden, sondern
        // merken; `catchUpState` wendet es danach an (`applyPendingRemote`).
        guard stateLoaded || catchUpState() else {
            pendingRemote.append(snapshot)
            DiagStore.shared.log(.alarmsReceived, "alarms from watch kept until settings readable")
            return
        }
        // Derselbe Kontext nicht zweimal (Fix S-5): Er kommt bei jedem Start erneut.
        guard storage.applyRemoteContext(snapshot) else { return }
        alarms = storage.snapshot().alarms
        DiagStore.shared.log(.alarmsReceived, "\(alarms.count) alarms from watch")
        Task { await backup.reschedule(alarms) }
        Task { await replanBedtime() }
    }

    /// Von der Uhr empfangene Wecker, solange der Stand nicht gelesen ist (AP3-1); in der Reihenfolge des Empfangs.
    @ObservationIgnored private var pendingRemote: [AlarmSnapshot] = []

    /// Nach dem Lesen des Stands (`catchUpState`): gemerkte Wecker der Uhr anwenden.
    func applyPendingRemote() {
        guard stateLoaded else { return }
        let queued = pendingRemote
        pendingRemote = []
        queued.forEach(applyRemote)
    }

    /// Von der Uhr gemeldete Nächte, solange Stand oder Verlauf nicht lesbar sind (vor dem ersten Entsperren); in der
    /// Reihenfolge des Empfangs. `catchUpState` schreibt sie danach (Prüfrunde 2, `applyPendingNights`).
    @ObservationIgnored private var pendingNights: [NightRecord] = []

    /// Nach dem Lesen des Stands: gemerkte Nächte speichern, erst jetzt gegen «Verlauf löschen» geprüft (der Zeitpunkt
    /// steht im Stand, vorher galt er als unbekannt).
    func applyPendingNights() {
        guard stateLoaded, !pendingNights.isEmpty, storage.nightsReadable else { return }
        let queued = pendingNights
        pendingNights = []
        for night in queued where HistoryClear.keeps(end: night.wokeAt, clearedAt: historyClearedAt) { add(night: night) }
    }

    func add(night: NightRecord) {
        // Vor dem ersten Entsperren: nicht verlieren, sondern merken (Prüfrunde 2, `add(night:)` wie die Wecker der Uhr).
        guard stateLoaded || catchUpState(), storage.nightsReadable else {
            pendingNights.append(night)
            DiagStore.shared.log(.suppressed, "night kept until history readable")
            return
        }
        storage.add(night)
        nights = storage.nights()
        PhoneWidgetSync.publishNight(self)
        // Früh geweckt: Die nächste Nacht beginnt jetzt, die Erinnerung richtet sich danach.
        Task { await replanBedtime() }
        Task { await replanWeekly() }
    }

    func setFeeling(_ feeling: Int, for night: NightRecord) {
        storage.setFeeling(feeling, alarmId: night.alarmId, windowStart: night.windowStart)
        nights = storage.nights()
        PhoneWidgetSync.publishNight(self)
        Task { await replanWeekly() }
    }

    /// Befinden zur Nacht aus einer anderen App (Nachtdatum `nightDate`), nur bei Phase Wake gespeichert.
    func setHealthFeeling(_ feeling: Int, nightDate: String) {
        healthFeelings = privateNotes.setHealthFeeling(feeling, nightDate: nightDate)
        Task { await replanWeekly() }
    }

    /// Die Uhr weckt: Einschlafklänge enden sofort (wie Android), nie das Wecken überdecken.
    func watchRang() {
        lastRing = Date()
        sounds.stop()
    }

    /// Am iPhone gestoppt oder geschlummert, während es mit der Uhr klingelt: Die Uhr macht mit (wie Android).
    func phoneRingEnded(snooze: Bool) async {
        let night = backup.currentRingNight
        await backup.stopRinging()
        if snooze { await backup.snoozeSafety(night: night) }
        // Mit der Nacht: Die Uhr folgt nur, wenn sie dieselbe Nacht weckt (nicht etwa schon einen zweiten Wecker).
        PhoneConnectivity.shared.send(command: snooze ? SyncKeys.snooze : SyncKeys.stop, night: night)
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

    func watchArmed(_ target: Date?) {
        watchArmedTarget = target
    }

    func setWatchStatus(battery: (percent: Int, at: Date)?, version: Int?, notifications: (state: WatchNotifications, checkedAt: Date?)?,
                        charging: Bool = false, build: Int? = nil) {
        (watchBatteryLevel, watchProtocol, watchNotifications, watchBatteryCharging) = (battery, version, notifications, battery != nil && charging)
        watchBuild = build
    }

    /// Ob die Uhr beim gemeldeten Akkustand lud (Prüfrunde 2 F-6): Dann misst sie trotz wenig Akku (Nacht-Check O-05).
    private(set) var watchBatteryCharging = false

    /// Akkustand der Uhr; `nil`, wenn unbekannt oder älter als 6 Stunden.
    func watchBattery(now: Date = Date()) -> Int? {
        guard let level = watchBatteryLevel,
              now.timeIntervalSince(level.at) * 1000 < Double(SyncKeys.maxBatteryAgeMs) else { return nil }
        return level.percent
    }

    func clearHistory() {
        storage.clearNights()
        nights = []
        // Notizen und das Befinden zu Nächten aus anderen Apps gehören zum Verlauf und gehen mit.
        privateNotes.clearNotes()
        notes = [:]
        healthFeelings = [:]
        // Ebenso die Haken der Abendroutine und die Geräusch-Ausschnitte.
        routineTicks = [:]
        privateNotes.setRoutineTicks([:])
        recorder.deleteEverything()
        PhoneLearning.shared.restart()
        // Nächte aus Apple Health lassen sich nicht löschen, nur ausblenden (`HealthHistory.visible`).
        let now = Date()
        historyClearedAt = now
        SettingsFile.phone.set(NSNumber(value: TimeBounds.millis(now)), forKey: Self.historyClearedKey)
        // Geteilte CSV-Dateien in den temporären Dateien gleich mit (Vollaudit DA-5).
        NightsExport.cleanUp()
        PhoneConnectivity.shared.send(command: SyncKeys.clearHistory)
    }

    /// Auto-Aus eines Einmal-Weckers nach seiner Nacht (Fix G-1/S-1): nur, wenn der gespeicherte Wecker noch dieselbe
    /// Uhrzeit wie die geklingelte Fassung `rung` hat (``AlarmStamps/rang(_:rung:target:calendar:shifted:)``; die Zielzeit
    /// zählt dann nicht). Zeitpunkt der bisherigen Fassung + 1 ms: Eine neuere Änderung auf der Uhr gewinnt beim Abgleich.
    func disableAfterRing(_ rung: AlarmSpec) {
        guard let snapshot = storage.disableAfterRing(rung.id, rang: {
            AlarmStamps.rang($0, rung: rung, target: .distantPast, calendar: .gregorianLocal, shifted: false)
        }) else { return }
        alarms = snapshot.alarms
        publishToWatch(snapshot)
        Task { await backup.reschedule(alarms) }
        Task { await replanBedtime() }
    }

    private func change(_ edit: ([AlarmSpec]) -> [AlarmSpec]) {
        // Unlesbare gespeicherte Wecker: keine Änderung, nichts an die Uhr (Fix KN-5).
        guard let snapshot = storage.mutate(edit) else { return }
        alarms = snapshot.alarms
        publishToWatch(snapshot)
        Task { await backup.reschedule(alarms) }
        Task { await replanBedtime() }
    }

    // MARK: Schlafnotizen

    /// Notizen einer Nacht; unbekannte (gelöschte) eigene Notizen fallen weg.
    func selectedNotes(_ nightDate: String) -> [String] {
        (notes[nightDate] ?? []).filter { id in !SleepNotes.isCustom(id) || customNotes.contains { $0.id == id } }
    }

    func toggleNote(_ id: String, nightDate: String) {
        notes = privateNotes.setNotes(SleepNotes.toggle(id, in: notes[nightDate] ?? []), nightDate: nightDate)
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
        privateNotes.setCustomNotes(list)
        customNotes = privateNotes.customNotes()
    }

    // MARK: Schlafziel und Bettzeit

    func setSleepGoal(_ minutes: Int) {
        sleepGoal = SleepGoal.clamp(minutes)
        SettingsFile.phone.set(sleepGoal, forKey: Self.sleepGoalKey)
        publishToWatch()
        Task { await replanBedtime() }
    }

    func setBedtimeReminder(_ enabled: Bool) {
        bedtimeReminder = enabled
        SettingsFile.phone.set(enabled, forKey: Self.bedtimeKey)
        Task {
            if enabled { await bedtime.requestPermission() }
            await replanBedtime()
        }
    }

    /// Plant die Bettzeit-Erinnerung neu (Wecker, Ziel, Einstellung, Zeitzone oder neue Nacht geändert).
    func replanBedtime() async {
        // Stand noch nicht gelesen: nichts verwerfen; `catchUpState` plant danach neu (AP3-1).
        guard stateLoaded || catchUpState() else { return }
        await bedtime.reschedule(alarms: alarms, goalMinutes: sleepGoal, enabled: bedtimeReminder, holidays: holidays.dates,
                                 latencyMinutes: learnedLatency)
    }

    /// Gelernte Einschlafdauer (Lernphase) für die eine Bettzeit (`Bedtime.bedtime`, Audit O-03); `nil` = nicht gelernt.
    var learnedLatency: Int? { PhoneLearning.shared.state.values?.latencyMinutes }
}
