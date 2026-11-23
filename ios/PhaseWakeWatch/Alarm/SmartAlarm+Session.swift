import PhaseWakeCore
import WatchKit

extension SmartAlarm {
    // MARK: Sitzung übernehmen und beenden

    /// Vom System gestartete Sitzung übernehmen. Muss synchron in `handle(_:)` geschehen,
    /// sonst beendet das System die Sitzung.
    func adopt(_ extendedSession: WKExtendedRuntimeSession) {
        extendedSession.delegate = self
        let own = session.flatMap { $0 === extendedSession ? nil : $0 }
        switch SessionRules.adoption(stale: isStale(extendedSession), own: own?.state) {
        case .stale:
            // Eine gemerkte, nicht mehr gültige Sitzung wird beim Start beendet (`extendedRuntimeSessionDidStart`).
            return
        case .keepOwn:
            // Die eigene, geplante oder laufende Sitzung nie verwerfen: Die ankommende ist dann eine
            // verwaiste aus der Zeit vor einem Neustart der App und endet bei ihrem Start, ohne zu vibrieren.
            DiagStore.shared.log(.error, "other session started, own kept")
            keepStale(extendedSession)
            return
        case .replaceOwn:
            if let own { retire(own) }
        case .take:
            break
        }
        session = extendedSession
    }

    /// Beendet die Sitzung dieser Nacht. Darf die App sie gerade nicht beenden (`SessionRules.retire`), bleibt sie
    /// gemerkt (`staleSessions`) und endet beim nächsten Aktivwerden oder gleich bei ihrem Start.
    func invalidateSession() {
        forgetScheduled()
        keptStart = nil
        guard let old = session else { return }
        session = nil
        retire(old)
    }

    // MARK: Nicht mehr gültige Sitzungen

    /// Sitzung `old` beenden oder für später merken (`SessionRules.retire`). Gemerkte Sitzungen behalten den Delegate:
    /// So meldet sich ein Start oder ihr Ende, und sie werden dann erkannt (`isStale`, `dropStale`).
    func retire(_ old: WKExtendedRuntimeSession) {
        let active = WKApplication.shared().applicationState == .active
        switch SessionRules.retire(old.state, active: active) {
        case .drop:
            old.delegate = nil
        case .now:
            old.delegate = nil
            old.invalidate()
        case .tryNowAndKeep:
            keepStale(old)
            old.invalidate()
        case .whenActive:
            keepStale(old)
        }
    }

    func keepStale(_ old: WKExtendedRuntimeSession) {
        guard !isStale(old) else { return }
        staleSessions.append(old)
        DiagStore.shared.log(.scheduled, "session end pending (\(old.state.rawValue))")
    }

    /// Ob `candidate` eine gemerkte, nicht mehr gültige Sitzung ist.
    func isStale(_ candidate: WKExtendedRuntimeSession) -> Bool {
        staleSessions.contains { $0 === candidate }
    }

    /// Ende einer gemerkten Sitzung: vergessen. - Returns: ob `ended` gemerkt war.
    func dropStale(_ ended: WKExtendedRuntimeSession) -> Bool {
        guard isStale(ended) else { return false }
        staleSessions.removeAll { $0 === ended }
        ended.delegate = nil
        return true
    }

    /// Gemerkte Sitzung `started` ist gestartet: Übernimmt sie die aktuelle Nacht
    /// (``SessionRules/usesOrphan(own:busy:serves:)``), wird sie die Sitzung dieser Nacht, die eigene (nur geplante)
    /// gilt als ersetzt, und die Nacht misst und weckt mit Vibration. - Returns: ob übernommen.
    func usesStale(_ started: WKExtendedRuntimeSession) -> Bool {
        let model = WatchModel.shared
        model.refresh()
        let now = Date()
        let serves = model.plan.map {
            NightRules.sessionServes(start: now, plan: $0, sessionStart: Self.sessionStart($0),
                                     tolerance: Self.startTolerance, delay: Self.fallbackDelay)
        } ?? false
        let own = session.flatMap { $0 === started ? nil : $0 }
        guard SessionRules.usesOrphan(own: own?.state, busy: SessionRules.busy(phase), serves: serves) else {
            return false
        }
        staleSessions.removeAll { $0 === started }
        if let own { retire(own) }
        session = started
        plan = nil
        keptStart = nil
        DiagStore.shared.log(.window, "stale session started, used for the night")
        startNight()
        return true
    }

    /// App aktiv: gemerkte Sitzungen jetzt beenden (Aufruf bei jedem Aktivwerden, `AppDelegate`).
    func invalidateStaleSessions() {
        guard WKApplication.shared().applicationState == .active, !staleSessions.isEmpty else { return }
        let stale = staleSessions
        staleSessions = []
        for old in stale where old.state != .invalid {
            old.delegate = nil
            old.invalidate()
        }
        DiagStore.shared.log(.scheduled, "\(stale.count) pending session end(s) done")
    }

    // MARK: WKExtendedRuntimeSessionDelegate

