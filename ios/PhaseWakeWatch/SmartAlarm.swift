import CoreMotion
import HealthKit
import Observation
import PhaseWakeCore
import UserNotifications
import WatchKit

/// Führt eine Weck-Nacht auf der Apple Watch durch – Gegenstück zum `WakeService` auf Wear OS.
///
/// Nutzt die Sitzungsart «Smart Alarm» (`WKExtendedRuntimeSession`): Sie wird vom System gestartet,
/// darf im Hintergrund Bewegung und Puls lesen und dauert höchstens 30 Minuten. Damit die Zielzeit
/// sicher in der Sitzung liegt, beginnt sie höchstens 25 Minuten davor (`sessionStart`); bei einem
/// 30-Minuten-Fenster bleiben die ersten 5 Minuten ungemessen (leere Epochen).
/// Gemessen wird wie auf Android (Regel in docs/PHASENERKENNUNG.md).
///
/// Grenzen von watchOS, die hier abgefangen werden:
/// - Planen geht nur bei aktiver App und höchstens 36 Stunden im Voraus. Sonst wartet der Plan
///   (`waiting`), bis die App das nächste Mal offen ist; das iPhone zeigt das im Nacht-Check.
/// - Lehnt das System die Sitzung ab oder bricht sie ab, weckt eine lokale Mitteilung kurz nach
///   der Zielzeit (Rückfall). Sie bleibt beim Klingeln stehen und wird erst beim Beenden entfernt.
/// - Endet die Sitzung während des Klingelns oder Schlummerns (Ablauf, System), weckt sofort eine
///   Mitteilung, und die Nacht wird trotzdem abgeschlossen; ebenso spätestens 1 h nach der Zielzeit.
@MainActor
@Observable
final class SmartAlarm: NSObject, WKExtendedRuntimeSessionDelegate {
    static let shared = SmartAlarm()

    enum Phase: Equatable {
        case idle
        /// Plan steht, Sitzung aber (noch) nicht: App öffnen bzw. näher als 36 h.
        case waiting(WakePlan)
        case scheduled(WakePlan)
        case measuring(WakePlan)
        case ringing(WakeReason, snoozesLeft: Int)
        case snoozed(until: Date, snoozesLeft: Int)

        var isRinging: Bool { if case .ringing = self { true } else { false } }
        var isSnoozed: Bool { if case .snoozed = self { true } else { false } }
        var isMeasuring: Bool { if case .measuring = self { true } else { false } }
    }

    /// Höchstens so weit im Voraus nimmt watchOS eine Smart-Alarm-Sitzung an.
    static let maxLeadTime: TimeInterval = 36 * 3600
    /// Der Rückfall weckt kurz nach der Zielzeit, damit er dem regulären Wecken nicht zuvorkommt.
    static let fallbackDelay: TimeInterval = 60
    /// Höchstdauer der Sitzung (30 Min) minus Reserve: So liegt die Zielzeit samt Klingeln in der Sitzung.
    static let sessionLead: TimeInterval = 25 * 60
    /// Spätestens so lange nach der Zielzeit gilt eine Nacht, die noch «klingelt» oder «schlummert», als hängengeblieben.
    static let ringLimit: TimeInterval = 3600
    /// Spielraum, um den das System eine Sitzung früher oder später starten darf.
    static let startTolerance: TimeInterval = 120
    nonisolated private static let fallbackId = "phasewake.fallback"
    /// Eigene Kennung für das Wecken nach einer abgebrochenen Sitzung: Neu planen ersetzt sie nicht.
    private static let interruptedId = "phasewake.fallback.interrupted"
    private static let stateKey = "smartAlarmNight"

    private(set) var phase: Phase = .idle

