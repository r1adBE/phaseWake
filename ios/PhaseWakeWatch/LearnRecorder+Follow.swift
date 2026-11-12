import Foundation
import PhaseWakeCore

extension LearnRecorder {
    /// Wecker derselben Nacht verschoben (Neuplanen, `plan` = `WatchModel.realPlan`): Eine laufende Aufzeichnung der
    /// Lernphase, die noch nicht geweckt hat, folgt seiner Zielzeit (``AppleLearnRecord/followedTarget(current:planTarget:now:)``,
    /// Entscheid r1AD 13.11.2026, wie `RecordControl.followPlan` auf der Galaxy Watch): Wecker, Zielzeit und Ende der
    /// Auswertung (Zielzeit plus ``AppleLearnRecord/afterTarget``) gehen mit. Wecker aus oder übersprungen: Sie endet zur
    /// bisherigen Zielzeit.
    ///
    /// Der Rekorder bleibt beim einen Aufruf dieser Nacht (``AppleNightRecorder``): Reicht er nicht bis zum neuen Ende,
    /// steht das einmal im Diagnose-Protokoll (``ensureRecorder(until:)``); ausgewertet wird trotzdem bis zur neuen
    /// Zielzeit, Epochen nach seinem Ende bleiben ohne Bewegung.
    /// - Returns: ob die Aufzeichnung nachgeführt wurde.
    func follow(_ plan: WakePlan) -> Bool {
        let now = Date()
        guard var current = mark, current.manual != true, current.wokeAt == nil, current.stoppedAt == nil,
              now < Self.end(of: current) else { return false }
        let target = AppleLearnRecord.followedTarget(current: current.target, planTarget: plan.target, now: now)
        guard target != current.target else { return false }
        DiagStore.shared.log(.scheduled, "learn record until \(Format.logTime(target)) (was \(Format.logTime(current.target)))")
        current.alarmId = plan.alarmId
        current.target = target
        current.until = target.addingTimeInterval(AppleLearnRecord.afterTarget)
        ensureRecorder(until: current.until)
        save(current)
        return true
    }
}
