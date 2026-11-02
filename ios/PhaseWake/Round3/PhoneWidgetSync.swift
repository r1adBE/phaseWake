import Foundation
import PhaseWakeCore
import UIKit
import WidgetKit

/// Hält das iPhone-Widget aktuell: legt die nächsten Weckfenster, den Nacht-Check und die letzte Nacht in der
/// App-Gruppe ab.
@MainActor
enum PhoneWidgetSync {
    /// So viele kommende Fenster bekommt das Widget mit.
    private static let planCount = 4

    /// Schreibt den aktuellen Stand und lädt das Widget nur bei einer Änderung neu.
    static func publish(_ model: PhoneModel, failed: [PhoneCheck], now: Date = Date()) {
        var plans: [PhoneWidgetSnapshot.Plan] = []
        var from = now
        while plans.count < planCount, let plan = model.nextPlan(now: from) {
            let alarm = model.alarms.first { $0.id == plan.alarmId }
            plans.append(.init(windowStart: plan.windowStart, target: plan.target, label: alarm?.label ?? "",
                               exact: alarm?.isExact == true))
            from = plan.target.addingTimeInterval(1)
        }
        let snapshot = PhoneWidgetSnapshot(plans: plans, issue: failed.first { !$0.isAdvisory }?.title ?? "",
                                           lastNight: lastNight(model))
        if snapshot.save() { WidgetCenter.shared.reloadTimelines(ofKind: PhoneWidgetSnapshot.kind) }
    }

    /// Nur die letzte Nacht nachführen (Nacht gespeichert, Befinden gesetzt), auch wenn die App im Hintergrund
    /// läuft; Fenster und Nacht-Check bleiben, wie sie zuletzt abgelegt wurden.
    static func publishNight(_ model: PhoneModel) {
        var snapshot = PhoneWidgetSnapshot.load() ?? PhoneWidgetSnapshot(plans: [], issue: nil)
        snapshot.lastNight = lastNight(model)
        if snapshot.save() { WidgetCenter.shared.reloadTimelines(ofKind: PhoneWidgetSnapshot.kind) }
        Task { await refreshAsleep(model) }
    }

    /// Schlafdauer der jüngsten Nacht aus Apple Health nachtragen, solange das Widget sie zeigt (Weckmorgen bis
    /// 12 Uhr). Nur mit Freigabe und entsperrt (sonst liefert HealthKit nichts und die Dauer fiele weg).
    static func refreshAsleep(_ model: PhoneModel, now: Date = Date()) async {
        guard UserDefaults.standard.bool(forKey: "healthRequested"), UIApplication.shared.isProtectedDataAvailable,
              PhoneWidgetSnapshot.load()?.morningNight(at: now) != nil else { return }
        let health = await model.health.sessions(now: now)
        publishAsleep(model, sleeps: HealthHistory.summaries(health.sessions, clearedAt: model.historyClearedAt, calendar: .current))
    }

    /// Legt die Schlafdauer der jüngsten Nacht aus `sleeps` (schon geladene Nächte aus Apple Health) ab, gleiche
    /// Regel wie Verlauf und Detailseite: `NoteEffect.asleepByNight` zum Nachtdatum der eigenen Nacht.
    static func publishAsleep(_ model: PhoneModel, sleeps: [SleepSummary]) {
        guard var snapshot = PhoneWidgetSnapshot.load(), var stored = snapshot.lastNight,
              let night = latest(model), night.wokeAt == stored.wokeAt else { return }
        let calendar = Calendar.current
        stored.asleepMinutes = NoteEffect.asleepByNight(sleeps, calendar: calendar)[SleepNotes.nightDate(of: night, calendar: calendar)]
        snapshot.lastNight = stored
        if snapshot.save() { WidgetCenter.shared.reloadTimelines(ofKind: PhoneWidgetSnapshot.kind) }
    }

    private static func latest(_ model: PhoneModel) -> NightRecord? { model.nights.max { $0.wokeAt < $1.wokeAt } }

    /// Jüngste Nacht wie im Morgenbericht: Zeitpunkt und Grund, Befinden (falls angegeben); `nil` ohne Nacht. Die
    /// Schlafdauer bleibt vom letzten Stand derselben Nacht (``publishAsleep(_:sleeps:)`` trägt sie nach).
    private static func lastNight(_ model: PhoneModel) -> PhoneWidgetSnapshot.LastNight? {
        guard let night = latest(model) else { return nil }
        let previous = PhoneWidgetSnapshot.load()?.lastNight.flatMap { $0.wokeAt == night.wokeAt ? $0.asleepMinutes : nil }
        return .init(wokeAt: night.wokeAt, reason: wakeTiming(night),
                     feeling: night.feeling.map { L("feeling_value", feelingName($0)) }, asleepMinutes: previous)
    }
}