    @ObservationIgnored private var session: WKExtendedRuntimeSession?
    /// Plan der laufenden Sitzung (nach Schlummern: der Schlummer-Termin).
    @ObservationIgnored private var plan: WakePlan?
    /// Ursprünglicher Plan der Nacht; wird im Verlauf gespeichert.
    @ObservationIgnored private var nightPlan: WakePlan?
    @ObservationIgnored private var spec: AlarmSpec?
    @ObservationIgnored private var detector = WakeDetector()
    @ObservationIgnored private var assembler: EpochAssembler?
    @ObservationIgnored private var epochs: [Epoch] = []
    @ObservationIgnored private var firstRing: Date?
    @ObservationIgnored private var reason: WakeReason = .deadline
    @ObservationIgnored private var snoozes = 0
    /// Epochen ab Fensterbeginn, die vor dem Start der Sitzung lagen (leer, nicht für die Erkennung).
    @ObservationIgnored private var unmeasured = 0
    @ObservationIgnored private var ticker: Timer?
    @ObservationIgnored private var deadline: Timer?

    @ObservationIgnored private let motion = CMMotionManager()
    /// Alle Zugriffe auf den `EpochAssembler` laufen über diese eine serielle Warteschlange.
    nonisolated let assemblerQueue = DispatchQueue(label: "phasewake.epochs")
    @ObservationIgnored private let motionQueue = OperationQueue()
    @ObservationIgnored private let health = HKHealthStore()
    @ObservationIgnored private var heartRateQuery: HKAnchoredObjectQuery?

    override private init() {
        super.init()
        motionQueue.maxConcurrentOperationCount = 1
        motionQueue.underlyingQueue = assemblerQueue
        restore()
    }

    // MARK: Berechtigungen

    /// Puls lesen (zweite Stimme der Erkennung) und Mitteilungen (Rückfall) – beim ersten Start fragen.
    func requestAccess() async {
        if HKHealthStore.isHealthDataAvailable(), let heartRate = HKQuantityType.quantityType(forIdentifier: .heartRate) {
            try? await health.requestAuthorization(toShare: [], read: [heartRate])
        }
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
    }

    // MARK: Planen

    /// Plant die Sitzung für `plan`. Gelingt das nicht (App nicht aktiv, zu weit voraus), wartet der
    /// Plan, und der Rückfall steht trotzdem.
    func schedule(_ plan: WakePlan?) {
        if phase.isRinging || phase.isSnoozed {
            // Hängengeblieben (z. B. nach einem Neustart der App ohne Sitzung): abschliessen; das plant neu.
            if ringExpired { finishInterrupted() }
            return
        }
        if case .measuring(let running) = phase {
            // Wecker während der Messung geändert, aus- oder abgeschaltet: Messung ohne Aufzeichnung beenden.
            guard running != plan else { return }
            cancelMeasuring()
        }
        guard let plan else {
            invalidateSession()
            removeFallback()
            phase = .idle
            publishArmed()
            return
        }
        scheduleFallback(at: plan.target.addingTimeInterval(Self.fallbackDelay))
        if case .scheduled(let current) = phase, current == plan, session != nil { return }

        let active = WKApplication.shared().applicationState == .active
        let start = Self.sessionStart(plan)
        let tooEarly = start.timeIntervalSinceNow > Self.maxLeadTime
        guard active, !tooEarly else {
            // Eine schon geplante Sitzung für einen anderen Plan ist überholt.
            if case .scheduled(let current) = phase, current != plan { invalidateSession() }
            if case .scheduled = phase, session != nil, !tooEarly { return }
            phase = .waiting(plan)
            publishArmed()
            return
        }
        invalidateSession()
        let newSession = WKExtendedRuntimeSession()
        newSession.delegate = self
        if start <= Date() {
            newSession.start()
        } else {
            newSession.start(at: start)
        }
        session = newSession
        self.plan = plan
        phase = .scheduled(plan)
        publishArmed()
    }

    /// Start der Sitzung: am Fensterbeginn, aber höchstens `sessionLead` vor der Zielzeit, weil die
    /// Sitzung nach 30 Minuten endet und das Wecken zur Zielzeit sonst auf ihr Ende fiele.
    static func sessionStart(_ plan: WakePlan) -> Date {
        max(plan.windowStart, plan.target.addingTimeInterval(-sessionLead))
    }

    /// Klingeln oder Schlummern dauert schon über die Zeitgrenze hinaus.
    private var ringExpired: Bool {
        guard let target = nightPlan?.target else { return true }
        return Date() > target.addingTimeInterval(Self.ringLimit)
    }

