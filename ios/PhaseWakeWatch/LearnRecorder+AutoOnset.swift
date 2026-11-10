import Foundation
import PhaseWakeCore

/// «Einschlafen automatisch erkennen» auf der Apple Watch (wie `AutoOnset` der Galaxy Watch; Standard ein, Schalter am
/// iPhone, freigegeben von r1AD 10.11.2026): Für jede Nacht eines gespeicherten Weckers startet die Uhr den
/// Bewegungs-Rekorder bis zum Beginn des Weckfensters (``AppleAutoOnset``), damit sie zu Beginn der Weck-Sitzung die
/// Einschlafzeit nachrechnen kann (`WatchOnset`) und die Untergrenze kurzer Nächte gilt. Unter 30 % Akku startet sie
/// für diese Nacht nicht (wie Android, auch nicht später). Gestartet wird frühestens 60 Min vor der Bettzeit (wie
/// Android, Entscheid r1AD 10.11.2026) beim ersten Durchgang ab dann (App geöffnet, etwa über die Erinnerung am Abend,
/// oder Hintergrund-Aktualisierung). Läuft die App bis zum Fensterbeginn nicht, zeichnet die Uhr diese Nacht nicht auf;
/// die Einschlafzeit bleibt dann unbekannt.
extension LearnRecorder {
    private static let autoOnsetKey = "autoOnset"

    /// Beginn der kommenden Nacht für den Rekorder: 60 Min vor der Bettzeit des nächsten echten Weckers
    /// (``ShortNight/autoOnsetStart(target:sleepGoalMinutes:sleepDurationMinutes:)``); `nil` ohne Wecker.
    static func nightStart() -> Date? {
        let model = WatchModel.shared
        guard let plan = model.realPlan, Transient.kindOf(plan.alarmId) == nil,
              let spec = model.alarms.first(where: { $0.id == plan.alarmId }) else { return nil }
        return ShortNight.autoOnsetStart(target: plan.target, sleepGoalMinutes: model.sleepGoal, sleepDurationMinutes: spec.sleepDurationMinutes)
    }

    /// Schalter vom iPhone; ohne Stand ein (wie Android).
    var autoOnset: Bool { SettingsFile.watchSession.object(forKey: Self.autoOnsetKey) as? Bool ?? true }

    /// Schalter vom iPhone übernehmen (Anwendungskontext) und neu planen.
    func applyAutoOnset(_ on: Bool) {
        guard on != autoOnset else { return }
        SettingsFile.watchSession.set(on, forKey: Self.autoOnsetKey)
        DiagStore.shared.log(.sync, "auto onset \(on ? "on" : "off")")
        plan()
    }

    /// Startet den Rekorder für die nächste Nacht, falls fällig; sonst merkt es den frühesten Start
    /// (`autoNextTry`, Hintergrund-Aktualisierung). Diagnose einmal je Nacht.
    func startAutoOnsetIfDue() {
        autoNextTry = nil
        guard autoOnset else { return }
        let model = WatchModel.shared
        // Im Hintergrund kann `now` des Modells alt sein: frisch lesen.
        model.refresh()
        guard let plan = model.realPlan, Transient.kindOf(plan.alarmId) == nil,
              model.alarms.contains(where: { $0.id == plan.alarmId }) else { return }
        if autoDecided != plan.target {
            autoDecided = nil
            autoSkipped = false
        }
        // Für diese Nacht schon ausgelassen (Akku): wie Android nicht später doch noch.
        if autoSkipped { return }
        let battery = Self.battery()
        let spec = model.alarms.first { $0.id == plan.alarmId }
        let onsetStart = ShortNight.autoOnsetStart(target: plan.target, sleepGoalMinutes: model.sleepGoal,
                                                   sleepDurationMinutes: spec?.sleepDurationMinutes)
        switch AppleAutoOnset.decide(now: Date(), target: plan.target, windowStart: plan.windowStart, onsetStart: onsetStart, battery: battery) {
        case .wait(let until):
            autoNextTry = until
        case .skip(let reason):
            // «window begun»: Die Nacht läuft schon – nichts zu melden.
            guard autoDecided == nil, reason != "window begun" else { return }
            autoDecided = plan.target
            autoSkipped = true
            DiagStore.shared.log(reason.hasPrefix("battery") ? .battery : .measureStart, "auto onset not started: \(reason)")
        case .start(let until):
            guard Self.recorderAllowed else {
                if autoDecided == nil { DiagStore.shared.log(.measureStart, "auto onset not started: motion not allowed") }
                autoDecided = plan.target
                return
            }
            let running = recorderSpan.map { $0.until >= until && $0.until > Date() } ?? false
            ensureRecorder(until: until)
            guard autoDecided == nil else { return }
            autoDecided = plan.target
            DiagStore.shared.log(.measureStart, "auto onset recorder bat=\(battery.map { "\($0)" } ?? "-") until \(Format.time(until))\(running ? " (running)" : "")")
        }
    }
}
