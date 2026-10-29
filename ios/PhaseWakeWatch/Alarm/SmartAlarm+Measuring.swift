import CoreMotion
import HealthKit
import PhaseWakeCore
import WatchKit

extension SmartAlarm {
    // MARK: Messen

    /// Prüft beim Start, ob die Sitzung zum aktuellen Plan passt. Eine verwaiste Sitzung (App-Prozess
    /// beendet, Wecker danach verschoben oder ausgeschaltet) misst nicht, sondern wird beendet.
    func startNight() {
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

    func beginMeasuring(_ plan: WakePlan) {
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

    func cancelMeasuring() {
        stopSensors()
        invalidateSession()
        phase = .idle
        plan = nil
        nightPlan = nil
    }

    func startHeartRate(assembler: EpochAssembler) {
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

    func feed(_ closed: [Epoch]) {
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

    func stopSensors() {
        motion.stopAccelerometerUpdates()
        if let heartRateQuery { health.stop(heartRateQuery) }
        heartRateQuery = nil
        ticker?.invalidate()
        ticker = nil
        deadline?.invalidate()
        deadline = nil
    }
}
