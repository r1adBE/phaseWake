import XCTest
@testable import PhaseWakeCore

/// Diagnose-Protokoll, Bericht der Uhr und Freischalten; Gegenstück zu `DiagLogTest` (Kotlin).
final class DiagLogTests: XCTestCase {
    private let now = ISO8601DateFormatter().date(from: "2026-10-29T06:00:00Z")!

    func testEncodeAndDecode() {
        let ms = AlarmSchedule.millis(now)
        let entry = DiagEntry(at: now, device: .watch, kind: .ring, detail: "light | sleep\nnow")
        let line = DiagLog.encode(entry)
        XCTAssertEqual(line, "\(ms)|W|RING|light   sleep now")
        XCTAssertEqual(DiagLog.decode(line), DiagEntry(at: now, device: .watch, kind: .ring, detail: "light   sleep now"))
        XCTAssertEqual(DiagLog.decode("1|P|NEU|x")?.kind, .other)
        XCTAssertEqual(DiagLog.decode("1|P|BOOT")?.detail, "")
        XCTAssertEqual(DiagLog.decode("1|P|BOOT|a|b")?.detail, "a|b")
        XCTAssertNil(DiagLog.decode("x|P|BOOT|"))
        XCTAssertNil(DiagLog.decode("1|Q|BOOT|"))
        XCTAssertNil(DiagLog.decode("1|P"))
        XCTAssertEqual(DiagLog.clean(String(repeating: "x", count: 500)).count, DiagLog.maxDetail)
    }

    func testTrimKeepsNewestWithinSevenDays() {
        let old = DiagEntry(at: now.addingTimeInterval(-DiagLog.maxAge - 1), device: .phone, kind: .boot, detail: "")
        let edge = DiagEntry(at: now.addingTimeInterval(-DiagLog.maxAge), device: .phone, kind: .boot, detail: "edge")
        XCTAssertEqual(DiagLog.trim([old, edge], now: now), [edge])
        let many = (0..<(DiagLog.maxEntries + 20)).map { DiagEntry(at: now.addingTimeInterval(-Double($0)), device: .phone, kind: .sync, detail: "\($0)") }
        let kept = DiagLog.trim(many, now: now)
        XCTAssertEqual(kept.count, DiagLog.maxEntries)
        XCTAssertEqual(kept.last?.detail, "0")
        XCTAssertTrue(zip(kept, kept.dropFirst()).allSatisfy { $0.at <= $1.at })
    }

    func testFormatForSharing() {
        let text = DiagLog.format([
            DiagEntry(at: now.addingTimeInterval(60), device: .phone, kind: .stop, detail: ""),
            DiagEntry(at: now, device: .watch, kind: .ring, detail: "light"),
        ], timeZone: TimeZone(identifier: "Europe/Zurich")!)
        XCTAssertEqual(text, "2026-10-29 07:00:00 watch RING light\n2026-10-29 07:01:00 phone STOP")
    }

    func testReportRoundTrip() {
        let report = DiagReport(
            status: [(key: "version", value: "1.0"), (key: "next=x", value: "a\nb"), (key: "battery", value: "55")],
            entries: [DiagEntry(at: now, device: .watch, kind: .scheduled, detail: "07:00")]
        )
        let text = DiagReports.encode(report)
        XCTAssertTrue(text.hasPrefix("phasewake-diag 1\nversion=1.0\nnextx=a b\nbattery=55\n--\n"))
        let back = DiagReports.decode(text)
        XCTAssertEqual(back?.status.map(\.key), ["version", "nextx", "battery"])
        XCTAssertEqual(back?.status.map(\.value), ["1.0", "a b", "55"])
        XCTAssertEqual(back?.entries, report.entries)
        XCTAssertNil(DiagReports.decode(nil))
        XCTAssertNil(DiagReports.decode("anders"))
        // Ohne Trennlinie: nur Zustand.
        XCTAssertEqual(DiagReports.decode("phasewake-diag 1\na=1\nkaputt"), DiagReport(status: [(key: "a", value: "1")], entries: []))
    }

    func testUnlockAfterSevenTaps() {
        XCTAssertNil(DiagUnlock.remaining(3))
        XCTAssertEqual(DiagUnlock.remaining(4), 3)
        XCTAssertEqual(DiagUnlock.remaining(6), 1)
        XCTAssertNil(DiagUnlock.remaining(7))
        XCTAssertFalse(DiagUnlock.unlocked(6))
        XCTAssertTrue(DiagUnlock.unlocked(7))
    }
}
