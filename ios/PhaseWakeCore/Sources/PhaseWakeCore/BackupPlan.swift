import Foundation

/// Welche Nächte der Zweitwecker auf dem iPhone abdeckt, ohne iOS-Abhängigkeit (testbar). Nur Apple: Android stellt
/// immer nur den nächsten Zweitwecker (`RingRules.backupPlan`), das iPhone dagegen bis zu zwei Wochen im Voraus, weil
/// iOS die App nicht zuverlässig weckt.
public enum BackupPlan {
    /// Wecker und Regeln für ``nights(_:now:until:maxNights:calendar:)``.
    public struct Input: Equatable, Sendable {
        /// Wecker, schon auf «Zweitwecker ein» gefiltert.
        public var specs: [AlarmSpec]
        /// Je Wecker ausgesetzt bis (die Uhr hat die Nacht übernommen); gesucht wird erst danach.
        public var suppressed: [String: Date]
        /// Einmalige Wecker mit der Zielzeit ihres ersten Vorkommens. Sie bekommen nur dieses; ist es ausgesetzt oder
        /// schon vorbei, keine.
        public var firstTargets: [String: Date]
        /// Wecker ohne Sicherung (z. B. gerade ausgeschaltete einmalige).
        public var skip: Set<String>
        /// Feiertage («yyyy-MM-dd») für Wecker mit «An Feiertagen nicht wecken».
        public var holidays: Set<String>

        /// Eingaben mit allen Feldern; ohne `skip` und `holidays` leer.
        public init(_ specs: [AlarmSpec], suppressed: [String: Date], firstTargets: [String: Date],
                    skip: Set<String> = [], holidays: Set<String> = []) {
            self.specs = specs
            self.suppressed = suppressed
            self.firstTargets = firstTargets
            self.skip = skip
            self.holidays = holidays
        }
    }

    /// Die Nächte mit Zweitwecker in (`now`, `until`], höchstens `maxNights`, nach Zielzeit sortiert (bei Gleichstand
    /// die kleinere Kennung). Je Wecker aus `input.specs` gesucht, nicht über alle zusammen: So bekommen zwei Wecker
    /// mit derselben Zielzeit beide eine Sicherung. Aussetzen, einmalige Wecker und Ausnahmen: ``Input``.
    public static func nights(_ input: Input, now: Date, until: Date, maxNights: Int,
                              calendar: Calendar) -> [WakePlan] {
        guard maxNights > 0 else { return [] }
        let (suppressed, firstTargets, holidays) = (input.suppressed, input.firstTargets, input.holidays)
        var plans: [WakePlan] = []
        for spec in input.specs where !input.skip.contains(spec.id) {
            var from = max(now, suppressed[spec.id] ?? .distantPast)
            var count = 0
            while count < maxNights,
                  let plan = AlarmSchedule.nextPlan(spec, now: from, calendar: calendar, holidays: holidays),
                  plan.target <= until {
                if let first = firstTargets[spec.id] {
                    // Einmalig: nur das erste Vorkommen, danach keine weitere Nacht.
                    if plan.target == first { plans.append(plan) }
                    break
                }
                plans.append(plan)
                count += 1
                from = plan.target
            }
        }
        let sorted = plans.sorted { ($0.target, $0.alarmId) < ($1.target, $1.alarmId) }
        return Array(sorted.prefix(maxNights))
    }
}
