import PhaseWakeCore
import UserNotifications
import WatchKit

extension SmartAlarm {
    // MARK: Wecken

    func ring(_ reason: WakeReason) {
        // Zuerst wecken (wie Android, 13.11.2026): Zustand setzen und vibrieren, erst danach Sensoren, Rückfall,
        // Protokoll und Speichern – sonst verzögert deren Arbeit die erste Vibration. Nur die Zeile zum Messende kommt
        // vorher, denn sie gilt nur in der Phase «messen».
        logMeasureEnd()
        self.reason = reason
        if firstRing == nil { firstRing = Date() }
        phase = .ringing(reason, snoozesLeft: snoozesLeft)
        hapticStart = Date()
        // Steigerung wie auf Android: sanft, deutlich, anhaltend – je eine Minute (`SessionRules.hapticStep`). Klingelt
        // diese Sitzung nicht mehr (gestoppt, geschlummert, ersetzt), endet die Wiederholung hier – auch wenn
        // `invalidate()` im Hintergrund nicht greift.
        let ringing = session
        ringing?.notifyUser(hapticType: .notification) { [weak self] haptic in
            let current = self.map { $0.phase.isRinging && $0.session === ringing } ?? false
            let elapsed = Date().timeIntervalSince(self?.hapticStart ?? Date())
            let step = SessionRules.hapticStep(elapsed: elapsed, ringing: current)
            if step.interval > 0 { haptic.pointee = step.type }
            return step.interval
        }
        stopSensors()
        // Der Rückfall bleibt stehen, bis beendet wird: Endet die Sitzung, weckt er trotzdem. Klingelt die Sitzung,
        // fallen nur die noch folgenden Mitteilungen seiner Kette weg (`NotificationChain`).
        if session?.state == .running {
            removeFollowingFallback(Self.fallbackKey(for: nightPlan?.alarmId ?? plan?.alarmId))
        }
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
        let id = RingingRules.arrivedId(notificationId)
        let now = Date()
        // Zielzeit da, aber höchstens `ringLimit` zurück (ein alter Rückfall, spät angetippt, weckt nicht mehr).
        func due(_ night: WakePlan) -> Bool {
            RingingRules.fallbackDue(night, key: Self.fallbackKey(for: night.alarmId), id: id, now: now,
                                     limit: Self.ringLimit)
        }
        switch phase {
        case .snoozed(let until, _)
            where RingingRules.endsSnooze(until: until, key: Self.fallbackKey(for: nightPlan?.alarmId), id: id,
                                          now: now):
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
        switch RingingRules.fallbackHaptic(ringing: phase.isRinging, session: session?.state) {
        case .silent:
            return
        case .once:
            WKInterfaceDevice.current().play(.notification)
            return
        case .repeating:
            break
        }
        // Ohne laufende Sitzung vibriert `notifyUser` nicht: solange die App offen ist und es klingelt, selbst
        // vibrieren.
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
        RingingRules.snoozesLeft(alarmId: nightPlan?.alarmId ?? plan?.alarmId, snoozes: snoozes)
    }

    /// Ob «stop» bzw. «schlummern» vom iPhone zu der Nacht gehört, die hier klingelt oder schlummert: gleiche Kennung
    /// und Zielzeit (auf 1 s genau; Millisekunden werden unterwegs gekürzt). Fehlende Angaben (ältere iPhone-Version)
    /// widersprechen nicht. So beendet der Zweitwecker einer anderen Nacht diese hier nicht.
    func isThisNight(alarmId: String?, target: Date?) -> Bool {
        RingingRules.isThisNight(alarmId: alarmId, target: target, ownAlarmId: nightPlan?.alarmId ?? plan?.alarmId,
                                 ownTarget: nightPlan?.target)
    }

    /// Schlummern am iPhone gewählt: wie auf der Uhr. Geht es nicht mehr, klingelt das iPhone weiter.
    func snoozeFromPhone() {
        switch RingingRules.snoozeChoice(ringing: phase.isRinging, snoozesLeft: snoozesLeft) {
        case .ignore: return
        case .snooze: snooze()
        case .keepRinging: sendRing()
        }
    }

    /// Schlummern: Sitzung beenden und neu für in 5 Minuten planen (App ist dabei aktiv).
    func snooze() {
        guard RingingRules.snoozeChoice(ringing: phase.isRinging, snoozesLeft: snoozesLeft) == .snooze,
              let plan else { return }
        snoozes += 1
        DiagStore.shared.log(.snooze, "\(snoozes)")
        invalidateSession()
        let round = RingingRules.snoozeRound(alarmId: plan.alarmId, now: Date(), delay: Self.fallbackDelay)
        let until = round.until
        phase = .snoozed(until: until, snoozesLeft: snoozesLeft)
        let next = WKExtendedRuntimeSession()
        next.delegate = self
        next.start(at: until)
        session = next
        self.plan = round.plan
        // Rückfall auch fürs Schlummern (unter der Kennung dieser Nacht): falls die neue Sitzung nicht startet.
        scheduleFallback(at: round.fallbackAt, id: Self.fallbackKey(for: plan.alarmId))
        save()
        // Mit Schlummer-Ende: Das iPhone hört auf zu klingeln, behält die Nacht aber und stellt 90 s nach dem
        // Schlummer-Ende eine eigene Sicherung (falls die neue Sitzung nicht startet).
        WatchConnectivityBridge.shared.send(
            command: SyncKeys.stop, alarmId: plan.alarmId, target: nightPlan?.target, snoozeUntil: until
        )
    }

    /// Wecker beenden, Nacht speichern, nächste Nacht planen.
    func dismiss(notifyPhone: Bool = true) {
        let alarmId = nightPlan?.alarmId ?? plan?.alarmId
        guard case .night(let clearsUpcoming) = RingingRules.dismissal(
            ringing: phase.isRinging, snoozed: phase.isSnoozed, alarmId: alarmId
        ) else {
            // Nach einer abgebrochenen Sitzung ist die Nacht schon abgeschlossen: Ein Stopp (vom iPhone) beendet die
            // Kette.
            removeInterrupted()
            return
        }
        DiagStore.shared.log(.stop, notifyPhone ? "watch" : "phone")
        // Nur den Rückfall dieser Nacht: Beim Probewecker oder Powernap bleibt der des echten Weckers stehen. Mit der
        // ganzen Kette, wartend und schon zugestellt.
        let ids = [Self.fallbackKey(for: alarmId), Self.interruptedId]
        removeFallback(ids)
        removeDeliveredFallback(ids)
        // Schon zugestellte Rückfälle späterer Nächte (die wartenden stellt das Neuplanen danach neu).
        if clearsUpcoming {
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: UpcomingFallbacks.allIds)
        }
        finish(notifyPhone: notifyPhone)
    }

