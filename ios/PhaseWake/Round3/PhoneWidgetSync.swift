import Foundation
import PhaseWakeCore
import WidgetKit

/// Hält das iPhone-Widget aktuell: legt die nächsten Weckfenster und den Nacht-Check in der App-Gruppe ab.
@MainActor
enum PhoneWidgetSync {
    /// So viele kommende Fenster bekommt das Widget mit.
    private static let planCount = 4

    /// Schreibt den aktuellen Stand und lädt das Widget nur bei einer Änderung neu.
    static func publish(_ model: PhoneModel, failed: [PhoneCheck], now: Date = Date()) {
        var plans: [PhoneWidgetSnapshot.Plan] = []
        var from = now
        while plans.count < planCount, let plan = model.nextPlan(now: from) {
            let label = model.alarms.first { $0.id == plan.alarmId }?.label ?? ""
            plans.append(.init(windowStart: plan.windowStart, target: plan.target, label: label))
            from = plan.target.addingTimeInterval(1)
        }
        let snapshot = PhoneWidgetSnapshot(plans: plans, issue: failed.first?.title ?? "")
        if snapshot.save() { WidgetCenter.shared.reloadTimelines(ofKind: PhoneWidgetSnapshot.kind) }
    }
}
