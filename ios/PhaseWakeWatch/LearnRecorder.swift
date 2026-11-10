import CoreMotion
import Foundation
import HealthKit
import PhaseWakeCore
import WatchKit

/// Lernphase auf der Apple Watch (`docs/PHASENERKENNUNG.md`, Abschnitt 10, Apple) – Gegenstück zur automatischen
/// Aufzeichnung der Galaxy Watch (`RecordControl`, `LearnSampler`).
///
/// watchOS lässt eine App nicht die ganze Nacht selbst messen. Darum startet die Uhr am Abend den Bewegungs-Rekorder des
/// Systems (`CMSensorRecorder`, 50 Hz, bis 12 Std., läuft auch bei geschlossener App) und holt die Werte nach dem Wecken
/// ab: in Abschnitten von 10 Min, mit begrenzter Rechenzeit je Durchgang, gespeichert nach jedem Durchgang. Daraus
/// rechnet ``RecorderEpochs`` die Bewegung je 30-s-Epoche mit derselben Regel wie im Weckfenster; den Puls je Epoche
/// liefert Apple Health (Werte, die die Uhr in Ruhe selbst misst). Die fertige Aufzeichnung (Grund `wake`) geht als
/// Benutzerdaten ans iPhone, das die Nächte zählt und lernt; die Uhr behält sie nicht.
///
/// Auslöser (watchOS bietet keinen festen Zeitpunkt am Abend): jedes Neuplanen (`WatchModel.reschedule`: App geöffnet,
/// etwa über die Erinnerung am Abend (`EveningReminder`), Wecker oder Lernphase vom iPhone, Ende einer Nacht) und eine
/// Hintergrund-Aktualisierung, die die Uhr auf den frühesten Start bzw. das Ende der Nacht stellt (`PhaseWakeWatchApp`).
/// Gestartet wird frühestens 60 Min vor der Bettzeit (``AppleLearnRecord``, wie die Galaxy Watch; Entscheid r1AD
/// 10.11.2026), und nur, wenn die App dann läuft; sonst zeichnet die Uhr diese Nacht nicht auf. Je Nacht ein Aufruf des
/// Rekorders (``AppleNightRecorder``). Während eine Nacht misst, klingelt oder schlummert, rechnet sie nicht (der Wecker
/// hat Vorrang).
///
/// «Nacht aufzeichnen» (von Hand, auf der Uhr oder vom iPhone) nutzt denselben Weg (`LearnRecorder+Manual`): Rekorder
/// bis 12 Std., Ende beim Wecken, von Hand, nach 12 Std. oder bei tiefem Akku; ausgewertet und ans iPhone geschickt wie eine Nacht der
/// Lernphase. Läuft sie, startet die Lernphase keine eigene Aufzeichnung (sie zählt dann wie auf Android).
///
/// Stand der Lernphase vom iPhone (``LearningSnapshot``) und die laufende Aufzeichnung liegen im von der Sicherung
/// ausgenommenen Ordner (`PrivateFiles`).
@MainActor
final class LearnRecorder {
    static let shared = LearnRecorder()

    /// Neue Werte des Rekorders sind erst nach bis zu 3 Min abrufbar (Apple-Dokumentation zu `accelerometerData`).
    nonisolated static let dataDelay: TimeInterval = 3 * 60
    /// So viel Zeit je Abfrage beim Rekorder (10 Min = 30 000 Werte).
    nonisolated static let chunk: TimeInterval = 10 * 60
    /// Rechenzeit je Durchgang mit offener App.
    static let foregroundBudget: TimeInterval = 20
    /// Rechenzeit je Durchgang im Hintergrund (watchOS gibt einer Hintergrund-Aktualisierung nur wenige Sekunden).
    static let backgroundBudget: TimeInterval = 2.5
    /// Der Rekorder behält die Werte 3 Tage: Ist die Nacht so lange vorbei, geht die Aufzeichnung mit dem bisher
    /// Ausgewerteten ans iPhone.
    static let giveUpAfter: TimeInterval = 60 * 3600
    /// Spätestens so lange nach dem Ende der Nacht versucht die Hintergrund-Aktualisierung das Auswerten erneut.
    static let retry: TimeInterval = 15 * 60
    /// So oft prüft die Uhr während «Nacht aufzeichnen» den Akku (Hintergrund-Aktualisierung; watchOS kann sie verzögern).
    static let batteryCheck: TimeInterval = 30 * 60

