import XCTest
import PhaseWakeCore

/// Weck-Mitteilung als Kette (`NotificationChain`): 4 Mitteilungen im Abstand von 30 s, Kennungen «id», «id.1» … «id.3».
/// Nur Apple.
final class NotificationChainTests: XCTestCase {
    func testIdsKeepBaseFirst() {
        XCTAssertEqual(NotificationChain.ids("phasewake.fallback"),
                       ["phasewake.fallback", "phasewake.fallback.1", "phasewake.fallback.2", "phasewake.fallback.3"])
        XCTAssertEqual(NotificationChain.followingIds("x"), ["x.1", "x.2", "x.3"])
    }

    func testDatesThirtySecondsApart() {
        let first = Date(timeIntervalSince1970: 1_800_000_000)
        let dates = NotificationChain.dates(first)
        XCTAssertEqual(dates.count, NotificationChain.count)
        XCTAssertEqual(dates.first, first)
        XCTAssertEqual(dates.last, first.addingTimeInterval(90))
        XCTAssertEqual(zip(dates, dates.dropFirst()).map { $1.timeIntervalSince($0) }, [30, 30, 30])
    }

    func testBaseIdOfEveryMember() {
        let uuid = "6F9619FF-8B86-D011-B42D-00CF4FC964FF"
        for id in NotificationChain.ids(uuid) { XCTAssertEqual(NotificationChain.baseId(id), uuid) }
        for id in NotificationChain.ids("phasewake.fallback") { XCTAssertEqual(NotificationChain.baseId(id), "phasewake.fallback") }
        for id in NotificationChain.ids("phasewake.fallback.interrupted") {
            XCTAssertEqual(NotificationChain.baseId(id), "phasewake.fallback.interrupted")
        }
    }

    func testBaseIdLeavesOtherIdsAlone() {
        // Eigene Kennungen der Uhr, die mit «phasewake.fallback» beginnen, gehören nicht zur Kette des echten Weckers.
        XCTAssertEqual(NotificationChain.baseId("phasewake.fallback.interrupted"), "phasewake.fallback.interrupted")
        XCTAssertEqual(NotificationChain.baseId("phasewake.fallback.transient"), "phasewake.fallback.transient")
        XCTAssertEqual(NotificationChain.baseId("x.4"), "x.4", "ausserhalb der Kette")
        XCTAssertEqual(NotificationChain.baseId("x.0"), "x.0")
        XCTAssertEqual(NotificationChain.baseId("x.01"), "x.01")
        XCTAssertEqual(NotificationChain.baseId("x.+1"), "x.+1")
        XCTAssertEqual(NotificationChain.baseId("weeklyReview"), "weeklyReview")
    }

    func testAllIdsWithoutDuplicates() {
        XCTAssertEqual(NotificationChain.allIds(["a", "b", "a"]), ["a", "a.1", "a.2", "a.3", "b", "b.1", "b.2", "b.3"])
        XCTAssertEqual(NotificationChain.allIds([]), [])
    }
}
