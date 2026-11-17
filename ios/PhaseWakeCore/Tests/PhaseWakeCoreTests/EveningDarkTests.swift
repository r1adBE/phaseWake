import XCTest
import PhaseWakeCore

/// «Abends dunkel ab» (``EveningDark``); Gegenstück zu `EveningDarkTest` (Kotlin), gemeinsame Fälle in
/// `shared/testdata/evening-dark.vectors`.
final class EveningDarkTests: XCTestCase {
    private func calendar(_ zone: String) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: zone)!
        return calendar
    }

    private func utc(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }

    private func text(_ date: Date?) -> String {
        guard let date else { return "-" }
        return ISO8601DateFormatter().string(from: date)
    }

    func testSharedVectors() throws {
        let text = try String(contentsOf: NightFixtures.directory.appendingPathComponent("evening-dark.vectors"), encoding: .utf8)
        let lines = text.components(separatedBy: "\n").filter { !$0.isEmpty && !$0.hasPrefix("#") }
        for line in lines {
            let p = line.components(separatedBy: "\t")
            XCTAssertEqual(p.count, 7, line)
            guard p.count == 7 else { continue }
            let cal = calendar(p[0])
            let now = utc(p[1])
            let enabled = p[2] == "1"
            let hm = p[3].components(separatedBy: ":").compactMap { Int($0) }
            let from = hm[0] * 60 + hm[1]
            XCTAssertEqual(EveningDark.isDark(now: now, calendar: cal, enabled: enabled, fromMinutes: from), p[4] == "1", "dunkel: \(line)")
            XCTAssertEqual(self.text(EveningDark.nextChange(now: now, calendar: cal, enabled: enabled, fromMinutes: from)), p[5], "Wechsel: \(line)")
        }
        XCTAssertEqual(lines.count, 24)
    }

    func testCoversAcrossMidnight() {
        XCTAssertTrue(EveningDark.covers(18 * 60, from: 18 * 60))
        XCTAssertTrue(EveningDark.covers(0, from: 18 * 60))
        XCTAssertTrue(EveningDark.covers(359, from: 18 * 60))
        XCTAssertFalse(EveningDark.covers(360, from: 18 * 60))
        XCTAssertFalse(EveningDark.covers(17 * 60 + 59, from: 18 * 60))
        // Beginn nach Mitternacht: nur bis 06:00 desselben Morgens.
        XCTAssertTrue(EveningDark.covers(60, from: 30))
        XCTAssertFalse(EveningDark.covers(23 * 60, from: 30))
        XCTAssertFalse(EveningDark.covers(100, from: EveningDark.untilMinutes))
    }

    func testStoredStartIsChecked() {
        XCTAssertEqual(EveningDark.checkedFrom(nil), 18 * 60)
        XCTAssertEqual(EveningDark.checkedFrom(-1), 18 * 60)
        XCTAssertEqual(EveningDark.checkedFrom(24 * 60), 18 * 60)
        XCTAssertEqual(EveningDark.checkedFrom(0), 0)
        XCTAssertEqual(EveningDark.checkedFrom(23 * 60 + 59), 23 * 60 + 59)
        XCTAssertTrue(EveningDark.defaultEnabled)
    }

    func testNextChangeAlwaysFlips() {
        // Jede Viertelstunde einer Woche mit Sommerzeit-Ende: Der Wechsel liegt in der Zukunft und kippt genau dort.
        let cal = calendar("Europe/Zurich")
        let from = 19 * 60 + 45
        var now = utc("2026-10-22T00:07:00Z")
        for _ in 0..<(7 * 96) {
            let dark = EveningDark.isDark(now: now, calendar: cal, enabled: true, fromMinutes: from)
            guard let next = EveningDark.nextChange(now: now, calendar: cal, enabled: true, fromMinutes: from) else {
                XCTFail("\(now)"); return
            }
            XCTAssertGreaterThan(next, now)
            XCTAssertEqual(EveningDark.isDark(now: next, calendar: cal, enabled: true, fromMinutes: from), !dark, "\(now)")
            XCTAssertEqual(EveningDark.isDark(now: max(now, next.addingTimeInterval(-60)), calendar: cal, enabled: true, fromMinutes: from), dark, "\(now)")
            now = now.addingTimeInterval(15 * 60)
        }
        XCTAssertNil(EveningDark.nextChange(now: now, calendar: cal, enabled: false, fromMinutes: 18 * 60))
    }
}