    nonisolated func extendedRuntimeSessionDidStart(_ extendedRuntimeSession: WKExtendedRuntimeSession) {
        // Das System ruft die Sitzungs-Rückmeldungen auf dem Hauptthread; weitergereicht wird sie nur an den MainActor.
        nonisolated(unsafe) let runtimeSession = extendedRuntimeSession
        Task { @MainActor in
            // Verwaiste Sitzung (ersetzt, als die App sie nicht beenden durfte): Kann sie die Nacht übernehmen, weckt
            // sie; sonst gleich beenden, ohne zu vibrieren (keine Nacht zu wecken; Apple kann dann warnen).
            if self.isStale(runtimeSession), self.usesStale(runtimeSession) { return }
            if self.isStale(runtimeSession) {
                DiagStore.shared.log(.error, "stale session started, ended")
                if self.session === runtimeSession { self.session = nil }
                runtimeSession.invalidate()
                return
            }
            guard runtimeSession === self.session || self.session == nil else { return }
            self.session = runtimeSession
            DiagStore.shared.log(.window, "session started")
            switch SessionRules.started(self.phase) {
            case .ringAgain:
                // Nach dem Schlummern startet die neue Sitzung direkt mit dem Wecken.
                self.ring(self.reason)
            case .alreadyRunning:
                break
            case .startNight:
                if self.replansAfterZoneChange(runtimeSession) { return }
                self.startNight()
            }
        }
    }

    /// Reise: Hat sich die Ortszeit seit dem Planen verschoben (`WatchRules.zoneShifted`), startete die Sitzung zur
    /// alten Zeit, der Rückfall weckt aber zur neuen Ortszeit – es weckte zweimal. Hat das Weckfenster der Nacht schon
    /// begonnen und gilt ihr Wecker in der Zone der Planung unverändert (`keptNight`), misst sie zur geplanten Zeit,
    /// mit festem Rückfall. Sonst neu planen statt messen: Passt die Sitzung danach nicht mehr (anderer Plan, App nicht
    /// aktiv), endet sie, und es weckt der Rückfall zur Ortszeit.
    /// - Returns: ob die Sitzung hier behandelt ist (gehaltene Nacht misst, oder neu geplant und beendet).
    func replansAfterZoneChange(_ started: WKExtendedRuntimeSession) -> Bool {
        let saved = Self.savedPlanZone
        guard WatchRules.zoneShifted(plannedZone: saved?.zone, target: saved?.target, zone: .current) else {
            return false
        }
        // Nach einem Neustart der App ist der Plan unbekannt (`idle`): dann die gemerkte Nacht.
        let night = SessionRules.plannedNight(phase)
        WatchModel.shared.refresh()
        if let kept = keptNight(night) {
            holdFallback(night ?? kept)
            beginMeasuring(night ?? kept)
            return true
        }
        DiagStore.shared.log(.scheduled, "zone changed since session was planned, replan")
        SettingsFile.watchSession.removeObject(forKey: Self.sessionZoneKey)
        WatchModel.shared.reschedule()
        let scheduled: Bool = if case .scheduled = phase { true } else { false }
        switch SessionRules.replanned(scheduled: scheduled, sameSession: session === started) {
        case .proceed:
            return false
        case .endSession:
            invalidateSession()
            return true
        case .done:
            return true
        }
    }

    nonisolated func extendedRuntimeSessionWillExpire(_ extendedRuntimeSession: WKExtendedRuntimeSession) {
        // Kurz vor Ablauf (spätestens nach 30 Min): jetzt wecken, sonst verwirft das System den Alarm.
        nonisolated(unsafe) let runtimeSession = extendedRuntimeSession
        Task { @MainActor in
            guard runtimeSession === self.session, self.phase.isMeasuring else { return }
            self.ring(self.targetReason())
        }
    }

    nonisolated func extendedRuntimeSession(
        _ extendedRuntimeSession: WKExtendedRuntimeSession,
        didInvalidateWith reason: WKExtendedRuntimeSessionInvalidationReason,
        error: Error?
    ) {
        let code = (error as NSError?).map { " \($0.domain) \($0.code)" } ?? ""
        nonisolated(unsafe) let runtimeSession = extendedRuntimeSession
        Task { @MainActor in
            if self.dropStale(runtimeSession) {
                DiagStore.shared.log(.window, "stale session ended \(SessionRules.reasonName(reason))\(code)")
                return
            }
            guard runtimeSession === self.session else { return }
            self.session = nil
            self.stopSensors()
            // Grund und Fehlercode ins Protokoll.
            let ending = SessionRules.ending(reason, ringing: self.phase.isRinging)
            DiagStore.shared.log(
                ending == .dismissed ? .stop : .error, "session invalidated \(SessionRules.reasonName(reason))\(code)"
            )
            switch SessionRules.afterEnd(self.phase, ending: ending) {
            case .dismiss:
                // Weckhinweis des Systems gestoppt: wie Stopp auf der Uhr – iPhone hört auf, die Kette fällt weg.
                self.dismiss()
                return
            case .wait(let plan):
                // Vom System abgelehnt oder zurückgezogen: Der Rückfall weckt; beim nächsten Öffnen neu planen.
                self.phase = .waiting(plan)
            case .measuringInterrupted(let plan):
                self.measuringInterrupted(plan)
            case .finishInterrupted:
                // Abgelaufen oder vom System beendet: weiterwecken und die Nacht abschliessen.
                self.finishInterrupted()
                return
            case .nothing:
                break
            }
            self.publishArmed()
        }
    }
}
