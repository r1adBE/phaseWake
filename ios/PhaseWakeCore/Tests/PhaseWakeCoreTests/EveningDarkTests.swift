import XCTest
import PhaseWakeCore

/// «Abends dunkel» (``EveningDark``); Gegenstück zu `EveningDarkTest` (Kotlin), gemeinsame Fälle in
/// `shared/testdata/evening-dark.vectors`.
final class EveningDarkTests: XCTestCase {
    private let six = EveningDark.defaultUntilMinutes

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
        let text = try String(contentsOf: NightFixtures.directory.appendingPathComponent("evening-dark.vectors"),
                              encoding: .utf8)
        let lines = text.components(separatedBy: "\n").filter { !$0.isEmpty && !$0.hasPrefix("#") }
        for line in lines {
            let p = line.components(separatedBy: "\t")
            XCTAssertEqual(p.count, 8, line)
            guard p.count == 8 else { continue }
            let cal = calendar(p[0])
            let now = utc(p[1])
            let enabled = p[2] == "1"
            let from = minutes(p[3])
            let until = minutes(p[4])
            XCTAssertEqual(EveningDark.isDark(now: now, calendar: cal, enabled: enabled, fromMinutes: from,
                                              untilMinutes: until),
                           p[5] == "1", "dunkel: \(line)")
            XCTAssertEqual(self.text(EveningDark.nextChange(now: now, calendar: cal, enabled: enabled,
                                                            fromMinutes: from, untilMinutes: until)),
                           p[6], "Wechsel: \(line)")
        }
        XCTAssertEqual(lines.count, 41)
    }

    func testCoversAcrossMidnight() {
        XCTAssertTrue(EveningDark.covers(18 * 60, from: 18 * 60, until: six))
        XCTAssertTrue(EveningDark.covers(0, from: 18 * 60, until: six))
        XCTAssertTrue(EveningDark.covers(359, from: 18 * 60, until: six))
        XCTAssertFalse(EveningDark.covers(360, from: 18 * 60, until: six))
        XCTAssertFalse(EveningDark.covers(17 * 60 + 59, from: 18 * 60, until: six))
        // Beginn nach Mitternacht: nur bis 06:00 desselben Morgens.
        XCTAssertTrue(EveningDark.covers(60, from: 30, until: six))
        XCTAssertFalse(EveningDark.covers(23 * 60, from: 30, until: six))
        XCTAssertFalse(EveningDark.covers(100, from: six, until: six))
    }

    func testCoversWithinOneDay() {
        // 13:00–15:00: nur am Nachmittag, nicht über Mitternacht.
        XCTAssertFalse(EveningDark.covers(12 * 60 + 59, from: 13 * 60, until: 15 * 60))
        XCTAssertTrue(EveningDark.covers(13 * 60, from: 13 * 60, until: 15 * 60))
        XCTAssertTrue(EveningDark.covers(14 * 60 + 59, from: 13 * 60, until: 15 * 60))
        XCTAssertFalse(EveningDark.covers(15 * 60, from: 13 * 60, until: 15 * 60))
        XCTAssertFalse(EveningDark.covers(0, from: 13 * 60, until: 15 * 60))
        // Gleiche Zeiten: nie, zu jeder Minute.
        XCTAssertFalse((0..<(24 * 60)).contains { EveningDark.covers($0, from: 22 * 60, until: 22 * 60) })
    }

    func testStoredStartIsChecked() {
        XCTAssertEqual(EveningDark.defaultFromMinutes, 20 * 60)
        XCTAssertEqual(EveningDark.checkedFrom(nil), 20 * 60)
        XCTAssertEqual(EveningDark.checkedFrom(-1), 20 * 60)
        XCTAssertEqual(EveningDark.checkedFrom(24 * 60), 20 * 60)
        // Ein gespeicherter Beginn (z. B. der frühere Standard 18:00) bleibt.
        XCTAssertEqual(EveningDark.checkedFrom(18 * 60), 18 * 60)
        XCTAssertEqual(EveningDark.checkedFrom(0), 0)
        XCTAssertEqual(EveningDark.checkedFrom(23 * 60 + 59), 23 * 60 + 59)
        XCTAssertTrue(EveningDark.defaultEnabled)
    }

    func testStoredEndIsChecked() {
        XCTAssertEqual(EveningDark.defaultUntilMinutes, 6 * 60)
        XCTAssertEqual(EveningDark.checkedUntil(nil), 6 * 60)
        XCTAssertEqual(EveningDark.checkedUntil(-1), 6 * 60)
        XCTAssertEqual(EveningDark.checkedUntil(24 * 60), 6 * 60)
        XCTAssertEqual(EveningDark.checkedUntil(0), 0)
        XCTAssertEqual(EveningDark.checkedUntil(23 * 60 + 59), 23 * 60 + 59)
        // Unplausibles Ende in isDark/nextChange: wie 06:00.
        let cal = calendar("Europe/Zurich")
        let at = utc("2026-11-17T04:59:00Z")
        XCTAssertTrue(EveningDark.isDark(now: at, calendar: cal, enabled: true, fromMinutes: 20 * 60,
                                         untilMinutes: 9999))
        XCTAssertEqual(text(EveningDark.nextChange(now: at, calendar: cal, enabled: true, fromMinutes: 20 * 60,
                                                   untilMinutes: -3)), "2026-11-17T05:00:00Z")
    }

    func testNextChangeAlwaysFlips() {
        // Jede Viertelstunde einer Woche mit Sommerzeit-Ende: Der Wechsel liegt in der Zukunft und kippt genau dort.
        let cal = calendar("Europe/Zurich")
        let from = 19 * 60 + 45
        var now = utc("2026-10-22T00:07:00Z")
        for _ in 0..<(7 * 96) {
            let dark = EveningDark.isDark(now: now, calendar: cal, enabled: true, fromMinutes: from,
                                          untilMinutes: six)
            guard let next = EveningDark.nextChange(now: now, calendar: cal, enabled: true, fromMinutes: from,
                                                    untilMinutes: six) else {
                XCTFail("\(now)"); return
            }
            XCTAssertGreaterThan(next, now)
            XCTAssertEqual(EveningDark.isDark(now: next, calendar: cal, enabled: true, fromMinutes: from,
                                              untilMinutes: six), !dark, "\(now)")
            XCTAssertEqual(EveningDark.isDark(now: max(now, next.addingTimeInterval(-60)), calendar: cal, enabled: true,
                                              fromMinutes: from, untilMinutes: six), dark, "\(now)")
            now = now.addingTimeInterval(15 * 60)
        }
        XCTAssertNil(EveningDark.nextChange(now: now, calendar: cal, enabled: false, fromMinutes: 18 * 60,
                                            untilMinutes: six))
        XCTAssertNil(EveningDark.nextChange(now: now, calendar: cal, enabled: true, fromMinutes: 22 * 60,
                                            untilMinutes: 22 * 60))
    }

    func testNextChangeFlipsWithinOneDay() {
        // Spanne 13:00–15:00 über eine Woche mit Sommerzeit-Beginn: jeder Wechsel kippt genau dort.
        let cal = calendar("Europe/Zurich")
        var now = utc("2027-03-25T00:07:00Z")
        for _ in 0..<(7 * 96) {
            let dark = EveningDark.isDark(now: now, calendar: cal, enabled: true, fromMinutes: 13 * 60,
                                          untilMinutes: 15 * 60)
            guard let next = EveningDark.nextChange(now: now, calendar: cal, enabled: true, fromMinutes: 13 * 60,
                                                    untilMinutes: 15 * 60) else {
                XCTFail("\(now)"); return
            }
            XCTAssertGreaterThan(next, now)
            XCTAssertEqual(EveningDark.isDark(now: next, calendar: cal, enabled: true, fromMinutes: 13 * 60,
                                              untilMinutes: 15 * 60), !dark, "\(now)")
            now = now.addingTimeInterval(15 * 60)
        }
    }

    private func minutes(_ hhmm: String) -> Int {
        let hm = hhmm.components(separatedBy: ":").compactMap { Int($0) }
        return hm[0] * 60 + hm[1]
    }
}
