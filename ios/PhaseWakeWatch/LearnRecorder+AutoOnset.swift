import Foundation
import PhaseWakeCore

/// «Einschlafen automatisch erkennen» auf der Apple Watch (wie `AutoOnset` der Galaxy Watch; Standard ein, Schalter am
/// iPhone, freigegeben von r1AD 10.11.2026): Für jede Nacht eines gespeicherten Weckers startet die Uhr den
/// Bewegungs-Rekorder bis zum Beginn des Weckfensters (``AppleAutoOnset``), damit sie zu Beginn der Weck-Sitzung die
/// Einschlafzeit nachrechnen kann (`WatchOnset`) und die Untergrenze kurzer Nächte jede Nacht gilt. Unter 30 % Akku
/// startet sie für diese Nacht nicht (wie Android, auch nicht später). watchOS weckt die App am Abend nicht verlässlich;
/// darum startet der Rekorder schon beim ersten Durchgang ab 12 Std. vor dem Fensterbeginn (Neuplanen, App geöffnet,
/// Hintergrund-Aktualisierung).
extension LearnRecorder {
    private static let autoOnsetKey = "autoOnset"

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
        switch AppleAutoOnset.decide(now: Date(), target: plan.target, windowStart: plan.windowStart, battery: battery) {
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
