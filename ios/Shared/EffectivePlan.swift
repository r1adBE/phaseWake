import Foundation
import PhaseWakeCore

/// Plan, nach dem die Uhr wirklich weckt (`WatchModel.plan`: nächster Wecker, «Gute Nacht», Probewecker, Powernap), für
/// die Komplikation (Audit AP-9). Die Uhr-App legt ihn bei jedem Neuplanen in ihre Einstellungsdatei in der App-Gruppe
/// (`key`); die Komplikation zeigt ihn, solange seine Zielzeit nicht vorbei ist, sonst rechnet sie selbst aus den
/// Weckern (wie bisher). Mit dem Versatz zu UTC, den die Zielzeit beim Speichern hatte (`offset`, Vollaudit AP2-7): Gilt
/// jetzt ein anderer (Reise), plant die Uhr ohnehin neu, und bis dahin zeigt die Komplikation den gerechneten Plan.
/// Rein bis auf das Lesen und Schreiben (testbar).
enum EffectivePlan {
    /// Schlüssel in der Einstellungsdatei der Uhr (`PhaseWakeStorage.watchSettingsName`).
    static let key = "effectivePlan"

    /// Wert zum Speichern, mit dem Versatz der Zone `zone` zur Zielzeit; `nil` ohne Plan.
    static func encode(_ plan: WakePlan?, zone: TimeZone = .current) -> [String: Any]? {
        guard let plan else { return nil }
        return ["alarm": plan.alarmId, "window": plan.windowStart.timeIntervalSince1970, "target": plan.target.timeIntervalSince1970,
                "exact": plan.exact, "offset": zone.secondsFromGMT(for: plan.target)]
    }

    /// Gespeicherter Plan, wenn er zur Zone `zone` passt: gleicher Versatz zur Zielzeit wie beim Speichern. Ohne
    /// gespeicherten Versatz (ältere Version) gilt er; sonst wie ``decode(_:)``.
    static func decode(_ value: Any?, zone: TimeZone) -> WakePlan? {
        guard let plan = decode(value) else { return nil }
        guard let offset = ((value as? [String: Any])?["offset"] as? NSNumber)?.intValue else { return plan }
        return offset == zone.secondsFromGMT(for: plan.target) ? plan : nil
    }

    /// Gespeicherter Plan; `nil`, wenn keiner oder unlesbar (auch unplausible Zeiten: Fensterbeginn nach der Zielzeit,
    /// ausserhalb 2000–2100).
    static func decode(_ value: Any?) -> WakePlan? {
        guard let dict = value as? [String: Any], let alarm = dict["alarm"] as? String,
              let window = (dict["window"] as? NSNumber)?.doubleValue, let target = (dict["target"] as? NSNumber)?.doubleValue,
              window.isFinite, target.isFinite, window <= target, target > 946_684_800, target < 4_102_444_800 else { return nil }
        return WakePlan(alarmId: alarm, windowStart: Date(timeIntervalSince1970: window), target: Date(timeIntervalSince1970: target),
                        exact: (dict["exact"] as? NSNumber)?.boolValue ?? false)
    }

    /// Was die Komplikation um `now` zeigt: den gespeicherten Plan der Uhr, solange seine Zielzeit (plus eine Minute
    /// zum Klingeln) nicht vorbei ist; sonst den aus den Weckern gerechneten (`computed`).
    static func choose(stored: WakePlan?, computed: WakePlan?, now: Date) -> WakePlan? {
        guard let stored, now < stored.target.addingTimeInterval(60) else { return computed }
        return stored
    }
}
