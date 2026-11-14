import PhaseWakeCore
import UserNotifications
import WatchKit

extension SmartAlarm {
    // MARK: Wecken

    func ring(_ reason: WakeReason) {
        // Zuerst wecken (wie Android, 13.11.2026): Zustand setzen und vibrieren, erst danach Sensoren, Rückfall, Protokoll
        // und Speichern – sonst verzögert deren Arbeit die erste Vibration. Nur die Zeile zum Messende kommt vorher, denn
        // sie gilt nur in der Phase «messen».
        logMeasureEnd()
        self.reason = reason
        if firstRing == nil { firstRing = Date() }
        phase = .ringing(reason, snoozesLeft: snoozesLeft)
        hapticStart = Date()
        // Steigerung wie auf Android: sanft, deutlich, anhaltend – je eine Minute (`SessionRules.hapticStep`). Klingelt
        // diese Sitzung nicht mehr (gestoppt, geschlummert, ersetzt), endet die Wiederholung hier – auch wenn
        // `invalidate()` im Hintergrund nicht greift (Audit AP-3).
        let ringing = session
        ringing?.notifyUser(hapticType: .notification) { [weak self] haptic in
            let current = self.map { $0.phase.isRinging && $0.session === ringing } ?? false
            let step = SessionRules.hapticStep(elapsed: Date().timeIntervalSince(self?.hapticStart ?? Date()), ringing: current)
            if step.interval > 0 { haptic.pointee = step.type }
            return step.interval
        }
        stopSensors()
        // Der Rückfall bleibt stehen, bis beendet wird: Endet die Sitzung, weckt er trotzdem. Klingelt die Sitzung, fallen
        // nur die noch folgenden Mitteilungen seiner Kette weg (`NotificationChain`).
        if session?.state == .running { removeFollowingFallback(Self.fallbackKey(for: nightPlan?.alarmId ?? plan?.alarmId)) }
        DiagStore.shared.log(.ring, "watch \(reason.rawValue)")
        save()
        // Immer melden: Das iPhone entscheidet (Zweitwecker, Sonnenaufgang, Sicherung aussetzen).
        sendRing()
    }

