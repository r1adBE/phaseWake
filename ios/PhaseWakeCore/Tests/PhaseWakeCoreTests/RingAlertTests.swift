import XCTest
@testable import PhaseWakeCore

/// Weg des Weckbildschirms und neue Kennungen; Gegenstück zu `RingAlertTest` (Kotlin).
final class RingAlertTests: XCTestCase {
    func testVisibleAppOpensDirectly() {
        XCTAssertEqual(RingAlert.route(appVisible: true, companion: false, interactive: true, locked: false, fullScreenAllowed: false), .direct)
        XCTAssertEqual(RingAlert.route(appVisible: true, companion: false, interactive: false, locked: true, fullScreenAllowed: true), .direct)
    }

    func testCompanionAppOpensDirectlyEvenWhileInUse() {
        XCTAssertEqual(RingAlert.route(appVisible: false, companion: true, interactive: true, locked: false, fullScreenAllowed: false), .companion)
        XCTAssertEqual(RingAlert.route(appVisible: false, companion: true, interactive: false, locked: true, fullScreenAllowed: true), .companion)
        XCTAssertEqual(RingAlert.route(appVisible: true, companion: true, interactive: true, locked: false, fullScreenAllowed: true), .direct)
    }

    func testDarkOrLockedScreenGetsFullScreen() {
        XCTAssertEqual(RingAlert.route(appVisible: false, companion: false, interactive: false, locked: false, fullScreenAllowed: true), .fullScreen)
        XCTAssertEqual(RingAlert.route(appVisible: false, companion: false, interactive: true, locked: true, fullScreenAllowed: true), .fullScreen)
    }

    func testDeviceInUseOrNoPermissionGetsHeadsUp() {
        XCTAssertEqual(RingAlert.route(appVisible: false, companion: false, interactive: true, locked: false, fullScreenAllowed: true), .headsUp)
        XCTAssertEqual(RingAlert.route(appVisible: false, companion: false, interactive: false, locked: true, fullScreenAllowed: false), .headsUp)
    }

    func testEveryAlertGetsAFreshId() {
        XCTAssertEqual(RingAlert.nextId(current: nil, first: 3, second: 4), 3)
        XCTAssertEqual(RingAlert.nextId(current: 1, first: 3, second: 4), 3)
        XCTAssertEqual(RingAlert.nextId(current: 3, first: 3, second: 4), 4)
        XCTAssertEqual(RingAlert.nextId(current: 4, first: 3, second: 4), 3)
        var id: Int? = 1
        for _ in 0..<5 {
            let next = RingAlert.nextId(current: id, first: 3, second: 4)
            XCTAssertNotEqual(id, next)
            id = next
        }
        XCTAssertEqual(RingAlert.realertSeconds, 20)
    }
}
