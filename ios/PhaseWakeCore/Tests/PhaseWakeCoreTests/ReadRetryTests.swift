import XCTest
import PhaseWakeCore

/// Lesen der AlarmKit-Alarme mit einem zweiten Versuch (`ReadRetry`): genau eine Wiederholung nach 1 s, danach gelten
/// sie als fehlend. Nur Apple.
final class ReadRetryTests: XCTestCase {
    func testRetriesExactlyOnce() {
        XCTAssertTrue(ReadRetry.retries(after: 1), "nach dem ersten Fehler ein zweiter Versuch")
        XCTAssertFalse(ReadRetry.retries(after: 2), "nach dem zweiten Fehler keiner mehr")
        XCTAssertFalse(ReadRetry.retries(after: 3))
    }

    func testShortPause() {
        XCTAssertEqual(ReadRetry.pause, 1)
    }

    /// Ablauf wie im Abgleich: Versuche bis Erfolg oder Ende; zählt die Versuche.
    private func run(failures: Int) -> (attempts: Int, ok: Bool) {
        var attempt = 1
        while true {
            if attempt > failures { return (attempt, true) }
            guard ReadRetry.retries(after: attempt) else { return (attempt, false) }
            attempt += 1
        }
    }

    func testFlow() {
        XCTAssertEqual(run(failures: 0).attempts, 1)
        XCTAssertTrue(run(failures: 0).ok)
        XCTAssertEqual(run(failures: 1).attempts, 2)
        XCTAssertTrue(run(failures: 1).ok, "ein einzelner Fehler: zweiter Versuch gelingt")
        XCTAssertEqual(run(failures: 5).attempts, 2, "nie mehr als zwei Versuche")
        XCTAssertFalse(run(failures: 5).ok, "beide gescheitert: gilt als fehlend")
    }

    func testDiagLines() {
        XCTAssertEqual(ReadRetry.failureLine("alarmkit list", attempt: 1, code: 7),
                       "alarmkit list failed 7, attempt 1, retry in 1 s")
        XCTAssertEqual(ReadRetry.failureLine("alarmkit list", attempt: 2, code: 7),
                       "alarmkit list failed 7, attempt 2, treated as missing")
        XCTAssertEqual(ReadRetry.recoveredLine("alarmkit list", attempt: 2), "alarmkit list ok, attempt 2")
    }
}
