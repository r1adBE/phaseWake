import Foundation
import PhaseWakeCore
import UserNotifications

extension SmartAlarm {
    // MARK: Gemerkte Sitzung (Vollaudit AP2-1)

    /// Schlüssel der gemerkten geplanten Sitzung in `SettingsFile.watchSession` (``NightRules/ScheduledSession``).
    static let scheduledKey = "scheduledSession"

    /// Merkt die eben mit `start(at:)` geplante Sitzung für `plan`.
    func noteScheduled(_ plan: WakePlan, start: Date) {
        let saved = NightRules.ScheduledSession(alarmId: plan.alarmId, target: plan.target, start: start)
        SettingsFile.watchSession.set(saved.encoded, forKey: Self.scheduledKey)
    }

    /// Vergisst die gemerkte Sitzung (beendet, ersetzt oder gestartet).
    func forgetScheduled() {
        SettingsFile.watchSession.removeObject(forKey: Self.scheduledKey)
    }

    /// Nach einem Neustart der App kennt sie die geplante Sitzung nicht mehr, das System behält sie aber und startet sie
    /// (`AppDelegate.handle`). Steht die gemerkte Sitzung noch für `plan` (``NightRules/keepsScheduled``), gilt die Uhr als
    /// scharf (`scheduled`), ohne eine zweite Sitzung zu planen – auch bei offener App, denn die alte lässt sich ohne
    /// Verweis nicht beenden. - Returns: ob so behandelt (dann endet `schedule` hier).
    func keepsRestoredSession(for plan: WakePlan) -> Bool {
        guard session == nil else { return false }
        let saved = NightRules.ScheduledSession.decode(SettingsFile.watchSession.object(forKey: Self.scheduledKey))
        guard NightRules.keepsScheduled(saved, plan: plan, start: Self.sessionStart(plan), now: Date(),
                                        tolerance: Self.startTolerance) else { return false }
        if phase != .scheduled(plan) {
            DiagStore.shared.log(.scheduled, "kept session \(Format.logTime(Self.sessionStart(plan))) after restart")
        }
        self.plan = plan
        phase = .scheduled(plan)
        notePlanZone(plan)
        publishArmed()
        return true
    }

    // MARK: «stop» vom iPhone (Vollaudit AP2-5)

    /// «stop» vom iPhone (schon auf dieselbe Nacht geprüft, `isThisNight`). Klingelt oder schlummert die Uhr: beenden wie
    /// bisher. Sonst hat der Zweitwecker des iPhones geweckt: Gehört der Stopp zur geplanten, wartenden, messenden oder
    /// ausstehenden Nacht (``NightRules/phoneStoppedNight``), ist sie erledigt wie auf Android – Rückfälle samt Kette
    /// weg (wartend und zugestellt), nicht nochmals planen; eine Messung endet mit ihren Epochen im Verlauf.
    func stopFromPhone(alarmId: String?, target: Date?) {
        if phase.isRinging || phase.isSnoozed {
            dismiss(notifyPhone: false)
            return
        }
        // Nach einer abgebrochenen Sitzung ist die Nacht schon abgeschlossen: Ein Stopp beendet die Kette.
        removeInterrupted()
        let model = WatchModel.shared
        var current: WakePlan?
        switch phase {
        case .waiting(let night), .scheduled(let night), .measuring(let night): current = night
        case .idle, .ringing, .snoozed: current = nil
        }
        guard let night = NightRules.phoneStoppedNight([current, model.pendingNight], alarmId: alarmId, target: target,
                                                       now: Date(), tolerance: Self.startTolerance) else { return }
        DiagStore.shared.log(.stop, "phone, watch not ringing")
        let ids = [Self.fallbackKey(for: night.alarmId)]
        removeFallback(ids)
        removeDeliveredFallback(ids)
        if case .measuring(let running) = phase, running == night {
            // Wie ein Stopp beim Klingeln: Nacht mit ihren Epochen abschliessen (Weckzeit jetzt).
            logMeasureEnd(" stopped on phone")
            if firstRing == nil { firstRing = Date() }
            reason = .deadline
            finish(notifyPhone: false)
            return
        }
        if Transient.kindOf(night.alarmId) == nil {
            markNightDone(night, model: model)
        } else {
            model.endTransient(night.alarmId)
        }
        invalidateSession()
        phase = .idle
        plan = nil
        model.reschedule()
    }

    /// Nacht `night` erledigt: nicht noch einmal planen, auch wenn die Zielzeit noch vorn liegt – nach «Gute Nacht» bis
    /// zur eingestellten Weckzeit derselben Nacht (wie `WakeRules.doneUntil`, Kotlin).
    func markNightDone(_ night: WakePlan, model: WatchModel) {
        let nominal = model.alarms.first { $0.id == night.alarmId }.flatMap {
            AlarmSchedule.nextTarget($0, now: night.target.addingTimeInterval(-0.001), calendar: .gregorianLocal,
                                     holidays: model.storage.holidays.dates)
        }
        model.storage.markDone(alarmId: night.alarmId, until: GoodNightEstimate.doneUntil(target: night.target, nominal: nominal))
    }

    // MARK: Alter Klingel-Zustand (Vollaudit AP2-9)

    /// Klingel-Zustand älter als ``ringStateMaxAge`` nach der Zielzeit (z. B. App lange nicht gestartet): nicht mehr
    /// wecken, die Nacht aber abschliessen – Epochen in den Verlauf, erledigt, Einmal-Wecker aus. Ohne Nachholen anderer
    /// Wecker (`noteNightEnded`): Sie wären längst vorbei.
    func finishSilently() {
        guard phase.isRinging || phase.isSnoozed else { return }
        DiagStore.shared.log(.error, "old ring state, night finished without waking")
        finish(notifyPhone: false, catchUp: false)
    }

    /// So lange nach der Zielzeit weckt ein gesicherter Klingel-Zustand beim Start noch (``NightRules/restored``).
    static let ringStateMaxAge: TimeInterval = 2 * 3600
}
