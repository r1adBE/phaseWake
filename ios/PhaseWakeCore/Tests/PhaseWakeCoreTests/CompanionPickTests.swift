import XCTest
@testable import PhaseWakeCore

/// Wahl der Uhr für die Begleit-App-Zuordnung; Gegenstück zu `CompanionPickTest` (Kotlin).
final class CompanionPickTests: XCTestCase {
    private let buds = PairedDevice(address: "00:00:00:00:00:01", name: "Galaxy Buds3 Pro", wearable: false)
    private let watch = PairedDevice(address: "00:00:00:00:00:02", name: "Galaxy Watch8 Classic (1A2B)", wearable: true)
    private let band = PairedDevice(address: "00:00:00:00:00:03", name: "Fit Band", wearable: true)
    private let car = PairedDevice(address: "00:00:00:00:00:04", name: nil, wearable: false)

    func testSameNameAsConnectedWatchWins() {
        XCTAssertEqual(CompanionPick.choose([buds, band, watch], nodeNames: [" galaxy watch8 classic (1a2b) "]), watch)
    }

    func testPartOfTheNameCountsWhenNoneIsEqual() {
        XCTAssertEqual(CompanionPick.choose([buds, band, watch, car], nodeNames: ["Galaxy Watch8 Classic"]), watch)
        let exact = PairedDevice(address: "00:00:00:00:00:05", name: "Galaxy Watch8 Classic", wearable: true)
        XCTAssertEqual(CompanionPick.choose([watch, exact], nodeNames: ["Galaxy Watch8 Classic"]), exact)
    }

    func testSingleWearableWithoutNameMatch() {
        XCTAssertEqual(CompanionPick.choose([buds, watch, car], nodeNames: []), watch)
        XCTAssertEqual(CompanionPick.choose([buds, watch], nodeNames: ["Pixel Watch 3", ""]), watch)
    }

    func testNoneOrSeveralWearablesLeaveTheChoiceToTheUser() {
        XCTAssertNil(CompanionPick.choose([], nodeNames: ["Galaxy Watch8 Classic (1A2B)"]))
        XCTAssertNil(CompanionPick.choose([buds, car], nodeNames: []))
        XCTAssertNil(CompanionPick.choose([watch, band], nodeNames: []))
        XCTAssertNil(CompanionPick.choose([PairedDevice(address: "00:00:00:00:00:06", name: " ", wearable: false)], nodeNames: [" "]))
    }
}
