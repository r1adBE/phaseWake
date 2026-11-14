import Foundation
import PhaseWakeCore

/// Entscheide der Uhr rund um eine Nacht ohne Sitzung und Sensoren (testbar): gemerkte geplante Sitzung nach einem
/// Neustart der App (Vollaudit AP2-1), «stop» vom iPhone, während die Uhr nicht klingelt (AP2-5), und ein alter
/// Klingel-Zustand beim Start (AP2-9).
enum NightRules {
    // MARK: Gemerkte Sitzung (AP2-1)

    /// Mit `start(at:)` geplante Smart-Alarm-Sitzung: Wecker, Zielzeit und Start. Gemerkt in `SettingsFile.watchSession`
    /// (``SmartAlarm/scheduledKey``), weil das System die Sitzung über einen Neustart der App hinweg behält, die App
    /// ihren Stand aber nicht: So bleibt die Uhr nach dem Neustart scharf und plant keine zweite Sitzung.
    struct ScheduledSession: Equatable {
        /// Kennung des Weckers.
        let alarmId: String
        /// Zielzeit der Nacht.
        let target: Date
        /// Geplanter Start der Sitzung.
        let start: Date

        /// Wert zum Speichern.
        var encoded: [String: Any] {
            ["alarm": alarmId, "target": target.timeIntervalSince1970, "start": start.timeIntervalSince1970]
        }

        /// Gespeicherte Sitzung; `nil`, wenn keine oder unlesbar (auch unplausible Zeiten, ``TimeBounds``).
        static func decode(_ value: Any?) -> ScheduledSession? {
            guard let dict = value as? [String: Any], let alarmId = dict["alarm"] as? String,
                  let target = (dict["target"] as? NSNumber)?.doubleValue, let start = (dict["start"] as? NSNumber)?.doubleValue,
                  plausible(target), plausible(start) else { return nil }
            return ScheduledSession(alarmId: alarmId, target: Date(timeIntervalSince1970: target), start: Date(timeIntervalSince1970: start))
        }

        /// Ob `seconds` (seit 1970) im Bereich von ``TimeBounds`` liegt.
        private static func plausible(_ seconds: Double) -> Bool {
            seconds.isFinite && seconds * 1000 >= Double(TimeBounds.minMs) && seconds * 1000 <= Double(TimeBounds.maxMs)
        }
    }

    /// Ob die gemerkte Sitzung `saved` noch für `plan` (Sitzungsstart `start`) steht: gleicher Wecker, gleiche Zielzeit
    /// und gleicher Start (auf die Millisekunde), und sie hat noch nicht begonnen (Start plus `tolerance` liegt vorn).
    /// Dann gilt die Uhr als scharf, ohne eine neue Sitzung zu planen.
    static func keepsScheduled(_ saved: ScheduledSession?, plan: WakePlan, start: Date, now: Date, tolerance: TimeInterval) -> Bool {
        guard let saved, saved.alarmId == plan.alarmId, TimeBounds.millis(saved.target) == TimeBounds.millis(plan.target),
              TimeBounds.millis(saved.start) == TimeBounds.millis(start) else { return false }
        return now < saved.start.addingTimeInterval(tolerance)
    }

    // MARK: Wecker geändert, Uhr-App nicht offen (Tiefenaudit AP3-3)

    /// Ob eine schon geplante Sitzung mit Start `start` die Nacht `plan` übernehmen kann: Sie beginnt frühestens
    /// `tolerance` vor dem Sitzungsstart von `plan` (`sessionStart`) und vor der Zielzeit plus `delay` – dieselbe
    /// Prüfung wie beim Start der Sitzung (`SmartAlarm.startNight`). Dann misst sie `plan` ab ihrem Start (die Minuten
    /// davor bleiben ungemessen), statt verworfen zu werden. Eine spätere Zielzeit deckt sie nicht (Sitzung höchstens
    /// 30 Min).
    static func sessionServes(start: Date, plan: WakePlan, sessionStart: Date, tolerance: TimeInterval, delay: TimeInterval) -> Bool {
        start >= sessionStart.addingTimeInterval(-tolerance) && start < plan.target.addingTimeInterval(delay)
    }

    // MARK: «stop» vom iPhone (AP2-5)

    /// Die Nacht, die ein «stop» vom iPhone (Wecker `alarmId`, Zielzeit `target`) beendet, obwohl die Uhr nicht klingelt:
    /// die erste aus `candidates` (geplante, wartende, messende oder ausstehende Nacht) mit gleichem Wecker und gleicher
    /// Zielzeit (auf 1 s), deren Weckfenster um `now` begonnen hat (bis `tolerance` früher). Ohne Kennung oder Zielzeit
    /// (ältere iPhone-App) keine: Dann bleibt es beim Beenden der Kette nach einer abgebrochenen Sitzung.
    static func phoneStoppedNight(_ candidates: [WakePlan?], alarmId: String?, target: Date?, now: Date,
                                  tolerance: TimeInterval) -> WakePlan? {
        guard let alarmId, let target else { return nil }
        return candidates.compactMap { $0 }.first { night in
            night.alarmId == alarmId && abs(night.target.timeIntervalSince(target)) < 1
                && now >= night.windowStart.addingTimeInterval(-tolerance)
        }
    }

    // MARK: Klingel-Zustand beim Start (AP2-9)

    /// Was mit einem gesicherten Klingel- oder Schlummer-Zustand beim Start der App geschieht.
    enum Restored: Equatable {
        /// Schlummern läuft noch (die Schlummer-Sitzung startet noch): weiter wie gehabt.
        case resume
        /// Sitzung weg: per Mitteilung weiterwecken (bis `SmartAlarm.ringLimit`) und die Nacht abschliessen.
        case finishWaking
        /// Zu alt: die Nacht ohne Wecken abschliessen (Epochen speichern, erledigt, Einmal-Wecker aus), ohne Nachholen.
        case finishSilently
    }

    /// Entscheid für einen Zustand der Nacht mit Zielzeit `target` (geschlummert bis `snoozedUntil`) um `now`: älter als
    /// `staleAfter` nach der Zielzeit → ohne Wecken abschliessen (früher verworfen, dabei gingen Nacht und Epochen
    /// verloren); Schlummer-Ende plus `tolerance` noch nicht vorbei → weiter; sonst weiterwecken und abschliessen.
    static func restored(target: Date, snoozedUntil: Date?, now: Date, staleAfter: TimeInterval, tolerance: TimeInterval) -> Restored {
        if now.timeIntervalSince(target) > staleAfter { return .finishSilently }
        if let snoozedUntil, snoozedUntil.addingTimeInterval(tolerance) >= now { return .resume }
        return .finishWaking
    }
}
