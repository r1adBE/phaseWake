import PhaseWakeCore
import XCTest
@testable import PhaseWakeWatch

/// Befinden nach dem Wecken auf der Apple Watch (``WatchFeeling``, wie `FeelingPromptTest` auf Wear OS): Die Frage
/// gilt 60 s; die Wahl landet in derselben Nacht im Verlauf der Uhr und geht als diese Nacht ans iPhone.
final class FeelingWatchTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_793_165_400)

    private func storage() -> PhaseWakeStorage {
        let suite = "test-\(UUID().uuidString)"
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(suite)
        return PhaseWakeStorage(defaults: UserDefaults(suiteName: suite)!, directory: dir)
    }

    private func night(_ id: String = "a") -> NightRecord {
        NightRecord(alarmId: id, windowStart: start, target: start.addingTimeInterval(1800),
                    wokeAt: start.addingTimeInterval(1200), reason: .lightSleep, snoozes: 0,
                    epochs: [Epoch(motion: 0.01, heartRate: 52)])
    }

    /// 60 s ab dem Stopp; eine neuere Frage bleibt beim Schliessen der älteren offen.
    @MainActor
    func testAskExpiresAndCloseKeepsANewerOne() throws {
        let feeling = WatchFeeling()
        feeling.ask(night(), now: start)
        let first = try XCTUnwrap(feeling.current(now: start))
        XCTAssertEqual(first.alarmId, "a")
        XCTAssertEqual(first.windowStart, start)
        XCTAssertNotNil(feeling.current(now: start.addingTimeInterval(59)))
        XCTAssertNil(feeling.current(now: start.addingTimeInterval(FeelingAsk.seconds)))
        feeling.ask(night("b"), now: start.addingTimeInterval(1))
        feeling.close(first)
        XCTAssertEqual(feeling.current(now: start.addingTimeInterval(1))?.alarmId, "b")
    }

    /// Die Wahl steht im Verlauf der Uhr und geht als dieselbe Nacht (mit Befinden) ans iPhone; danach keine Frage
    /// mehr. Ohne diese Nacht: nichts gespeichert, nichts geschickt.
    @MainActor
    func testChoiceIsStoredAndSentWithTheNight() throws {
        let last = WatchLastNight(storage: storage())
        last.add(night())
        let feeling = WatchFeeling()
        feeling.ask(night(), now: Date())
        let ask = try XCTUnwrap(feeling.current())
        var sent: [NightRecord] = []
        XCTAssertTrue(feeling.choose(4, for: ask, lastNight: last) { sent.append($0) })
        XCTAssertNil(feeling.current())
        XCTAssertEqual(sent.map(\.feeling), [4])
        XCTAssertEqual(sent.first?.epochs, night().epochs, "dieselbe Nacht")
        XCTAssertEqual(last.stored(alarmId: "a", windowStart: start)?.feeling, 4)

        let other = WatchFeeling.Ask(alarmId: "b", windowStart: start, askedAt: Date())
        XCTAssertFalse(feeling.choose(3, for: other, lastNight: last) { sent.append($0) })
        XCTAssertEqual(sent.count, 1)
    }
}