    /// Kette nach abgebrochener Sitzung (`interruptedId`) beenden, wartend und zugestellt: Stopp vom iPhone oder Tipp
    /// auf eine ihrer Mitteilungen (die Nacht ist dann schon abgeschlossen, es gibt keinen Weckbildschirm mehr).
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
        var snoozedUntil: Date?
        if case .snoozed(let until, _) = phase { snoozedUntil = until }
        if let at = RingingRules.interruptedRingWake(expired: ringExpired, snoozedUntil: snoozedUntil, now: Date(),
                                                     delay: Self.fallbackDelay) {
            scheduleFallback(at: at, id: Self.interruptedId)
        }
        finish(notifyPhone: false)
    }

    /// Nacht speichern, einmaligen Wecker ausschalten, nächste Nacht planen. `catchUp`: Wecker, die während dieser
    /// Nacht fällig wurden, nachholen (nicht bei einem alten Zustand, ``finishSilently()``).
    func finish(notifyPhone: Bool, catchUp: Bool = true) {
        invalidateSession()
        stopSensors()
        let model = WatchModel.shared
        // Probewecker und Powernap: kein Verlauf, kein «erledigt», kein Ausschalten (`Transient`).
        let end = RingingRules.finish(night: nightPlan, firstRing: firstRing)
        // «Gute Nacht» hat diese Nacht vorverlegt (vor `markNightDone` gelesen, das sie verfallen lässt): für das
        // Auto-Aus.
        let shifted = RingingRules.shiftedByGoodNight(nightPlan, goodNightAlarmId: model.goodNight?.alarmId,
                                                      goodNightTarget: model.goodNight?.target)
        if end.records, let nightPlan, let firstRing {
            let night = NightRecord(
                alarmId: nightPlan.alarmId,
                windowStart: nightPlan.windowStart,
                target: nightPlan.target,
                wokeAt: firstRing,
                reason: reason,
                snoozes: snoozes,
                epochs: epochs,
                // Akku je Nacht: Ende jetzt, die Punkte reisen mit der Nacht zum iPhone; ebenso die
                // Einschlafzeit samt Beginn, nur zusammen.
                watchBattery: WatchBattery.end(),
                bedAt: asleepAt == nil ? nil : onsetBedAt,
                asleepAt: asleepAt
            )
            model.lastNight.add(night)
            // Diese Nacht ist erledigt: nicht noch einmal planen (`markNightDone`).
            markNightDone(nightPlan, model: model)
            // Wecker, die während dieser Nacht fällig wurden, gleich nachholen (wie Wear OS).
            if catchUp { model.noteNightEnded(windowStart: nightPlan.windowStart) }
            WatchConnectivityBridge.shared.send(night: night)
            // Die Aufzeichnung dieser Nacht (Lernphase oder von Hand) endet mit dem Klingeln.
            LearnRecorder.shared.noteWake(alarmId: nightPlan.alarmId, target: nightPlan.target)
        }
        // Ohne Nacht im Verlauf (nicht geweckt) gehört der Akku zu nichts; Probewecker und Powernap haben keinen
        // begonnen.
        if RingingRules.dropsBattery(firstRing: firstRing, transient: end.transient) {
            WatchBattery.drop("night ended without ring")
        }
        if notifyPhone {
            WatchConnectivityBridge.shared.send(
                command: SyncKeys.stop, alarmId: nightPlan?.alarmId ?? "", target: nightPlan?.target
            )
        }
        // Aus dem Speicher statt aus der Nacht: nach einem Neustart der App fehlt `spec`. Nur die Fassung, die
        // geklingelt hat: Im Schlummern verschoben, oder eine neuere Änderung vom iPhone, bleibt eingeschaltet.
        if let nightPlan, !end.transient, let alarm = model.alarms.first(where: { $0.id == nightPlan.alarmId }),
           RingingRules.disablesAfterRing(alarm, transient: end.transient) {
            model.disableAfterRing(alarm.id, rung: spec, target: nightPlan.target, shifted: shifted)
        }
        if end.transient { model.endTransient(nightPlan?.alarmId) }
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