    /// Aufzeichnung einer Nacht: Wecker und Zielzeit, Beginn der Auswertung (`from`, Epoche 0), Ende des Rekorders
    /// (`until`), Ende des Klingelns (`wokeAt`) und die schon ausgewerteten Epochen (`motions`). Von Hand
    /// (`manual`, «Nacht aufzeichnen»): ohne Wecker, `until` = Beginn plus 12 Std. Von Hand gestoppt oder bei tiefem
    /// Akku: `stoppedAt` mit Grund `stopReason` (``RecordEnd``). Einschlafzeit aus dem Rekorder (`WatchOnset`), falls
    /// bekannt: `bedAt` und `asleepAt`. Die neuen Felder fehlen bei älteren Ständen (`nil`).
    struct Mark: Codable, Equatable {
        var alarmId: String
        var target: Date
        var from: Date
        var until: Date
        var wokeAt: Date?
        var motions: [Double?]
        var manual: Bool?
        var stoppedAt: Date?
        var stopReason: String?
        var bedAt: Date?
        var asleepAt: Date?
    }

    private static let snapshotKey = "learningSnapshot"
    private let fileURL = PrivateFiles.directory.appendingPathComponent("learn-record.v1")
    private let health = HKHealthStore()

    /// Stand der Lernphase vom iPhone; ohne Stand inaktiv und ohne Werte.
    private(set) var snapshot: LearningSnapshot
    /// Laufende oder noch nicht fertig ausgewertete Aufzeichnung; nur über ``save(_:)`` ändern.
    private(set) var mark: Mark?
    private var running = false
    /// Zielzeit, für die ein Nicht-Starten schon im Protokoll steht (einmal je Nacht).
    private var loggedSkip: Date?
    /// Frühester Start der nächsten Nacht (``AppleLearnRecord/Decision/wait(until:)``).
    private var nextTry: Date?
    /// Zuletzt gestellte Hintergrund-Aktualisierung.
    private var scheduled: Date?
    /// Erlaubnis für Bewegungsdaten in diesem Lauf schon erfragt.
    private var askedMotion = false
    /// Bedarf, für den «Rekorder reicht nicht» schon im Protokoll steht (`ensureRecorder`, einmal je Bedarf).
    var loggedShortNeed: Date?

    private init() {
        snapshot = SettingsFile.watchSession.data(forKey: Self.snapshotKey)
            .flatMap { try? JSONDecoder().decode(LearningSnapshot.self, from: $0) } ?? .empty
        mark = (try? Data(contentsOf: fileURL)).flatMap { try? JSONDecoder().decode(Mark.self, from: $0) }
    }

    /// Beginn der laufenden Aufzeichnung (Lernphase ab dem Beginn ihrer Auswertung, oder von Hand); `nil`, wenn keine läuft.
    var since: Date? {
        let now = Date()
        guard let mark, mark.from <= now, now < Self.end(of: mark) else { return nil }
        return mark.from
    }

    /// Zuletzt an Startseite und iPhone gemeldeter Beginn (`publishSince`).
    var publishedSince: Date?
    /// Automatische Einschlaf-Messung (`LearnRecorder+AutoOnset`): frühester Start für die nächste Nacht.
    var autoNextTry: Date?
    /// Zielzeit, für die die automatische Messung entschieden ist (gestartet oder ausgelassen), und ob ausgelassen.
    var autoDecided: Date?
    var autoSkipped = false

