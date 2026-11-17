import PhaseWakeCore
import XCTest
@testable import PhaseWakeWatch

/// Plan der Komplikation auf der Apple Watch: der Plan, nach dem die Uhr weckt, unplausible gemerkte Pläne, Plan aus
/// einer anderen Zeitzone (Herkunft: Vollaudit 11.11.2026 AP-9; Vollaudit 071597f AP2-7).
final class ComplicationWatchTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    private func plan(_ id: String = "d", targetIn seconds: TimeInterval) -> WakePlan {
        WakePlan(alarmId: id, windowStart: now.addingTimeInterval(seconds - 1800), target: now.addingTimeInterval(seconds))
    }

    func testComplicationShowsThePlanTheWatchWakesBy() throws {
        let stored = WakePlan(alarmId: "d", windowStart: now.addingTimeInterval(3000), target: now.addingTimeInterval(3600), exact: true)
        let computed = WakePlan(alarmId: "d", windowStart: now.addingTimeInterval(4800), target: now.addingTimeInterval(5400))
        let decoded = try XCTUnwrap(EffectivePlan.decode(EffectivePlan.encode(stored)))
        XCTAssertEqual(decoded, stored)
        XCTAssertEqual(EffectivePlan.choose(stored: decoded, computed: computed, now: now), stored)
        // Zielzeit (plus eine Minute) vorbei: wieder aus den Weckern gerechnet.
        XCTAssertEqual(EffectivePlan.choose(stored: decoded, computed: computed, now: stored.target.addingTimeInterval(60)), computed)
        XCTAssertEqual(EffectivePlan.choose(stored: nil, computed: computed, now: now), computed)
        XCTAssertNil(EffectivePlan.encode(nil))
    }

    func testComplicationIgnoresImplausibleStoredPlans() {
        XCTAssertNil(EffectivePlan.decode(nil))
        XCTAssertNil(EffectivePlan.decode("x"))
        XCTAssertNil(EffectivePlan.decode(["alarm": "d", "window": 2_000_000_000.0, "target": 1_900_000_000.0]))
        XCTAssertNil(EffectivePlan.decode(["alarm": "d", "window": 0.0, "target": 9.2e18]))
        XCTAssertNil(EffectivePlan.decode(["window": 1_800_000_000.0, "target": 1_800_000_600.0]))
    }

    func testComplicationIgnoresPlanOfAnotherZone() throws {
        let stored = plan(targetIn: 3600)
        let zurich = try XCTUnwrap(TimeZone(identifier: "Europe/Zurich"))
        let tokyo = try XCTUnwrap(TimeZone(identifier: "Asia/Tokyo"))
        let value = EffectivePlan.encode(stored, zone: zurich)
        XCTAssertEqual(EffectivePlan.decode(value, zone: zurich), stored)
        XCTAssertNil(EffectivePlan.decode(value, zone: tokyo))
        // Ältere Version ohne Versatz: gilt weiter.
        var legacy = try XCTUnwrap(value)
        legacy["offset"] = nil
        XCTAssertEqual(EffectivePlan.decode(legacy, zone: tokyo), stored)
    }
}
