import XCTest
import PhaseWakeCore

/// Platz für die Mitteilungen des Zweitweckers (`NotificationBudget`): iOS behält 64 wartende je App, Reserve 4;
/// ganze Kette nur innerhalb von 48 h. Nur Apple.
final class NotificationBudgetTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    /// Je eine Sicherung pro Tag, die erste in `firstHours` Stunden.
    private func daily(_ count: Int, firstHours: Double = 10) -> [Date] {
        (0..<count).map { now.addingTimeInterval(firstHours * 3600 + Double($0) * 24 * 3600) }
    }

    func testFreeSpace() {
        XCTAssertEqual(NotificationBudget(otherPending: 0).left, 60)
        XCTAssertEqual(NotificationBudget(otherPending: 10).left, 50)
        XCTAssertEqual(NotificationBudget(otherPending: 70).left, 0, "nie negativ")
    }

    func testChainOnlyWithin48Hours() {
        XCTAssertEqual(NotificationBudget.wanted(now.addingTimeInterval(3600), now: now), NotificationChain.count)
        XCTAssertEqual(NotificationBudget.wanted(now.addingTimeInterval(48 * 3600), now: now), NotificationChain.count)
        XCTAssertEqual(NotificationBudget.wanted(now.addingTimeInterval(48 * 3600 + 1), now: now), 1)
    }

    func testFourteenNightsFitWithFewOthers() {
        // 10 h, 34 h: Kette; die übrigen 12 Nächte je eine Mitteilung: 8 + 12 = 20 von 50.
        let counts = NotificationBudget.counts(daily(14), now: now, otherPending: 10)
        XCTAssertEqual(counts, [4, 4] + Array(repeating: 1, count: 12))
    }

    func testFortySlotsWhenThereIsRoom() {
        // Zwei Wecker täglich über 14 Tage wären 28; 40 Sicherungen im Abstand von 8 h: 6 in 48 h mit Kette.
        let fireAts = (0..<40).map { now.addingTimeInterval(3600 + Double($0) * 8 * 3600) }
        let counts = NotificationBudget.counts(fireAts, now: now, otherPending: 0)
        XCTAssertEqual(counts.count, 40)
        XCTAssertEqual(counts.filter { $0 == NotificationChain.count }.count, 6)
        XCTAssertEqual(counts.reduce(0, +), 6 * 4 + 34)
    }

    func testStopsWhenFull() {
        // 54 andere: 6 frei. Erste Kette 4, zweite nur noch 2, danach nichts mehr.
        let counts = NotificationBudget.counts(daily(14), now: now, otherPending: 54)
        XCTAssertEqual(counts, [4, 2])
    }

    func testFirstBackupAlwaysGetsItsChain() {
        // Kein Platz: Die erste bekommt ihre Kette trotzdem (iOS behält die frühesten), die übrigen keine.
        XCTAssertEqual(NotificationBudget.counts(daily(3), now: now, otherPending: 64), [4])
        XCTAssertEqual(NotificationBudget.counts(daily(3, firstHours: 72), now: now, otherPending: 64), [1])
    }

    func testCompletesChainWhenCloser() {
        // Gestellt mit einer Mitteilung, jetzt innerhalb von 48 h: Kette ergänzen.
        var budget = NotificationBudget(otherPending: 10)
        let fireAt = now.addingTimeInterval(20 * 3600)
        XCTAssertEqual(budget.grant(fireAt, now: now, have: 1), 4)
        budget.use(4)
        XCTAssertEqual(budget.left, 46)
    }

    func testKeepsWhatIsAlreadyPending() {
        // Eine schon gestellte Kette bleibt, auch wenn kein Platz mehr ist.
        var budget = NotificationBudget(otherPending: 0)
        budget.use(60)
        XCTAssertEqual(budget.grant(now.addingTimeInterval(3600), now: now, have: 4), 4)
        XCTAssertEqual(budget.grant(now.addingTimeInterval(72 * 3600), now: now, have: 1), 1)
        XCTAssertEqual(budget.grant(now.addingTimeInterval(72 * 3600), now: now), 0)
    }
}
