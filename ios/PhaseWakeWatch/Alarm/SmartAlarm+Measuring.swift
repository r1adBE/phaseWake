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
        // Die geplante Sitzung gehört zum nächsten Plan oder zum echten Wecker, dessen Sitzung ein vorübergehender
        // Plan bei geschlossener App nicht verdrängt hat (`keepsRealSession`).
        let candidates = [model.plan, model.realPlan].compactMap { $0 }
        let matching: WakePlan? = if let plan { candidates.contains(plan) ? plan : nil } else { model.plan }
        // Ein nachgeholter Wecker oder eine ausstehende Nacht (Zielzeit schon vorbei) gilt trotzdem: Er klingelt
        // sofort (`beginMeasuring`).
        let missed = matching != nil && (matching == model.missedPlan || matching == model.pendingNight)
        guard let current = matching,
              now >= Self.sessionStart(current).addingTimeInterval(-Self.startTolerance),
              missed || now < current.target.addingTimeInterval(Self.fallbackDelay) else {
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
        spec = model.spec(for: plan.alarmId)
        // Gelernte Werte der Lernphase (Grundrauschen, Ruhepuls) wie auf der Galaxy Watch.
        let calibrated = Calibration.calibrate(nights: NightHistory.motionsForCalibration(model.storage.nights()))
        detector = WakeDetector(config: LearnRecorder.shared.snapshot.applied(to: calibrated))
        epochs = []
        unmeasured = max(0, Int(Date().timeIntervalSince(plan.windowStart)) / DetectorConfig.epochSeconds)
        firstRing = nil
        snoozes = 0
        asleepAt = nil
        onsetMeasured = false
        heldWake = nil
        earlyBlocked = false
        goalMinutes = spec?.sleepDurationMinutes ?? model.sleepGoal
        phase = .measuring(plan)
        publishArmed()
        // Einschlafzeit aus dem Rekorder (Untergrenze kurzer Nächte), abseits der Messung. Bis sie da ist, wartet ein
        // früher Moment (`heldWake`); kommt nach `WatchOnset.timeout` kein Ergebnis, gilt die Rechnung als gescheitert.
        let config = detector.config
        let nightSpec = spec
        onsetPending = true
        onsetRun += 1
        let run = onsetRun
        Task { @MainActor in
            let result = await WatchOnset.compute(plan: plan, spec: nightSpec, config: config, goodNight: model.goodNight,
                                                  sleepGoal: model.sleepGoal)
            self.onsetDone(result, run: run, plan: plan)
        }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(WatchOnset.timeout))
            guard self.onsetPending, self.onsetRun == run, self.phase == .measuring(plan) else { return }
            DiagStore.shared.log(.error, "onset computation timed out")
            self.onsetDone(.failed, run: run, plan: plan)
        }
        // Ab Fensterbeginn gilt die geplante Zeit (`AlarmSchedule.begunNight`): Rückfall jetzt zum festen Zeitpunkt.
        // Als Kalenderzeit ohne Zone läge er nach einer Reise nach Osten schon zurück (iOS stellte ihn womöglich sofort zu,
        // dann weckte die Sitzung zur geplanten Zeit ein zweites Mal) oder rutschte auf morgen.
        let pending = plan == model.pendingNight
        if let at = WatchRules.fallbackDate(plan, pending: pending, now: Date(), delay: Self.fallbackDelay) {
            scheduleFallback(at: at, id: Self.fallbackKey(for: plan.alarmId), fixed: true)
        }
        DiagStore.shared.log(.measureStart, "\(plan.alarmId.prefix(8)) \(Format.time(plan.windowStart))–\(Format.time(plan.target)) unmeasured \(unmeasured)\(exactNight ? " exact" : "")")

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
                self.ring(self.targetReason())
            }
        }
    }

    func cancelMeasuring() {
        logMeasureEnd(" cancelled")
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
            // «Genau»: nur für den Verlauf messen, nie vor der Zielzeit wecken – die Erkennung entscheidet nicht.
            if exactNight { continue }
            if case .wake(let reason) = detector.onEpoch(epoch) {
                // Einschlafzeit noch in Rechnung: Moment merken und entscheiden, sobald sie da ist (`onsetDone`).
                if onsetPending {
                    detector.rearm()
                    if heldWake == nil {
                        heldWake = reason
                        DiagStore.shared.log(.phase, "\(reason.rawValue) held, onset pending")
                    }
                    continue
                }
                // Kurze Nacht (wie Android): Moment übergehen (nicht nachholen), weiter erkennen; in den letzten
                // `ShortNight.lateMinutes` vor der Zielzeit weckt ein neuer Moment.
                if blocksEarly() {
                    detector.rearm()
                    noteBlocked(reason)
                    continue
                }
                DiagStore.shared.log(.phase, reason.rawValue)
                ring(reason)
                return
            }
        }
    }

    /// Ob ein früher Moment jetzt übergangen wird (kurze Nacht, `ShortNight.blocksEarly`; ohne Einschlafzeit nach einer
    /// Messung ohne Ergebnis fail-closed).
    func blocksEarly() -> Bool {
        guard let target = nightPlan?.target else { return false }
        return ShortNight.blocksEarly(onset: asleepAt, now: Date(), sleepGoalMinutes: goalMinutes, target: target, measured: onsetMeasured)
    }

    /// Früher Moment `reason` übergangen: einmal je Nacht ins Protokoll; zur Zielzeit weckt es mit `shortNight`.
    func noteBlocked(_ reason: WakeReason) {
        if !earlyBlocked {
            let why = asleepAt == nil ? "short night unknown" : "short night"
            DiagStore.shared.log(.phase, "\(reason.rawValue) ignored, \(why)")
        }
        earlyBlocked = true
    }

    /// Rechnung der Einschlafzeit fertig (oder abgelaufen, `WatchOnset.timeout`): Stand übernehmen und einen
    /// gemerkten frühen Moment jetzt entscheiden. Ein überholtes Ergebnis (`run`) oder eine andere Nacht gilt nicht.
    func onsetDone(_ result: WatchOnset.Result, run: Int, plan: WakePlan) {
        guard onsetPending, onsetRun == run, phase == .measuring(plan) else { return }
        onsetPending = false
        switch result {
        case .asleep(let at): asleepAt = at
        case .notMeasured: break
        case .failed: onsetMeasured = true
        }
        guard let held = heldWake else { return }
        heldWake = nil
        if exactNight { return }
        if blocksEarly() {
            noteBlocked(held)
            return
        }
        DiagStore.shared.log(.phase, held.rawValue)
        ring(held)
    }

    /// Zusammenfassung am Ende einer Messung, einmal je Nacht: Epochen gesamt und davon mit Puls («hr epochs N/M») sowie
    /// die Kennzahlen der Schwellen (``NoiseFloor/summary(config:motions:)``: Grundrauschen, wirksame Leicht- und
    /// Wach-Schwelle am Ende), ohne einzelne Messwerte. Nur während der Messung (`measuring`); `note` ergänzt den Grund
    /// (z. B. abgebrochen).
    func logMeasureEnd(_ note: String = "") {
        guard phase.isMeasuring else { return }
        let withHeartRate = epochs.filter { $0.heartRate != nil }.count
        let summary = NoiseFloor.summary(config: detector.config, motions: epochs.map(\.motion))
        DiagStore.shared.log(.measureEnd, "\(epochs.count) epochs, hr epochs \(withHeartRate)/\(epochs.count), \(summary)\(note)")
    }

    /// Wecker «Genau» (`WakePlan.exact` bzw. `AlarmSpec.isExact`): misst, weckt aber erst zur Zielzeit.
    var exactNight: Bool { nightPlan?.exact == true || spec?.isExact == true }

    /// Grund beim Wecken zur Zielzeit (Zeitgeber oder Ende der Sitzung): «Genau», kurze Nacht (ein früher Moment wurde
    /// übergangen) oder, was die Erkennung meldet (wie `WakeRules.deadlineReason`, Kotlin).
    func targetReason() -> WakeReason {
        if exactNight { return .exact }
        if earlyBlocked { return .shortNight }
        return detector.finish()
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
