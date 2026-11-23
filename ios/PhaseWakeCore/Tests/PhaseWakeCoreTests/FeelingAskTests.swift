import XCTest
@testable import PhaseWakeCore

/// Befinden auf der Uhr nach dem Wecken. Gegenstück: `FeelingAskTest` (Kotlin), gleiche Fälle.
final class FeelingAskTests: XCTestCase {
    /// Nur nach echtem Wecken, auf der Uhr gestoppt, und ohne schon bekanntes Befinden.
    func testAsksOnlyAfterARealNightStoppedOnTheWatch() {
        XCTAssertTrue(FeelingAsk.asks(recorded: true, stoppedOnWatch: true))
        // Probewecker, Powernap, nie geklingelt: keine Nacht im Verlauf.
        XCTAssertFalse(FeelingAsk.asks(recorded: false, stoppedOnWatch: true))
        // Am iPhone gestoppt, Abbruch.
        XCTAssertFalse(FeelingAsk.asks(recorded: true, stoppedOnWatch: false))
        XCTAssertFalse(FeelingAsk.asks(recorded: true, stoppedOnWatch: true, known: true))
    }

    /// 60 s ab dem Stellen; genau 60 s danach und bei rückwärts gestellter Uhr nicht mehr.
    func testOpenForSixtySeconds() {
        XCTAssertEqual(FeelingAsk.seconds, 60)
        let at = Date(timeIntervalSince1970: 1_793_250_000)
        XCTAssertTrue(FeelingAsk.isOpen(askedAt: at, now: at))
        XCTAssertTrue(FeelingAsk.isOpen(askedAt: at, now: at.addingTimeInterval(59.999)))
        XCTAssertFalse(FeelingAsk.isOpen(askedAt: at, now: at.addingTimeInterval(60)))
        XCTAssertFalse(FeelingAsk.isOpen(askedAt: at, now: at.addingTimeInterval(-0.001)))
    }

    /// Gespeichertes Befinden hat Vorrang; ohne gespeichertes gilt das ankommende.
    func testStoredFeelingWins() {
        XCTAssertEqual(FeelingAsk.merged(stored: 4, incoming: 2), 4)
        XCTAssertEqual(FeelingAsk.merged(stored: nil, incoming: 2), 2)
        XCTAssertEqual(FeelingAsk.merged(stored: 4, incoming: nil), 4)
        XCTAssertNil(FeelingAsk.merged(stored: nil, incoming: nil))
    }
}
