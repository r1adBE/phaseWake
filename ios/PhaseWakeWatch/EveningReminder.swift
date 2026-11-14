import Foundation
import PhaseWakeCore
import UserNotifications

/// Erinnerung am Abend auf der Apple Watch (Entscheid r1AD 10.11.2026): watchOS plant die Smart-Alarm-Sitzung nur bei
/// offener App, und den Bewegungs-Rekorder startet die Uhr frühestens 60 Min vor der Bettzeit (Bettzeit = Zielzeit minus
/// Schlafziel bzw. Schlafdauer, ``ShortNight/autoOnsetStart(target:sleepGoalMinutes:sleepDurationMinutes:)``, wie die
/// Einschlaf-Messung der Galaxy Watch). 60 Min vor der einen Bettzeit (mit gelernter Einschlafdauer, Audit O-03) bittet
/// eine lokale Mitteilung, die App zu öffnen. Wer sie
/// antippt (oder die App sonst öffnet), stellt den Wecker scharf (`SmartAlarm.schedule` bei offener App) und startet den
/// Rekorder ab dann (`LearnRecorder`). Ohne Öffnen und ohne Hintergrund-Aktualisierung zeichnet die Uhr diese Nacht
/// nicht auf; die Einschlafzeit bleibt unbekannt, und ohne geplante Sitzung weckt nur die Mitteilung zur Zielzeit
/// (Nacht-Check rot).
///
/// Gestellt bei jedem Neuplanen (`WatchModel.reschedule`), nur für einen echten Wecker und nur, wenn es etwas zu tun gibt:
/// Sitzung noch nicht geplant, oder Rekorder nötig (Einschlaf-Messung, Lernphase). Nach «Gute Nacht» und ab dem
/// Zeitpunkt selbst nicht mehr (die App läuft dann ja).
@MainActor
enum EveningReminder {
    static let id = "phasewake.evening"

    /// Zeitpunkt der Erinnerung für den nächsten echten Wecker (``reminderAt(target:sleepMinutes:latencyMinutes:)``);
    /// `nil` ohne Wecker.
    static var time: Date? {
        let model = WatchModel.shared
        guard let plan = model.realPlan, Transient.kindOf(plan.alarmId) == nil,
              let spec = model.alarms.first(where: { $0.id == plan.alarmId }) else { return nil }
        return reminderAt(target: plan.target, sleepMinutes: spec.sleepDurationMinutes ?? model.sleepGoal,
                          latencyMinutes: LearnRecorder.shared.snapshot.latencyMinutes)
    }

    /// 60 Min vor der einen Bettzeit (`Bedtime.bedtime`: Zielzeit − Schlafdauer bzw. Schlafziel − gelernte
    /// Einschlafdauer; Audit O-03, Entscheid r1AD 14.11.2026). Ohne gelernte Einschlafdauer wie bisher der Beginn der
    /// Einschlaf-Messung (``ShortNight/autoOnsetStart(target:sleepGoalMinutes:sleepDurationMinutes:)``); mit ihr um sie
    /// früher – der Rekorder startet trotzdem erst ab jenem Beginn (unverändert).
    nonisolated static func reminderAt(target: Date, sleepMinutes: Int, latencyMinutes: Int?) -> Date {
        Bedtime.bedtime(nextAlarmTarget: target, goalMinutes: sleepMinutes, latencyMinutes: latencyMinutes)
            .addingTimeInterval(-TimeInterval(ShortNight.autoLeadMinutes * 60))
    }

    /// Erinnerung für die kommende Nacht stellen oder entfernen.
    static func update(model: WatchModel) {
        let center = UNUserNotificationCenter.current()
        let now = Date()
        guard let plan = model.realPlan, let at = time, at > now, !model.goodNightActive else {
            center.removePendingNotificationRequests(withIdentifiers: [id])
            return
        }
        var armed = false
        if case .scheduled(let current) = SmartAlarm.shared.phase, current == plan { armed = true }
        let recorder = LearnRecorder.shared.autoOnset || LearnRecorder.shared.snapshot.active
        guard needed(at: at, now: now, goodNight: false, armed: armed, recorder: recorder) else {
            center.removePendingNotificationRequests(withIdentifiers: [id])
            return
        }
        let content = UNMutableNotificationContent()
        content.title = L("watch_evening_reminder")
        content.sound = .default
        // Gleiche Kennung: ersetzt die Erinnerung einer früheren Planung.
        let request = UNNotificationRequest(identifier: id, content: content, trigger: LocalTrigger.at(at, now: now))
        center.add(request) { error in
            guard let error else { return }
            DiagStore.shared.log(.error, "evening reminder not set \((error as NSError).code)")
        }
    }

    /// Ob die Erinnerung um `at` (für einen echten Wecker) zum Zeitpunkt `now` gestellt wird: noch nicht vorbei, nicht nach
    /// «Gute Nacht» (`goodNight`), und es gibt etwas zu tun: Sitzung noch nicht geplant (`armed`) oder Rekorder nötig
    /// (`recorder`: Einschlaf-Messung oder Lernphase). Ohne Zeitpunkt (kein Wecker) nie.
    static func needed(at: Date?, now: Date, goodNight: Bool, armed: Bool, recorder: Bool) -> Bool {
        guard let at, at > now, !goodNight else { return false }
        return !armed || recorder
    }

    /// Ob die Erinnerungszeit der kommenden Nacht erreicht ist (Startseite: dann deutlicher Hinweis, wenn nicht scharf).
    static func due(now: Date = Date()) -> Bool {
        guard let at = time else { return false }
        return now >= at
    }
}
