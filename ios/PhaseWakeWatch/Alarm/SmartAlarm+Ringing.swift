import PhaseWakeCore
import WatchKit

extension SmartAlarm {
    // MARK: Wecken

    func ring(_ reason: WakeReason) {
        if phase.isMeasuring { DiagStore.shared.log(.measureEnd, "\(epochs.count) epochs") }
        stopSensors()
        // Der Rückfall bleibt stehen, bis beendet wird: Endet die Sitzung, weckt er trotzdem.
        self.reason = reason
        if firstRing == nil { firstRing = Date() }
        phase = .ringing(reason, snoozesLeft: snoozesLeft)
        DiagStore.shared.log(.ring, "watch \(reason.rawValue)")
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
    func sendRing() {
        guard let alarmId = nightPlan?.alarmId ?? plan?.alarmId else { return }
        WatchConnectivityBridge.shared.send(command: SyncKeys.ring, alarmId: alarmId, target: nightPlan?.target,
                                            snoozesLeft: snoozesLeft)
    }

    /// Verbleibende Schlummer-Runden; der Probewecker prüft genau einen Weckvorgang und schlummert nie.
    var snoozesLeft: Int {
        Transient.allowsSnooze(nightPlan?.alarmId ?? plan?.alarmId) ? Snooze.maxCount - snoozes : 0
    }

    /// Schlummern am iPhone gewählt: wie auf der Uhr. Geht es nicht mehr, klingelt das iPhone weiter.
    func snoozeFromPhone() {
        guard phase.isRinging else { return }
        if snoozesLeft > 0 { snooze() } else { sendRing() }
    }

    /// Schlummern: Sitzung beenden und neu für in 5 Minuten planen (App ist dabei aktiv).
    func snooze() {
        guard phase.isRinging, snoozesLeft > 0, let plan else { return }
        snoozes += 1
        DiagStore.shared.log(.snooze, "\(snoozes)")
        invalidateSession()
        let until = Snooze.nextRing(Date())
        phase = .snoozed(until: until, snoozesLeft: snoozesLeft)
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
        DiagStore.shared.log(.stop, notifyPhone ? "watch" : "phone")
        removeFallback([Self.fallbackId, Self.interruptedId])
        finish(notifyPhone: notifyPhone)
    }

    /// Sitzung weg oder Zeitgrenze erreicht, während geklingelt oder geschlummert wird: per Mitteilung
    /// weiterwecken (nicht mehr nach der Zeitgrenze) und die Nacht abschliessen. Das iPhone bekommt
    /// die Nacht, aber kein «stop»: Sein Wecker soll weiterklingeln.
    func finishInterrupted() {
        guard phase.isRinging || phase.isSnoozed else { return }
        DiagStore.shared.log(.error, "session ended while ringing")
        if !ringExpired {
            var at = Date()
            if case .snoozed(let until, _) = phase, until > at { at = until.addingTimeInterval(Self.fallbackDelay) }
            scheduleFallback(at: at, id: Self.interruptedId)
        }
        finish(notifyPhone: false)
    }

    /// Nacht speichern, einmaligen Wecker ausschalten, nächste Nacht planen.
    func finish(notifyPhone: Bool) {
        invalidateSession()
        stopSensors()
        let model = WatchModel.shared
        // Probewecker und Powernap: kein Verlauf, kein «erledigt», kein Ausschalten (`Transient`).
        let transient = Transient.kindOf(nightPlan?.alarmId) != nil
        if let nightPlan, let firstRing, !transient {
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
        if transient { model.endTransient(nightPlan?.alarmId) }
        phase = .idle
        plan = nil
        nightPlan = nil
        spec = nil
        clearSaved()
        model.reschedule()
    }
}
