import PhaseWakeCore
import WatchConnectivity
import XCTest
@testable import PhaseWake

/// Audit 17.11.: Zeilen aus MetricKit (PERF-03, ``ExitLines``) und Diagnose-Zeilen für verworfene Nächte und
/// gescheitertes Speichern (CODE-02).
final class DiagLinesPhoneTests: XCTestCase {
    private let utc = TimeZone(identifier: "UTC")!
    private let at = Date(timeIntervalSince1970: 1_794_000_000) // 2026-11-06 21:20 UTC

    // MARK: PERF-03

    func testCrashLineHasKindTimeAndCodesOnly() {
        let line = ExitLines.line("crash", at: at, fields: [
            ("sig", ExitLines.number(11)), ("exc", ExitLines.number(1)), ("code", ExitLines.number(nil)), ("v", "1.4"),
            ("reason", "  Namespace SIGNAL, Code 11 Segmentation fault: 11  "),
        ], zone: utc)
        XCTAssertEqual(line, "exit crash at 2026-11-06 21:20 sig=11 exc=1 v=1.4 reason=Namespace SIGNAL, Code 11 Segmentation fault: 11")
        // Passt ins Protokoll, ohne Trennzeichen.
        XCTAssertEqual(DiagLog.clean(line), line)
    }

    func testLongReasonIsCut() {
        let line = ExitLines.line("crash", at: at, fields: [("reason", String(repeating: "x", count: 500))], zone: utc)
        XCTAssertEqual(line, "exit crash at 2026-11-06 21:20 reason=" + String(repeating: "x", count: ExitLines.maxReason))
    }

    func testDurationsAndWritesAreFormattedWithoutLocale() {
        XCTAssertEqual(ExitLines.seconds(Measurement(value: 2500, unit: .milliseconds)), "2.5s")
        XCTAssertEqual(ExitLines.seconds(Measurement(value: 3, unit: .minutes)), "180.0s")
        XCTAssertEqual(ExitLines.megabytes(Measurement(value: 1.1, unit: .gigabytes)), "1100MB")
        XCTAssertEqual(ExitLines.line("hang", at: at, fields: [("dur", "2.5s"), ("v", "")], zone: utc), "exit hang at 2026-11-06 21:20 dur=2.5s")
    }

    func testReportIsLoggedOnlyOnce() {
        XCTAssertTrue(ExitLines.isNew(end: 100, seenUntil: 0))
        XCTAssertFalse(ExitLines.isNew(end: 100, seenUntil: 100))
        XCTAssertFalse(ExitLines.isNew(end: 99, seenUntil: 100))
    }

    // MARK: CODE-02

    func testUnreadableNightIsLogged() {
        let seen = UserDefaults.standard.object(forKey: PhoneConnectivity.watchSeenKey)
        defer { UserDefaults.standard.set(seen, forKey: PhoneConnectivity.watchSeenKey) }
        let start = Date()
        PhoneConnectivity.shared.session(WCSession.default, didReceiveUserInfo: [SyncKeys.night: "not a night"])
        let logged = DiagStore.shared.entries().filter { $0.at >= start.addingTimeInterval(-1) }
        XCTAssertTrue(logged.contains { $0.kind == .sync && $0.detail == "night unreadable, dropped (len=11)" }, "\(logged)")
    }

    @MainActor
    func testFailedLearningWriteIsLogged() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("learn-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let learning = PhoneLearning(directory: dir)
        // Nach dem Lesen (Datei fehlte) liegt an ihrer Stelle ein Ordner: Das Schreiben scheitert.
        let blocker = dir.appendingPathComponent("learning.v1", isDirectory: true)
        try FileManager.default.createDirectory(at: blocker, withIntermediateDirectories: true)
        try Data("x".utf8).write(to: blocker.appendingPathComponent("keep"))
        let start = Date()
        _ = learning.watchSnapshot()
        let logged = DiagStore.shared.entries().filter { $0.at >= start.addingTimeInterval(-1) }
        XCTAssertTrue(logged.contains { $0.kind == .error && $0.detail.hasPrefix("learning not saved") }, "\(logged)")
    }
}