    /// Stand vom iPhone übernehmen, wenn er neuer ist (wie `LearningStore.setIfNewer`). Ausgeschaltet, während eine
    /// Aufzeichnung läuft: Sie endet ohne Auswertung (Grund `phone`). Danach neu planen.
    func apply(_ new: LearningSnapshot) {
        guard new.replaces(snapshot) else { return }
        let changed = new.active != snapshot.active || new.floor != snapshot.floor || new.restingHeartRate != snapshot.restingHeartRate
        snapshot = new
        SettingsFile.watchSession.set(try? JSONEncoder().encode(new), forKey: Self.snapshotKey)
        if changed { DiagStore.shared.log(.sync, "learning \(new.diagLine)") }
        // Eine von Hand begonnene Aufzeichnung läuft weiter (wie auf Android).
        if !new.active, let mark, mark.manual != true, Date() < Self.end(of: mark) {
            DiagStore.shared.log(.measureEnd, "record end phone")
            save(nil)
        }
        plan()
    }

    /// «Verlauf löschen» vom iPhone: Eine laufende oder noch nicht ausgewertete Aufzeichnung verfällt (sie läge vor dem
    /// neuen Beginn der Lernphase und zählte nicht; eine von Hand begonnene gehört ebenso zum gelöschten Verlauf).
    func clearHistory() {
        guard mark != nil else { return }
        DiagStore.shared.log(.measureEnd, "record end phone (history cleared)")
        save(nil)
    }

    /// Die Nacht `alarmId` mit Zielzeit `target` hat geweckt (Klingeln beendet): Die Auswertung endet jetzt, höchstens
    /// beim Ende des Rekorders. Hat «Gute Nacht» die Zielzeit vorverlegt, nachdem die Aufzeichnung begann, gilt dieselbe
    /// Nacht (Zielzeiten höchstens 16 Std. auseinander). «Nacht aufzeichnen» endet spätestens jetzt (Grund ``RecordEnd/wake``,
    /// wie die Galaxy Watch, Audit F-01).
    func noteWake(alarmId: String, target: Date) {
        if mark?.manual == true {
            stop(.wake)
            return
        }
        guard var current = mark, current.alarmId == alarmId,
              abs(current.target.timeIntervalSince(target)) <= GoodNightEstimate.maxAhead, current.wokeAt == nil else { return }
        current.wokeAt = Date()
        save(current)
    }

    /// Plant und wertet aus (nicht während eine Nacht misst, klingelt oder schlummert). Rechenzeit nach Zustand der App.
    func plan() {
        let budget = WKApplication.shared().applicationState == .active ? Self.foregroundBudget : Self.backgroundBudget
        Task { await run(budget: budget) }
    }

    /// Hintergrund-Aktualisierung (`PhaseWakeWatchApp`): ein Durchgang mit der Rechenzeit für den Hintergrund.
    func refreshTask() async {
        await run(budget: Self.backgroundBudget)
    }

    /// Ein Durchgang: Nacht auswerten, falls vorbei; nächste Nacht starten, falls fällig; Hintergrund-Aktualisierung
    /// stellen. Läuft nie doppelt.
    func run(budget: TimeInterval) async {
        let phase = SmartAlarm.shared.phase
        guard !running, !phase.isMeasuring, !phase.isRinging, !phase.isSnoozed else { return }
        running = true
        defer { running = false }
        // «Nacht aufzeichnen»: Ende nach Akku (Ende nach 12 Std. ergibt sich aus `until`).
        checkBattery()
        if let pending = mark, Date() >= Self.end(of: pending) {
            await process(pending, budget: budget)
        }
        if snapshot.active {
            // Im Hintergrund kann `now` des Modells alt sein: frisch lesen.
            WatchModel.shared.refresh()
            await startIfDue()
        }
        // Einschlafzeit jede Nacht (`LearnRecorder+AutoOnset`), auch ohne Lernphase.
        startAutoOnsetIfDue()
        if snapshot.active || autoOnset { askMotionIfNeeded() }
        // Beginn oder Ende ohne Speichern (Auswertung der Lernphase beginnt, 12 Std. vorbei): Anzeige nachführen.
        if since != publishedSince { publishSince() }
        scheduleRefresh()
    }

    // MARK: Starten