    /// Vom System gestartete Sitzung übernehmen. Muss synchron in `handle(_:)` geschehen,
    /// sonst beendet das System die Sitzung.
    func adopt(_ extendedSession: WKExtendedRuntimeSession) {
        extendedSession.delegate = self
        if let session, session !== extendedSession { session.invalidate() }
        session = extendedSession
    }

    private func invalidateSession() {
        session?.delegate = nil
        session?.invalidate()
        session = nil
    }

    /// Meldet dem iPhone, für welche Zielzeit die Uhr scharf ist (Nacht-Check).
    private func publishArmed() {
        var armed: Date?
        switch phase {
        case .scheduled(let plan), .measuring(let plan): armed = plan.target
        case .ringing, .snoozed: armed = nightPlan?.target
        case .idle, .waiting: armed = nil
        }
        WatchConnectivityBridge.shared.publish(armedTarget: armed)
    }

    // MARK: Rückfall

    private func scheduleFallback(at date: Date, id: String = SmartAlarm.fallbackId) {
        let content = UNMutableNotificationContent()
        content.title = L("ring_greeting")
        content.body = L("ring_reason_deadline")
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        let interval = max(1, date.timeIntervalSinceNow)
        let request = UNNotificationRequest(
            identifier: id,
            content: content,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)
        )
        UNUserNotificationCenter.current().add(request)
    }

    private func removeFallback(_ ids: [String] = [SmartAlarm.fallbackId]) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
    }

    // MARK: WKExtendedRuntimeSessionDelegate

    nonisolated func extendedRuntimeSessionDidStart(_ extendedRuntimeSession: WKExtendedRuntimeSession) {
        Task { @MainActor in
            guard extendedRuntimeSession === self.session || self.session == nil else { return }
            self.session = extendedRuntimeSession
            switch self.phase {
            case .snoozed:
                // Nach dem Schlummern startet die neue Sitzung direkt mit dem Wecken.
                self.ring(self.reason)
            case .measuring, .ringing:
                break // läuft schon
            case .idle, .waiting, .scheduled:
                self.startNight()
            }
        }
    }

    nonisolated func extendedRuntimeSessionWillExpire(_ extendedRuntimeSession: WKExtendedRuntimeSession) {
        // Kurz vor Ablauf (spätestens nach 30 Min): jetzt wecken, sonst verwirft das System den Alarm.
        Task { @MainActor in if self.phase.isMeasuring { self.ring(self.detector.finish()) } }
    }

    nonisolated func extendedRuntimeSession(
        _ extendedRuntimeSession: WKExtendedRuntimeSession,
        didInvalidateWith reason: WKExtendedRuntimeSessionInvalidationReason,
        error: Error?
    ) {
        Task { @MainActor in
            guard extendedRuntimeSession === self.session else { return }
            self.session = nil
            self.stopSensors()
            switch self.phase {
            case .scheduled(let plan):
                // Vom System abgelehnt oder zurückgezogen: Der Rückfall weckt; beim nächsten Öffnen neu planen.
                self.phase = .waiting(plan)
            case .measuring:
                // Abgebrochen mitten in der Messung: sofort per Mitteilung wecken.
                self.scheduleFallback(at: Date(), id: Self.interruptedId)
                self.phase = .idle
            case .ringing, .snoozed:
                // Abgelaufen oder vom System beendet: weiterwecken und die Nacht abschliessen.
                self.finishInterrupted()
                return
            case .idle, .waiting:
                break
            }
            self.publishArmed()
        }
    }

    // MARK: Messen

    /// Prüft beim Start, ob die Sitzung zum aktuellen Plan passt. Eine verwaiste Sitzung (App-Prozess
    /// beendet, Wecker danach verschoben oder ausgeschaltet) misst nicht, sondern wird beendet.
    private func startNight() {
        let model = WatchModel.shared
        model.refresh()
        let now = Date()
        guard let current = model.plan,
              plan == nil || plan == current,
              now >= Self.sessionStart(current).addingTimeInterval(-Self.startTolerance),
              now < current.target.addingTimeInterval(Self.fallbackDelay) else {
            invalidateSession()
            phase = .idle
            schedule(model.plan)
            return
        }
        beginMeasuring(current)
    }

    private func beginMeasuring(_ plan: WakePlan) {
        stopSensors()
        let model = WatchModel.shared
        self.plan = plan
        nightPlan = plan
        spec = model.alarms.first { $0.id == plan.alarmId }
        detector = WakeDetector(config: Calibration.calibrate(nights: NightHistory.motionsForCalibration(model.storage.nights())))
        epochs = []
        unmeasured = max(0, Int(Date().timeIntervalSince(plan.windowStart)) / DetectorConfig.epochSeconds)
        firstRing = nil
        snoozes = 0
        phase = .measuring(plan)
        publishArmed()

        let uptimeNanos = Int64(ProcessInfo.processInfo.systemUptime * 1e9)
        let sinceStart = Int64(Date().timeIntervalSince(plan.windowStart) * 1e9)
        let assembler = EpochAssembler(startNanos: uptimeNanos - sinceStart)
        self.assembler = assembler

        if motion.isAccelerometerAvailable {
            motion.accelerometerUpdateInterval = 1.0 / 25
            motion.startAccelerometerUpdates(to: motionQueue) { [weak self] data, _ in
                guard let data else { return }
                // CoreMotion liefert g; die Regel rechnet in m/s².
                let g = 9.80665
                let nanos = Int64(data.timestamp * 1e9)
                let closed = assembler.addMotion(timestampNanos: nanos, x: data.acceleration.x * g, y: data.acceleration.y * g, z: data.acceleration.z * g)
                if !closed.isEmpty { Task { @MainActor in self?.feed(closed) } }
            }
        }
        startHeartRate(assembler: assembler)

        ticker = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let assembler = self.assembler else { return }
                let now = Int64(ProcessInfo.processInfo.systemUptime * 1e9)
                // CoreMotion liefert ohne Bündelung: Nachfrist 2 s plus ein Takt des Zeitgebers.
                let closed = self.assemblerQueue.sync { assembler.advance(nowNanos: now, graceNanos: 12_000_000_000) }
                self.feed(closed)
            }
        }
        deadline = Timer.scheduledTimer(withTimeInterval: max(0, plan.target.timeIntervalSinceNow), repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.phase.isMeasuring else { return }
                self.ring(self.detector.finish())
            }
        }
    }

    private func cancelMeasuring() {
        stopSensors()
        invalidateSession()
        phase = .idle
        plan = nil
        nightPlan = nil
    }

    private func startHeartRate(assembler: EpochAssembler) {
        guard HKHealthStore.isHealthDataAvailable(), let type = HKQuantityType.quantityType(forIdentifier: .heartRate) else { return }
        let predicate = HKQuery.predicateForSamples(withStart: Date(), end: nil)
        let unit = HKUnit.count().unitDivided(by: .minute())
        let queue = assemblerQueue
        let handler: @Sendable (HKAnchoredObjectQuery, [HKSample]?, [HKDeletedObject]?, HKQueryAnchor?, Error?) -> Void = { [weak self] _, samples, _, _, _ in
            guard let samples = samples as? [HKQuantitySample], !samples.isEmpty else { return }
            // Zeitstempel der Probe auf die Systemlaufzeit umrechnen (gleiche Uhr wie CoreMotion): Alter der
            // Probe gegen die Wanduhr, abgezogen von der Laufzeit. Beide Bezugswerte im selben Moment und
            // einmal je Lieferung genommen. Bewusst kein fester Versatz ab Sitzungsbeginn: HealthKit stempelt
            // nach der Wanduhr, nach einem Zeitsprung (NTP) verschöbe ein alter Versatz jede spätere Probe
            // um den ganzen Sprung. So trifft es nur Proben, die vor dem Sprung entstanden und erst danach kommen.
            let uptime = ProcessInfo.processInfo.systemUptime
            let wall = Date()
            let closed: [Epoch] = queue.sync {
                samples.flatMap { sample in
                    let nanos = Int64((uptime - wall.timeIntervalSince(sample.endDate)) * 1e9)
                    return assembler.addHeartRate(timestampNanos: nanos, bpm: sample.quantity.doubleValue(for: unit))
                }
            }
            if !closed.isEmpty { Task { @MainActor in self?.feed(closed) } }
        }
        let query = HKAnchoredObjectQuery(type: type, predicate: predicate, anchor: nil, limit: HKObjectQueryNoLimit, resultsHandler: handler)
        query.updateHandler = handler
        health.execute(query)
        heartRateQuery = query
    }

    private func feed(_ closed: [Epoch]) {
        guard phase.isMeasuring else { return }
        for epoch in closed {
            epochs.append(epoch)
            // Vor dem Start der Sitzung gab es keine Werte: Diese Epochen zählen nicht fürs Aufwärmen der Erkennung.
            if epochs.count <= unmeasured { continue }
            if case .wake(let reason) = detector.onEpoch(epoch) {
                ring(reason)
                return
            }
        }
    }

    private func stopSensors() {
        motion.stopAccelerometerUpdates()
        if let heartRateQuery { health.stop(heartRateQuery) }
        heartRateQuery = nil
        ticker?.invalidate()
        ticker = nil
        deadline?.invalidate()
        deadline = nil
    }

    // MARK: Wecken

    @ObservationIgnored private var hapticStart = Date()

    private func ring(_ reason: WakeReason) {
        stopSensors()
        // Der Rückfall bleibt stehen, bis beendet wird: Endet die Sitzung, weckt er trotzdem.
        self.reason = reason
        if firstRing == nil { firstRing = Date() }
        phase = .ringing(reason, snoozesLeft: Snooze.maxCount - snoozes)
        save()
        hapticStart = Date()
        // Steigerung wie auf Android: sanft, deutlich, anhaltend – je eine Minute.
        session?.notifyUser(hapticType: .notification) { [weak self] haptic in
            let elapsed = Date().timeIntervalSince(self?.hapticStart ?? Date())
            switch elapsed {
            case ..<60:
                haptic.pointee = .notification
                return 3
            case ..<120:
                haptic.pointee = .retry
                return 2
            default:
                haptic.pointee = .failure
                return 1.5
            }
        }
        // Immer melden: Das iPhone entscheidet (Zweitwecker, Sonnenaufgang, Sicherung aussetzen).
        sendRing()
    }

    /// «klingeln» ans iPhone, mit Zielzeit der Nacht und verbleibenden Schlummer-Runden.
    private func sendRing() {
        guard let alarmId = nightPlan?.alarmId ?? plan?.alarmId else { return }
        WatchConnectivityBridge.shared.send(command: SyncKeys.ring, alarmId: alarmId, target: nightPlan?.target,
                                            snoozesLeft: Snooze.maxCount - snoozes)
    }

    /// Schlummern am iPhone gewählt: wie auf der Uhr. Geht es nicht mehr, klingelt das iPhone weiter.
    func snoozeFromPhone() {
        guard phase.isRinging else { return }
        if Snooze.canSnooze(snoozes) { snooze() } else { sendRing() }
    }

    /// Schlummern: Sitzung beenden und neu für in 5 Minuten planen (App ist dabei aktiv).
    func snooze() {
        guard phase.isRinging, Snooze.canSnooze(snoozes), let plan else { return }
        snoozes += 1
        invalidateSession()
        let until = Snooze.nextRing(Date())
        phase = .snoozed(until: until, snoozesLeft: Snooze.maxCount - snoozes)
        let next = WKExtendedRuntimeSession()
        next.delegate = self
        next.start(at: until)
        session = next
        self.plan = WakePlan(alarmId: plan.alarmId, windowStart: until, target: until)
        // Rückfall auch fürs Schlummern: falls die neue Sitzung nicht startet.
        scheduleFallback(at: until.addingTimeInterval(Self.fallbackDelay))
        save()
        WatchConnectivityBridge.shared.send(command: SyncKeys.stop, alarmId: plan.alarmId, target: nightPlan?.target)
    }

    /// Wecker beenden, Nacht speichern, nächste Nacht planen.
    func dismiss(notifyPhone: Bool = true) {
        guard phase.isRinging || phase.isSnoozed else { return }
        removeFallback([Self.fallbackId, Self.interruptedId])
        finish(notifyPhone: notifyPhone)
    }

    /// Sitzung weg oder Zeitgrenze erreicht, während geklingelt oder geschlummert wird: per Mitteilung
    /// weiterwecken (nicht mehr nach der Zeitgrenze) und die Nacht abschliessen. Das iPhone bekommt
    /// die Nacht, aber kein «stop»: Sein Wecker soll weiterklingeln.
    private func finishInterrupted() {
        guard phase.isRinging || phase.isSnoozed else { return }
        if !ringExpired {
            var at = Date()
            if case .snoozed(let until, _) = phase, until > at { at = until.addingTimeInterval(Self.fallbackDelay) }
            scheduleFallback(at: at, id: Self.interruptedId)
        }
        finish(notifyPhone: false)
    }

    /// Nacht speichern, einmaligen Wecker ausschalten, nächste Nacht planen.
    private func finish(notifyPhone: Bool) {
        invalidateSession()
        stopSensors()
        let model = WatchModel.shared
        if let nightPlan, let firstRing {
            let night = NightRecord(
                alarmId: nightPlan.alarmId,
                windowStart: nightPlan.windowStart,
                target: nightPlan.target,
                wokeAt: firstRing,
                reason: reason,
                snoozes: snoozes,
                epochs: epochs
            )
            model.storage.add(night)
            // Diese Nacht ist erledigt: nicht noch einmal planen, auch wenn die Zielzeit noch vorn liegt.
            model.storage.markDone(alarmId: nightPlan.alarmId, until: nightPlan.target)
            WatchConnectivityBridge.shared.send(night: night)
        }
        if notifyPhone { WatchConnectivityBridge.shared.send(command: SyncKeys.stop, alarmId: nightPlan?.alarmId ?? "", target: nightPlan?.target) }
        // Aus dem Speicher statt aus der Nacht: nach einem Neustart der App fehlt `spec`.
        if let alarm = model.alarms.first(where: { $0.id == nightPlan?.alarmId }), alarm.isOneShot, alarm.enabled {
            model.setEnabled(alarm, false)
        }
        phase = .idle
        plan = nil
        nightPlan = nil
        spec = nil
        clearSaved()
        model.reschedule()
    }

    // MARK: Zustand sichern

    /// Klingel- und Schlummer-Zustand überstehen das Beenden der App durch das System.
    private func save() {
        guard let nightPlan, let firstRing else { return }
        let night = NightRecord(alarmId: nightPlan.alarmId, windowStart: nightPlan.windowStart, target: nightPlan.target,
                                wokeAt: firstRing, reason: reason, snoozes: snoozes, epochs: epochs)
        var state: [String: Any] = ["night": Codec.encodeNight(night)]
        if case .snoozed(let until, _) = phase { state["snoozedUntil"] = until.timeIntervalSince1970 }
        UserDefaults.standard.set(state, forKey: Self.stateKey)
    }

    private func clearSaved() {
        UserDefaults.standard.removeObject(forKey: Self.stateKey)
    }

    private func restore() {
        guard let state = UserDefaults.standard.dictionary(forKey: Self.stateKey),
              let text = state["night"] as? String,
              let night = try? Codec.decodeNight(text) else { return }
        // Älter als zwei Stunden nach der Zielzeit: nicht mehr wecken, nur verwerfen.
        guard night.target.timeIntervalSinceNow > -2 * 3600 else {
            clearSaved()
            return
        }
        nightPlan = WakePlan(alarmId: night.alarmId, windowStart: night.windowStart, target: night.target)
        firstRing = night.wokeAt
        reason = night.reason
        snoozes = night.snoozes
        epochs = night.epochs
        let interrupted: Bool
        if let until = state["snoozedUntil"] as? Double {
            let date = Date(timeIntervalSince1970: until)
            plan = WakePlan(alarmId: night.alarmId, windowStart: date, target: date)
            phase = .snoozed(until: date, snoozesLeft: Snooze.maxCount - snoozes)
            // Die Schlummer-Sitzung hätte längst starten müssen.
            interrupted = date.addingTimeInterval(Self.startTolerance) < Date()
        } else {
            plan = nightPlan
            phase = .ringing(reason, snoozesLeft: Snooze.maxCount - snoozes)
            // Nach einem Neustart der App gibt es keine Sitzung mehr, also auch keine Vibration.
            interrupted = true
        }
        // Erst nach dem Aufbau von `shared` abschliessen (plant über WatchModel neu).
        if interrupted { Task { @MainActor in self.finishInterrupted() } }
    }
}
