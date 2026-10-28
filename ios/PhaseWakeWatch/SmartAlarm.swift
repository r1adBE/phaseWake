import CoreMotion
import HealthKit
import Observation
import PhaseWakeCore
import WatchKit

/// Führt eine Weck-Nacht auf der Apple Watch durch – Gegenstück zum `WakeService` auf Wear OS.
///
/// Nutzt die Sitzungsart «Smart Alarm» (`WKExtendedRuntimeSession`): Sie wird zum Fensterbeginn
/// vom System gestartet, darf im Hintergrund Bewegung und Puls lesen und dauert höchstens 30 Minuten.
/// Gemessen wird wie auf Android (Regel in docs/PHASENERKENNUNG.md).
@MainActor
@Observable
final class SmartAlarm: NSObject, WKExtendedRuntimeSessionDelegate {
    static let shared = SmartAlarm()

    enum Phase: Equatable {
        case idle
        case scheduled(WakePlan)
        case measuring(WakePlan)
        case ringing(WakeReason, snoozesLeft: Int)
        case snoozed(until: Date, snoozesLeft: Int)

        var isRinging: Bool { if case .ringing = self { true } else { false } }
        var isSnoozed: Bool { if case .snoozed = self { true } else { false } }
    }

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
    }

    // MARK: Planen

    /// Plant die Sitzung für [plan]; ersetzt eine geplante. Muss bei aktiver App aufgerufen werden.
    func schedule(_ plan: WakePlan?) {
        if case .measuring = phase { return }
        if phase.isRinging || phase.isSnoozed { return }
        session?.invalidate()
        session = nil
        guard let plan else {
            phase = .idle
            return
        }
        let newSession = WKExtendedRuntimeSession()
        newSession.delegate = self
        if plan.windowStart <= Date() {
            newSession.start()
        } else {
            newSession.start(at: plan.windowStart)
        }
        session = newSession
        self.plan = plan
        phase = .scheduled(plan)
    }

    /// Vom System gestartete Sitzung übernehmen (App wurde dafür im Hintergrund geöffnet).
    func adopt(_ extendedSession: WKExtendedRuntimeSession) {
        extendedSession.delegate = self
        session = extendedSession
    }

    // MARK: WKExtendedRuntimeSessionDelegate

    nonisolated func extendedRuntimeSessionDidStart(_ extendedRuntimeSession: WKExtendedRuntimeSession) {
        Task { @MainActor in
            // Nach dem Schlummern startet die neue Sitzung direkt mit dem Wecken.
            if self.phase.isSnoozed {
                self.ring(self.reason)
            } else {
                self.beginMeasuring()
            }
        }
    }

    nonisolated func extendedRuntimeSessionWillExpire(_ extendedRuntimeSession: WKExtendedRuntimeSession) {
        // Kurz vor Ablauf (spätestens nach 30 Min): jetzt wecken, sonst verwirft das System den Alarm.
        Task { @MainActor in if case .measuring = self.phase { self.ring(self.detector.finish()) } }
    }

    nonisolated func extendedRuntimeSession(
        _ extendedRuntimeSession: WKExtendedRuntimeSession,
        didInvalidateWith reason: WKExtendedRuntimeSessionInvalidationReason,
        error: Error?
    ) {
        Task { @MainActor in self.stopSensors() }
    }

    // MARK: Messen

    private func beginMeasuring() {
        let model = WatchModel.shared
        model.refresh()
        guard let plan = plan ?? model.plan else { return }
        self.plan = plan
        nightPlan = plan
        spec = model.alarms.first { $0.id == plan.alarmId }
        detector = WakeDetector(config: Calibration.calibrate(nights: NightHistory.motionsForCalibration(model.storage.nights())))
        epochs = []
        firstRing = nil
        snoozes = 0
        phase = .measuring(plan)

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
                let closed = self.assemblerQueue.sync { assembler.advance(nowNanos: now, graceNanos: 12_000_000_000) }
                self.feed(closed)
            }
        }
        deadline = Timer.scheduledTimer(withTimeInterval: max(0, plan.target.timeIntervalSinceNow), repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self, case .measuring = self.phase else { return }
                self.ring(self.detector.finish())
            }
        }
    }

    private func startHeartRate(assembler: EpochAssembler) {
        guard HKHealthStore.isHealthDataAvailable(), let type = HKQuantityType.quantityType(forIdentifier: .heartRate) else { return }
        let predicate = HKQuery.predicateForSamples(withStart: Date(), end: nil)
        let unit = HKUnit.count().unitDivided(by: .minute())
        let handler: (HKAnchoredObjectQuery, [HKSample]?, [HKDeletedObject]?, HKQueryAnchor?, Error?) -> Void = { [weak self] _, samples, _, _, _ in
            guard let samples = samples as? [HKQuantitySample], !samples.isEmpty else { return }
            let uptime = ProcessInfo.processInfo.systemUptime
            let closed: [Epoch] = self?.assemblerQueue.sync {
                samples.flatMap { sample in
                    // Zeitstempel der Probe auf die Systemlaufzeit umrechnen (gleiche Uhr wie CoreMotion).
                    let nanos = Int64((uptime - Date().timeIntervalSince(sample.endDate)) * 1e9)
                    return assembler.addHeartRate(timestampNanos: nanos, bpm: sample.quantity.doubleValue(for: unit))
                }
            } ?? []
            if !closed.isEmpty { Task { @MainActor in self?.feed(closed) } }
        }
        let query = HKAnchoredObjectQuery(type: type, predicate: predicate, anchor: nil, limit: HKObjectQueryNoLimit, resultsHandler: handler)
        query.updateHandler = handler
        health.execute(query)
        heartRateQuery = query
    }

    private func feed(_ closed: [Epoch]) {
        guard case .measuring = phase else { return }
        for epoch in closed {
            epochs.append(epoch)
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
        self.reason = reason
        if firstRing == nil { firstRing = Date() }
        phase = .ringing(reason, snoozesLeft: Snooze.maxCount - snoozes)
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
        if spec?.phoneBackup != false, let plan {
            WatchConnectivityBridge.shared.send(command: SyncKeys.ring, alarmId: plan.alarmId)
        }
    }

    /// Schlummern: Sitzung beenden und neu für in 5 Minuten planen (App ist dabei aktiv).
    func snooze() {
        guard phase.isRinging, Snooze.canSnooze(snoozes), let plan else { return }
        snoozes += 1
        session?.invalidate()
        let until = Snooze.nextRing(Date())
        phase = .snoozed(until: until, snoozesLeft: Snooze.maxCount - snoozes)
        let next = WKExtendedRuntimeSession()
        next.delegate = self
        next.start(at: until)
        session = next
        self.plan = WakePlan(alarmId: plan.alarmId, windowStart: until, target: until)
        WatchConnectivityBridge.shared.send(command: SyncKeys.stop)
    }

    /// Wecker beenden, Nacht speichern, nächste Nacht planen.
    func dismiss(notifyPhone: Bool = true) {
        guard phase.isRinging || phase.isSnoozed else { return }
        session?.invalidate()
        session = nil
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
            WatchConnectivityBridge.shared.send(night: night)
        }
        if notifyPhone { WatchConnectivityBridge.shared.send(command: SyncKeys.stop) }
        if let spec, spec.isOneShot { model.setEnabled(spec, false) }
        phase = .idle
        plan = nil
        nightPlan = nil
        model.reschedule()
    }
}