    private func startIfDue() async {
        let model = WatchModel.shared
        guard let plan = model.realPlan else { return }
        if let mark, mark.alarmId == plan.alarmId, mark.target == plan.target { return }
        // Eine andere Nacht zeichnet noch auf (Wecker in der Nacht verschoben): sie zuerst zu Ende. Läuft «Nacht
        // aufzeichnen», zählt diese Aufzeichnung (wie auf Android: «recording runs»).
        if let mark, Date() < Self.end(of: mark) {
            if mark.manual == true { logSkip("recording runs", target: plan.target) }
            return
        }
        let spec = model.alarms.first { $0.id == plan.alarmId }
        let onset = ShortNight.autoOnsetStart(target: plan.target, sleepGoalMinutes: model.sleepGoal,
                                              sleepDurationMinutes: spec?.sleepDurationMinutes)
        let battery = Self.battery()
        nextTry = nil
        switch AppleLearnRecord.decide(now: Date(), target: plan.target, onsetStart: onset, battery: battery) {
        case .wait(let until):
            nextTry = until
        case .skip(let reason):
            logSkip(reason, target: plan.target, battery: reason.hasPrefix("battery"))
            if reason.hasPrefix("battery") { noteRecorderSkip(.battery, target: plan.target) }
        case .start(let from, let until, let duration):
            guard CMSensorRecorder.isAccelerometerRecordingAvailable() else {
                logSkip("recorder unavailable", target: plan.target)
                return
            }
            let status = CMSensorRecorder.authorizationStatus()
            // Ohne Erlaubnis zeichnet der Rekorder nichts auf; fragen kann watchOS nur bei offener App.
            if status == .denied || status == .restricted
                || (status == .notDetermined && WKApplication.shared().applicationState != .active) {
                logSkip("motion not allowed", target: plan.target)
                noteRecorderSkip(.motion, target: plan.target)
                return
            }
            // Eine frühere Nacht ist noch nicht fertig ausgewertet: mit dem bisher Ausgewerteten ans iPhone.
            if let pending = mark { await finish(pending) }
            ensureRecorder(until: Date().addingTimeInterval(duration))
            save(Mark(alarmId: plan.alarmId, target: plan.target, from: from, until: until, wokeAt: nil, motions: []))
            DiagStore.shared.log(.measureStart, "learn record start bat=\(battery.map { "\($0)" } ?? "-") mode=recorder")
        }
    }

    /// Fragt einmal je Lauf nach der Erlaubnis für Bewegungsdaten, solange sie offen ist und die App vorne ist: Sonst
    /// könnte der Rekorder am Abend im Hintergrund nicht starten. Eine Aufzeichnung von 1 s löst die Frage aus – nur
    /// tagsüber (vor dem Beginn der Nacht, `nightStart`): In der Nacht fragt der eine Aufruf des Rekorders selbst.
    private func askMotionIfNeeded() {
        // Nie neben einer laufenden Aufzeichnung: Ein zweiter Aufruf könnte ihre Dauer verkürzen.
        if let start = Self.nightStart(), Date() >= start { return }
        guard !askedMotion, mark == nil, !recorderRunning, WKApplication.shared().applicationState == .active,
              CMSensorRecorder.isAccelerometerRecordingAvailable(), CMSensorRecorder.authorizationStatus() == .notDetermined else { return }
        askedMotion = true
        CMSensorRecorder().recordAccelerometer(forDuration: 1)
    }

    func logSkip(_ reason: String, target: Date, battery: Bool = false) {
        // «past»: Die Nacht ist vorbei (sie wartet noch aufs Wecken oder ist ausgewertet) – nichts zu melden.
        guard loggedSkip != target, reason != "past" else { return }
        loggedSkip = target
        DiagStore.shared.log(battery ? .battery : .measureStart, "learn record skipped: \(reason)")
    }

    /// Akkustand der Uhr in Prozent; `nil`, wenn unbekannt.
    static func battery() -> Int? {
        let device = WKInterfaceDevice.current()
        device.isBatteryMonitoringEnabled = true
        return device.batteryLevel >= 0 ? Int((device.batteryLevel * 100).rounded()) : nil
    }

    // MARK: Auswerten

