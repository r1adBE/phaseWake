import CoreMotion
import PhaseWakeCore
import WatchKit
import XCTest
@testable import PhaseWakeWatch

/// Regeln des Rekorders auf der Apple Watch: Zeitspanne nur mit Erlaubnis gemerkt, Pausen beim Lesen, Laden abtasten,
/// nachgereichter Start.
final class RecorderRulesWatchTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    private func minutes(_ value: Double) -> Date { now.addingTimeInterval(value * 60) }

    func testRecorderSpanRememberedOnlyWithPermission() {
        XCTAssertTrue(LearnRecorder.remembersSpan(.authorized))
        XCTAssertFalse(LearnRecorder.remembersSpan(.notDetermined))
        XCTAssertFalse(LearnRecorder.remembersSpan(.denied))
        XCTAssertFalse(LearnRecorder.remembersSpan(.restricted))
    }

    func testRecorderPauseNeverPassesTheDeadline() {
        XCTAssertEqual(LearnRecorder.pauseLength(0.2, now: now, deadline: now.addingTimeInterval(10)), 0.2)
        // Date rechnet ab 2001 in Sekunden: ~5e-8 Rundung
        XCTAssertEqual(LearnRecorder.pauseLength(0.2, now: now, deadline: now.addingTimeInterval(0.05)), 0.05,
                       accuracy: 1e-6)
        XCTAssertEqual(LearnRecorder.pauseLength(0.2, now: now, deadline: now.addingTimeInterval(-1)), 0)
        XCTAssertEqual(LearnRecorder.pauseLength(0, now: now, deadline: now.addingTimeInterval(10)), 0)
    }

    /// Stichproben 22:00 lädt, 22:30 lädt, 23:00 nicht: am Ladegerät 22:00–23:00. Lädt die letzte, höchstens bis 15 Min
    /// danach (die Uhr weiss nicht, wann sie abgenommen wurde; die Puls-Regel deckt den Rest).
    func testChargeSpansFromSamples() {
        let gap = LearnRecorder.chargeCheck
        XCTAssertEqual(gap, 15 * 60)
        let spans = LearnRecorder.chargeSpans([(at: minutes(30), charging: true), (at: minutes(0), charging: true),
                                               (at: minutes(60), charging: false)],
                                              until: minutes(300), gap: gap)
        XCTAssertEqual(spans.map { $0.from }, [minutes(0)])
        XCTAssertEqual(spans.map { $0.until }, [minutes(60)])
        let open = LearnRecorder.chargeSpans([(at: minutes(0), charging: false), (at: minutes(90), charging: true)],
                                             until: minutes(300), gap: gap)
        XCTAssertEqual(open.map { $0.from }, [minutes(90)])
        XCTAssertEqual(open.map { $0.until }, [minutes(105)])
        // Nie über das Ende der Rechnung hinaus, und ohne ladende Stichprobe keine Zeit.
        XCTAssertEqual(LearnRecorder.chargeSpans([(at: minutes(90), charging: true)], until: minutes(95), gap: gap)
            .map { $0.until }, [minutes(95)])
        XCTAssertTrue(LearnRecorder.chargeSpans([(at: minutes(0), charging: false)], until: minutes(300), gap: gap)
            .isEmpty)
    }

    func testRetriedRecordStartIsAcceptedUpToTenMinutes() {
        let nowMs = TimeBounds.millis(now)
        // Frisch wie bisher.
        XCTAssertTrue(RecordRetry.accepts(sentAtMs: nowMs - 60_000, retryTapMs: nil, now: now))
        // Spät ohne Kennzeichen: nein; nachgereicht mit Tippen vor 9 Min: ja, vor 11 Min: nein.
        XCTAssertFalse(RecordRetry.accepts(sentAtMs: nowMs - 300_000, retryTapMs: nil, now: now))
        XCTAssertTrue(RecordRetry.accepts(sentAtMs: nowMs - 300_000, retryTapMs: nowMs - 540_000, now: now))
        XCTAssertFalse(RecordRetry.accepts(sentAtMs: nowMs - 300_000, retryTapMs: nowMs - 660_000, now: now))
        // Kaputte Zeiten: nein.
        XCTAssertFalse(RecordRetry.accepts(sentAtMs: Int64.min, retryTapMs: nowMs, now: now))
        XCTAssertFalse(RecordRetry.accepts(sentAtMs: nowMs - 300_000, retryTapMs: Int64.max, now: now))
    }
}