    /// Ein Rückfall-Wecker (`id`, `phasewake.fallback…`) kommt bei offener App an oder wird angetippt. Hat die Sitzung
    /// seiner Nacht nicht geweckt (geplant, wartend oder messend nach der Zielzeit, Schlummer-Sitzung nicht gestartet),
    /// klingelt die Uhr jetzt selbst: Weckbildschirm mit Stopp und Schlummern, die Nacht wird wie sonst abgeschlossen.
    /// Gehört er zu einer anderen Nacht (Probewecker neben dem echten Wecker, abgebrochene Sitzung), nur vibrieren.
    /// Jede Mitteilung der Kette (`NotificationChain`) gilt wie die erste.
    func fallbackArrived(_ notificationId: String) {
        // Rückfall einer späteren Nacht (`UpcomingFallbacks`, App lief seither nicht): gilt wie der des echten Weckers.
        let id = UpcomingFallbacks.isUpcoming(notificationId) ? Self.fallbackId : NotificationChain.baseId(notificationId)
        let now = Date()
        // Zielzeit da, aber höchstens `ringLimit` zurück (ein alter Rückfall, spät angetippt, weckt nicht mehr).
        func due(_ night: WakePlan) -> Bool {
            Self.fallbackKey(for: night.alarmId) == id && night.target <= now && now < night.target.addingTimeInterval(Self.ringLimit)
        }
        switch phase {
        case .snoozed(let until, _) where Self.fallbackKey(for: nightPlan?.alarmId) == id && until <= now:
            ring(reason)
        case .measuring(let night) where Self.fallbackKey(for: night.alarmId) == id:
            ring(targetReason())
        case .waiting(let night) where due(night), .scheduled(let night) where due(night):
            invalidateSession()
            // Nach einer abgebrochenen Messung derselben Nacht bleiben ihre Epochen (`measuringInterrupted`).
            if nightPlan != night { epochs = [] }
            plan = night
            nightPlan = night
            spec = WatchModel.shared.spec(for: night.alarmId)
            firstRing = nil
            snoozes = 0
            DiagStore.shared.log(.ring, "fallback without session")
            ring(.deadline)
        default:
            break
        }
        guard phase.isRinging, session?.state != .running else {
            if !phase.isRinging { WKInterfaceDevice.current().play(.notification) }
            return
        }
        // Ohne laufende Sitzung vibriert `notifyUser` nicht: solange die App offen ist und es klingelt, selbst vibrieren.
        fallbackHaptic?.invalidate()
        WKInterfaceDevice.current().play(.notification)
        fallbackHaptic = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                guard self.phase.isRinging else {
                    self.fallbackHaptic?.invalidate()
                    self.fallbackHaptic = nil
                    return
                }
                WKInterfaceDevice.current().play(.notification)
            }
        }
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

    /// Ob «stop» bzw. «schlummern» vom iPhone zu der Nacht gehört, die hier klingelt oder schlummert: gleiche Kennung
    /// und Zielzeit (auf 1 s genau; Millisekunden werden unterwegs gekürzt). Fehlende Angaben (ältere iPhone-Version)
    /// widersprechen nicht. So beendet der Zweitwecker einer anderen Nacht diese hier nicht.
    func isThisNight(alarmId: String?, target: Date?) -> Bool {
        if let alarmId, let own = nightPlan?.alarmId ?? plan?.alarmId, alarmId != own { return false }
        if let target, let own = nightPlan?.target, abs(target.timeIntervalSince(own)) >= 1 { return false }
        return true
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
        // Rückfall auch fürs Schlummern (unter der Kennung dieser Nacht): falls die neue Sitzung nicht startet.
        scheduleFallback(at: until.addingTimeInterval(Self.fallbackDelay), id: Self.fallbackKey(for: plan.alarmId))
        save()
        // Mit Schlummer-Ende: Das iPhone hört auf zu klingeln, behält die Nacht aber und stellt 90 s nach dem
        // Schlummer-Ende eine eigene Sicherung (falls die neue Sitzung nicht startet).
        WatchConnectivityBridge.shared.send(command: SyncKeys.stop, alarmId: plan.alarmId, target: nightPlan?.target, snoozeUntil: until)
    }

    /// Wecker beenden, Nacht speichern, nächste Nacht planen.
    func dismiss(notifyPhone: Bool = true) {
        guard phase.isRinging || phase.isSnoozed else {
            // Nach einer abgebrochenen Sitzung ist die Nacht schon abgeschlossen: Ein Stopp (vom iPhone) beendet die Kette.
            removeInterrupted()
            return
        }
        DiagStore.shared.log(.stop, notifyPhone ? "watch" : "phone")
        // Nur den Rückfall dieser Nacht: Beim Probewecker oder Powernap bleibt der des echten Weckers stehen. Mit der
        // ganzen Kette, wartend und schon zugestellt.
        let ids = [Self.fallbackKey(for: nightPlan?.alarmId ?? plan?.alarmId), Self.interruptedId]
        removeFallback(ids)
        removeDeliveredFallback(ids)
        // Schon zugestellte Rückfälle späterer Nächte (die wartenden stellt das Neuplanen danach neu).
        if Transient.kindOf(nightPlan?.alarmId ?? plan?.alarmId) == nil {
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: UpcomingFallbacks.allIds)
        }
        finish(notifyPhone: notifyPhone)
    }

    /// Kette nach abgebrochener Sitzung (`interruptedId`) beenden, wartend und zugestellt: Stopp vom iPhone oder Tipp auf
    /// eine ihrer Mitteilungen (die Nacht ist dann schon abgeschlossen, es gibt keinen Weckbildschirm mehr).
    func removeInterrupted() {
        removeFallback([Self.interruptedId])
        removeDeliveredFallback([Self.interruptedId])
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

    /// Nacht speichern, einmaligen Wecker ausschalten, nächste Nacht planen. `catchUp`: Wecker, die während dieser Nacht
    /// fällig wurden, nachholen (nicht bei einem alten Zustand, ``finishSilently()``).
    func finish(notifyPhone: Bool, catchUp: Bool = true) {
        invalidateSession()
        stopSensors()
        let model = WatchModel.shared
        // Probewecker und Powernap: kein Verlauf, kein «erledigt», kein Ausschalten (`Transient`).
        let transient = Transient.kindOf(nightPlan?.alarmId) != nil
        // «Gute Nacht» hat diese Nacht vorverlegt (vor `markNightDone` gelesen, das sie verfallen lässt): für das Auto-Aus.
        let shifted = nightPlan.map { night in
            model.goodNight.map { $0.alarmId == night.alarmId && TimeBounds.millis($0.target) == TimeBounds.millis(night.target) } ?? false
        } ?? false
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
            // Diese Nacht ist erledigt: nicht noch einmal planen (`markNightDone`).
            markNightDone(nightPlan, model: model)
            // Wecker, die während dieser Nacht fällig wurden, gleich nachholen (wie Wear OS).
            if catchUp { model.noteNightEnded(windowStart: nightPlan.windowStart) }
            WatchConnectivityBridge.shared.send(night: night)
            // Die Aufzeichnung dieser Nacht (Lernphase oder von Hand, Audit F-01) endet mit dem Klingeln.
            LearnRecorder.shared.noteWake(alarmId: nightPlan.alarmId, target: nightPlan.target)
        }
        if notifyPhone { WatchConnectivityBridge.shared.send(command: SyncKeys.stop, alarmId: nightPlan?.alarmId ?? "", target: nightPlan?.target) }
        // Aus dem Speicher statt aus der Nacht: nach einem Neustart der App fehlt `spec`. Nur die Fassung, die geklingelt
        // hat (Fix G-1/S-1): Im Schlummern verschoben, oder eine neuere Änderung vom iPhone, bleibt eingeschaltet.
        if let nightPlan, !transient, let alarm = model.alarms.first(where: { $0.id == nightPlan.alarmId }), alarm.isOneShot, alarm.enabled {
            model.disableAfterRing(alarm.id, rung: spec, target: nightPlan.target, shifted: shifted)
        }
        if transient { model.endTransient(nightPlan?.alarmId) }
        phase = .idle
        plan = nil
        nightPlan = nil
        spec = nil
        clearSaved()
        // Nächste Nacht planen; bei geschlossener App (z. B. am iPhone gestoppt) die Bitte, sie zu öffnen – nach einer
        // echten Nacht wie nach Probewecker und Powernap.
        model.transientEnded()
    }
}