    /// Ende der Auswertung: von Hand gestoppt dann (höchstens Ende des Rekorders); sonst bei «Nacht aufzeichnen» das Ende
    /// des Rekorders (12 Std.), in der Lernphase das Ende des Klingelns (höchstens Ende des Rekorders), ohne Klingeln die
    /// Zielzeit.
    nonisolated static func end(of mark: Mark) -> Date {
        if let stopped = mark.stoppedAt { return min(stopped, mark.until) }
        if mark.manual == true { return mark.until }
        return AppleLearnRecord.end(wokeAt: mark.wokeAt, target: mark.target, until: mark.until)
    }

    /// Grund des Endes: von Hand bzw. Akku wie gemerkt, sonst nach 12 Std. (von Hand) bzw. beim Wecken (Lernphase).
    nonisolated static func reason(of mark: Mark) -> RecordEnd {
        if let code = mark.stopReason { return RecordEnd.of(code) }
        return mark.manual == true ? .cap : .wake
    }

    /// Letzte Epochengrenze ab `from` bis `until`.
    nonisolated private static func boundary(from: Date, until: Date) -> Date {
        let epochs = max(0, Int(until.timeIntervalSince(from)) / DetectorConfig.epochSeconds)
        return from.addingTimeInterval(TimeInterval(min(epochs, NightRecording.maxEpochs) * DetectorConfig.epochSeconds))
    }

    /// Wertet `pending` bis zu den abrufbaren Werten aus (höchstens `budget` Sekunden), speichert den Stand und schickt
    /// die Aufzeichnung ans iPhone, sobald sie bis zum Ende ausgewertet ist (oder die Werte bald verfallen).
    func process(_ pending: Mark, budget: TimeInterval) async {
        let now = Date()
        let end = Self.end(of: pending)
        let stop = Self.boundary(from: pending.from, until: min(end, now.addingTimeInterval(-Self.dataDelay)))
        let deadline = now.addingTimeInterval(budget)
        let motions = await Task.detached(priority: .utility) {
            Self.motions(pending, until: stop, deadline: deadline)
        }.value
        // Inzwischen verworfen (Lernphase aus, Verlauf gelöscht): nichts speichern.
        guard mark == pending else { return }
        var updated = pending
        updated.motions = motions
        let complete = motions.count * DetectorConfig.epochSeconds >= Int(Self.boundary(from: pending.from, until: end).timeIntervalSince(pending.from))
        if complete || now.timeIntervalSince(end) > Self.giveUpAfter {
            await finish(updated)
        } else {
            save(updated)
        }
    }

    /// Bewegung je Epoche aus dem Rekorder ab dem gespeicherten Stand bis `until` (Epochengrenze), Abschnitt für
    /// Abschnitt, bis `deadline`. Läuft abseits des Main-Threads.
    nonisolated private static func motions(_ mark: Mark, until stop: Date, deadline: Date) -> [Double?] {
        recordedMotions(start: mark.from, done: mark.motions, until: stop, deadline: deadline)
    }

    /// Bewegung je Epoche ab `start` aus dem Rekorder: nach den schon fertigen Epochen `done` bis `until`
    /// (Epochengrenze), Abschnitt für Abschnitt, bis `deadline`. Läuft abseits des Main-Threads (auch `WatchOnset`).
    nonisolated static func recordedMotions(start: Date, done: [Double?], until stop: Date, deadline: Date) -> [Double?] {
        let epochs = RecorderEpochs(start: start, motions: done)
        let recorder = CMSensorRecorder()
        var chunkStart = epochs.closedUntil
        while chunkStart < stop, Date() < deadline {
            let chunkEnd = min(chunkStart.addingTimeInterval(chunk), stop)
            if let list = recorder.accelerometerData(from: chunkStart, to: chunkEnd) {
                var iterator = NSFastEnumerationIterator(list)
                while let item = iterator.next() {
                    guard let data = item as? CMRecordedAccelerometerData else { continue }
                    epochs.add(at: data.startDate, x: data.acceleration.x, y: data.acceleration.y, z: data.acceleration.z)
                }
            }
            epochs.close(until: chunkEnd)
            guard epochs.closedUntil > chunkStart else { break }
            chunkStart = epochs.closedUntil
        }
        return epochs.motions
    }

