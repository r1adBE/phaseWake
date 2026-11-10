import Foundation

/// Frühere Zielzeit einer Nacht aus dem Einschlaf-Modus der Apple Watch («Gute Nacht»): Wecker `alarmId`, eingestellte
/// Zielzeit `original`, neue Zielzeit `target`, festgelegt um `at` (alles in ms). Wie `OnsetShift` (Kotlin): Das iPhone
/// merkt sie sich und stellt den Zweitwecker dieser Nacht auf die frühere Zielzeit (``applyTo(_:now:grace:)``); nach der
/// Nacht fällt sie weg (``expired(now:grace:)``). Eine neuere Meldung ohne frühere Zielzeit (``cancel(_:at:)``) hebt sie
/// auf. Geht als Befehl ``SyncKeys/onsetShift`` mit ``encode()`` als Inhalt.
public struct OnsetShift: Equatable, Sendable, Codable {
    /// Kennung des Weckers.
    public let alarmId: String
    /// Eingestellte Zielzeit der Nacht (ms seit 1970).
    public let original: Int64
    /// Neue Zielzeit (ms seit 1970); gleich `original`, wenn die Ausnahme aufgehoben ist.
    public let target: Int64
    /// Zeitpunkt der Meldung (ms seit 1970); eine neuere ersetzt eine ältere.
    public let at: Int64

    /// Meldung wie angegeben.
    public init(alarmId: String, original: Int64, target: Int64, at: Int64) {
        self.alarmId = alarmId
        self.original = original
        self.target = target
        self.at = at
    }

    /// Meldung ohne frühere Zielzeit: Die Uhr hat ihre Ausnahme für `plan` aufgehoben.
    public static func cancel(_ plan: WakePlan, at: Int64) -> OnsetShift {
        let ms = millis(plan.target)
        return OnsetShift(alarmId: plan.alarmId, original: ms, target: ms, at: at)
    }

    /// Ob die Meldung `plan` betrifft (derselbe Wecker, `plan` liegt auf der eingestellten Zielzeit).
    public func matches(_ plan: WakePlan) -> Bool {
        plan.alarmId == alarmId && Self.millis(plan.target) == original
    }

    /// Plan mit der früheren Zielzeit: nur für dieselbe Nacht desselben Weckers, nur früher und nur, solange die frühere
    /// Zielzeit samt Nachfrist `grace` noch nicht vorbei ist. Sonst `plan` unverändert.
    public func applyTo(_ plan: WakePlan, now: Date, grace: TimeInterval) -> WakePlan {
        guard matches(plan), target < original else { return plan }
        let earlier = Self.date(target)
        guard earlier.addingTimeInterval(grace) > now else { return plan }
        return WakePlan(alarmId: plan.alarmId, windowStart: min(plan.windowStart, earlier), target: earlier, exact: plan.exact)
    }

    /// Ob die Nacht vorbei ist (eingestellte Zielzeit plus `grace` erreicht).
    public func expired(now: Date, grace: TimeInterval) -> Bool {
        Self.date(original).addingTimeInterval(grace) <= now
    }

    /// «kennung|eingestellt|neu|zeitpunkt» (Kennung prozentkodiert).
    public func encode() -> String {
        let id = alarmId.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? alarmId
        return "\(id)|\(original)|\(target)|\(at)"
    }

    /// Liest ``encode()``; `nil`, wenn leer oder unlesbar.
    public static func decode(_ text: String?) -> OnsetShift? {
        guard let fields = text?.split(separator: "|", omittingEmptySubsequences: false), fields.count >= 4,
              let original = Int64(fields[1]), let target = Int64(fields[2]), let at = Int64(fields[3]) else { return nil }
        let id = String(fields[0]).removingPercentEncoding ?? String(fields[0])
        guard !id.isEmpty else { return nil }
        return OnsetShift(alarmId: id, original: original, target: target, at: at)
    }

    /// Millisekunden seit 1970, gerundet.
    public static func millis(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    private static func date(_ millis: Int64) -> Date { Date(timeIntervalSince1970: TimeInterval(millis) / 1000) }
}
