import Foundation

/// Platz für die Mitteilungen des Zweitweckers am iPhone (vor iOS 26 und wenn AlarmKit scheitert), ohne
/// iOS-Abhängigkeit (testbar). iOS behält höchstens `limit` wartende Mitteilungen je App; die übrigen der App
/// (Bettzeit-Erinnerungen, Wochenrückblick, Mitklingeln, Schlummern, Probewecker …) gehen vor, dazu eine Reserve
/// (`margin`). Eine Sicherung, die innerhalb von `chainWindow` klingelt, bekommt die ganze Kette
/// (`NotificationChain.count`), eine spätere nur die erste Mitteilung; rückt sie bei einem späteren Neuplanen näher,
/// wird ihre Kette ergänzt. Die erste Sicherung bekommt ihre Mitteilungen immer, auch ohne freien Platz: iOS behält
/// die frühesten. Nur Apple.
public struct NotificationBudget: Sendable {
    /// Wartende Mitteilungen je App, die iOS behält.
    public static let limit = 64
    /// Reserve für Mitteilungen, die nach dem Planen dazukommen.
    public static let margin = 4
    /// Innerhalb dieser Zeit bis zum Klingeln bekommt eine Sicherung die ganze Kette.
    public static let chainWindow: TimeInterval = 48 * 3600

    /// Noch freie Mitteilungen.
    public private(set) var left: Int
    /// Ob schon eine Sicherung Mitteilungen bekommen hat.
    private var started = false

    /// `otherPending` = wartende Mitteilungen der App ohne die des Zweitweckers.
    public init(otherPending: Int) {
        left = max(0, Self.limit - Self.margin - otherPending)
    }

    /// Gewünschte Mitteilungen einer Sicherung, die zu `fireAt` klingelt: die ganze Kette innerhalb von
    /// `chainWindow`, sonst nur die erste.
    public static func wanted(_ fireAt: Date, now: Date) -> Int {
        fireAt.timeIntervalSince(now) <= chainWindow ? NotificationChain.count : 1
    }

    /// So viele Mitteilungen soll die Sicherung zu `fireAt` haben; `have` = schon wartende davon (sie bleiben). Ohne
    /// genug Platz weniger als gewünscht, bis 0 (dann keine Sicherung). Ändert nichts; verbraucht wird mit `use`.
    public func grant(_ fireAt: Date, now: Date, have: Int = 0) -> Int {
        let wanted = Self.wanted(fireAt, now: now)
        guard started else { return max(have, wanted) }
        return max(have, min(wanted, left))
    }

    /// Verbraucht `count` Mitteilungen.
    public mutating func use(_ count: Int) {
        guard count > 0 else { return }
        started = true
        left = max(0, left - count)
    }

    /// Mitteilungen je Sicherung für `fireAts` (nach Zeit sortiert), solange Platz ist; die Liste endet bei der ersten
    /// Sicherung ohne Platz. Für Prüfungen und die Planung in einem Zug.
    public static func counts(_ fireAts: [Date], now: Date, otherPending: Int) -> [Int] {
        var budget = NotificationBudget(otherPending: otherPending)
        var result: [Int] = []
        for fireAt in fireAts {
            let count = budget.grant(fireAt, now: now)
            guard count > 0 else { break }
            budget.use(count)
            result.append(count)
        }
        return result
    }
}
