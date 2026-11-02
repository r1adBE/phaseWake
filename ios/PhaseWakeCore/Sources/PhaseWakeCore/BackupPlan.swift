import Foundation

/// Welche Nächte der Zweitwecker auf dem iPhone abdeckt, ohne iOS-Abhängigkeit (testbar). Nur Apple: Android stellt
/// immer nur den nächsten Zweitwecker (`RingRules.backupPlan`), das iPhone dagegen bis zu zwei Wochen im Voraus, weil
/// iOS die App nicht zuverlässig weckt.
public enum BackupPlan {
    /// Die Nächte mit Zweitwecker in (`now`, `until`], höchstens `maxNights`, nach Zielzeit sortiert (bei Gleichstand
    /// die kleinere Kennung). Je Wecker aus `specs` (schon auf «Zweitwecker ein» gefiltert) gesucht, nicht über alle
    /// zusammen: So bekommen zwei Wecker mit derselben Zielzeit beide eine Sicherung.
    /// - `suppressed`: je Wecker ausgesetzt bis (die Uhr hat die Nacht übernommen); gesucht wird erst danach.
    /// - `firstTargets`: einmalige Wecker mit der Zielzeit ihres ersten Vorkommens. Sie bekommen nur dieses; ist es
    ///   ausgesetzt oder schon vorbei, keine.
    /// - `skip`: Wecker ohne Sicherung (z. B. gerade ausgeschaltete einmalige).
    public static func nights(_ specs: [AlarmSpec], now: Date, until: Date, maxNights: Int, suppressed: [String: Date],
                              firstTargets: [String: Date], skip: Set<String> = [], calendar: Calendar,
                              holidays: Set<String> = []) -> [WakePlan] {
        guard maxNights > 0 else { return [] }
        var plans: [WakePlan] = []
        for spec in specs where !skip.contains(spec.id) {
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
