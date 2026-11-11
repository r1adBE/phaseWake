import Foundation
import PhaseWakeCore

/// «Gute Nacht» auf der Apple Watch (Einschlaf-Modus «nach 7½ Std.», Näherung, freigegeben von r1AD 10.11.2026): Die
/// Galaxy Watch misst nach dem Tippen das Einschlafen und legt die Zielzeit danach fest. Eine App auf der Apple Watch
/// kann nachts nicht laufend messen; darum legt das Tippen die Zielzeit gleich fest (``GoodNightEstimate``: Tippen plus
/// gelernte Einschlafdauer, sonst 15 Min, plus Schlafdauer, höchstens die eingestellte Weckzeit) und startet den
/// Bewegungs-Rekorder. Am Morgen rechnet die Uhr die Einschlafzeit aus dem Rekorder nach (`WatchOnset`): für die
/// Untergrenze kurzer Nächte und den Vergleich mit der Schätzung im Diagnose-Protokoll.
///
/// Die vorverlegte Zielzeit gilt für diese eine Nacht (`WatchModel.realPlan`), auch für den Zweitwecker am iPhone
/// (``OnsetShift``). Ohne Tippen gilt die eingestellte Weckzeit (die Einschlafzeit ist auf der Apple Watch erst am Morgen
/// bekannt). Weckt die Nacht bis eine Stunde nach der neuen Zielzeit nicht ab (keine Sitzung, kein Stopp), fällt die
/// Ausnahme weg; dann gilt die eingestellte Weckzeit als späte Sicherung (wie der Zweitwecker auf Android).
struct GoodNightState: Codable, Equatable {
    /// Wecker mit Schlafdauer.
    let alarmId: String
    /// Eingestellte Weckzeit dieser Nacht (spätestens).
    let latest: Date
    /// Zeit des Tippens: Beginn der Einschlafdauer.
    let since: Date
    /// Geschätzte Einschlafzeit.
    let asleep: Date
    /// Zielzeit der Nacht.
    let target: Date

    /// Plan des Weckers `spec` mit der Zielzeit `target`, wenn `plan` dieselbe Nacht ist (gleicher Wecker, Zielzeit
    /// `latest`), die Zielzeit früher liegt und die Nacht weder erledigt noch vorbei ist; sonst `plan`.
    func apply(to plan: WakePlan?, spec: AlarmSpec?, now: Date, doneUntil: [String: Date], ringLimit: TimeInterval) -> WakePlan? {
        guard let plan, let spec, plan.alarmId == alarmId, spec.id == alarmId, plan.target == latest, target < latest,
              !expired(now: now, doneUntil: doneUntil[alarmId], ringLimit: ringLimit) else { return plan }
        return AlarmSchedule.plan(spec, target: target)
    }

    /// Die vorverlegte Nacht `plan` ist fällig, hat aber noch nicht geweckt (Sitzung geplant oder nicht planbar): Sie
    /// bleibt der Plan bis `ringLimit` nach der Zielzeit (wie `WatchRules.pendingNight` für eingestellte Zeiten).
    func pending(_ plan: WakePlan, now: Date, doneUntil: Date?, ringLimit: TimeInterval) -> WakePlan? {
        guard plan.alarmId == alarmId, plan.target == target, target < latest, now >= target,
              !expired(now: now, doneUntil: doneUntil, ringLimit: ringLimit) else { return nil }
        return plan
    }

    /// Vorbei (`ringLimit` nach der Zielzeit) oder erledigt (`doneUntil` bis zur eingestellten Weckzeit).
    func expired(now: Date, doneUntil: Date?, ringLimit: TimeInterval) -> Bool {
        now >= target.addingTimeInterval(ringLimit) || (doneUntil.map { $0 >= latest } ?? false)
    }
}

extension WatchModel {
    /// Nächster Wecker mit Schlafdauer («Gute Nacht» auf der Startseite); nicht, wenn ein Powernap oder Probewecker vorgeht.
    var sleepAlarm: AlarmSpec? {
        guard let plan, Transient.kindOf(plan.alarmId) == nil else { return nil }
        return alarms.first { $0.id == plan.alarmId && $0.sleepDurationMinutes != nil }
    }

    /// Ob «Gute Nacht» für die kommende Nacht gilt.
    var goodNightActive: Bool {
        guard let goodNight, let sleepAlarm else { return false }
        return goodNight.alarmId == sleepAlarm.id
    }

    /// Ob die eingestellte Weckzeit für «Gute Nacht» zu weit weg ist (mehr als 16 Std.).
    var goodNightTooFar: Bool {
        guard sleepAlarm != nil else { return true }
        return GoodNightEstimate.tooFar(now: now, latest: goodNight?.latest ?? realPlan?.target)
    }

    /// «Gute Nacht» antippen: legt die Zielzeit dieser Nacht fest (Schätzung), meldet sie dem iPhone und startet den
    /// Rekorder; nochmals tippen hebt es auf (dann gilt wieder die eingestellte Weckzeit). Nicht, solange eine Nacht
    /// misst, klingelt oder schlummert, und nicht mehr als 16 Std. vor der Weckzeit (Grund im Diagnose-Protokoll).
    func toggleGoodNight() {
        refresh()
        if let state = goodNight, state.alarmId == sleepAlarm?.id {
            setGoodNight(nil)
            DiagStore.shared.log(.measureStart, "onset cancelled")
            if let spec = alarms.first(where: { $0.id == state.alarmId }) {
                WatchConnectivityBridge.shared.send(onsetShift: .cancel(AlarmSchedule.plan(spec, target: state.latest), at: OnsetShift.millis(now)))
            }
            reschedule()
            return
        }
        guard let spec = sleepAlarm, let duration = spec.sleepDurationMinutes, let plan = realPlan, plan.alarmId == spec.id else {
            DiagStore.shared.log(.measureStart, "onset not started: no sleep duration")
            return
        }
        let phase = SmartAlarm.shared.phase
        if phase.isMeasuring || phase.isRinging || phase.isSnoozed {
            DiagStore.shared.log(.measureStart, "onset not started: night running")
            return
        }
        if GoodNightEstimate.tooFar(now: now, latest: plan.target) {
            DiagStore.shared.log(.measureStart, "onset not started: too far ahead")
            return
        }
        let latency = LearnRecorder.shared.snapshot.latencyMinutes
        let target = GoodNightEstimate.target(tap: now, latencyMinutes: latency, sleepDurationMinutes: duration, latest: plan.target)
        let state = GoodNightState(alarmId: spec.id, latest: plan.target, since: now,
                                   asleep: GoodNightEstimate.asleep(tap: now, latencyMinutes: latency), target: target)
        setGoodNight(state)
        DiagStore.shared.log(.measureStart, "onset start (estimate) lat=\(latency.map { "\($0)" } ?? "-") target \(Format.logTime(target)), alarm at \(Format.logTime(plan.target))")
        WatchConnectivityBridge.shared.send(onsetShift: OnsetShift(alarmId: spec.id, original: OnsetShift.millis(plan.target),
                                                                   target: OnsetShift.millis(target), at: OnsetShift.millis(now)))
        // Rekorder für die Einschlafzeit am Morgen; ohne Erlaubnis gilt nur die Schätzung.
        if LearnRecorder.recorderAllowed {
            LearnRecorder.shared.ensureRecorder(until: plan.target.addingTimeInterval(AppleLearnRecord.afterTarget))
        } else {
            DiagStore.shared.log(.measureStart, "onset recorder not started: motion not allowed")
        }
        reschedule()
    }
}