    /// Puls aus Apple Health dazu, Aufzeichnung ans iPhone (mit der Einschlafzeit, falls bekannt), Stand löschen.
    func finish(_ done: Mark) async {
        let end = Self.end(of: done)
        let pulses = await heartRates(from: done.from, to: end)
        guard mark?.from == done.from else { return }
        let current = mark ?? done
        let rates = RecorderEpochs.heartRates(pulses, start: done.from, count: done.motions.count)
        let reason = Self.reason(of: current)
        var recording = NightRecording(start: done.from, epochs: RecorderEpochs.epochs(motions: done.motions, heartRates: rates),
                                       end: end, endReason: reason)
        if let bed = current.bedAt, let asleep = current.asleepAt { recording = recording.with(bedAt: bed, asleepAt: asleep) }
        let measured = done.motions.compactMap { $0 }.count
        let withPulse = rates.compactMap { $0 }.count
        DiagStore.shared.log(.measureEnd, "record end \(Self.reasonText(reason, battery: nil)) epochs=\(done.motions.count) motion=\(measured) hr=\(withPulse)")
        WatchConnectivityBridge.shared.send(learnRecording: NightRecording.encode(recording))
        save(nil)
    }

    /// Pulswerte (Zeitpunkt, Schläge pro Minute) aus Apple Health im Zeitraum; leer ohne Daten oder Erlaubnis.
    private func heartRates(from: Date, to: Date) async -> [(at: Date, bpm: Double)] {
        guard HKHealthStore.isHealthDataAvailable(), let type = HKQuantityType.quantityType(forIdentifier: .heartRate) else { return [] }
        let predicate = HKQuery.predicateForSamples(withStart: from, end: to)
        let store = health
        return await withCheckedContinuation { (continuation: CheckedContinuation<[(at: Date, bpm: Double)], Never>) in
            let unit = HKUnit.count().unitDivided(by: .minute())
            let query = HKSampleQuery(sampleType: type, predicate: predicate, limit: HKObjectQueryNoLimit, sortDescriptors: nil) { _, results, _ in
                let samples = (results as? [HKQuantitySample]) ?? []
                continuation.resume(returning: samples.map { (at: $0.startDate, bpm: $0.quantity.doubleValue(for: unit)) })
            }
            store.execute(query)
        }
    }

    // MARK: Speichern und Hintergrund

    func save(_ new: Mark?) {
        mark = new
        if let new, let data = try? JSONEncoder().encode(new) {
            // Atomar: Scheitert das Schreiben, bleibt der vorige Stand (höchstens ein Durchgang geht verloren).
            try? data.write(to: fileURL, options: .atomic)
        } else {
            try? FileManager.default.removeItem(at: fileURL)
        }
        // Anzeige auf Uhr und iPhone («Aufzeichnung läuft seit …»), nur bei einer Änderung.
        if since != publishedSince { publishSince() }
    }

    /// Hintergrund-Aktualisierung: Ende der laufenden Nacht (plus Verzögerung des Rekorders), bei unfertiger Auswertung
    /// in 15 Min, sonst der früheste Start der nächsten Nacht. Läuft «Nacht aufzeichnen», spätestens in 30 Min (Akku
    /// prüfen). watchOS bestimmt den genauen Zeitpunkt selbst.
    func scheduleRefresh() {
        let now = Date()
        var date: Date?
        if let mark {
            let end = Self.end(of: mark)
            let ready = end.addingTimeInterval(Self.dataDelay + 60)
            date = now < ready ? ready : now.addingTimeInterval(Self.retry)
            if mark.manual == true, now < end, let check = date { date = min(check, now.addingTimeInterval(Self.batteryCheck)) }
        } else if snapshot.active, let nextTry {
            date = nextTry
        }
        // Automatische Einschlaf-Messung: frühester Start der nächsten Nacht.
        if let auto = autoNextTry, auto > now { date = date.map { min($0, auto) } ?? auto }
        guard let date, date != scheduled else { return }
        scheduled = date
        WKApplication.shared().scheduleBackgroundRefresh(withPreferredDate: date, userInfo: nil) { error in
            guard let error else { return }
            DiagStore.shared.log(.error, "learn refresh not scheduled \((error as NSError).code)")
        }
    }
}
