import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Nacht aus Apple Health in der Zone ihrer Aufzeichnung anzeigen (Audit F-3), wie `NightZoneTest` (Android).
final class NightZonePhoneTests: XCTestCase {
    private let tokyo = TimeZone(secondsFromGMT: 9 * 3600)!

    /// Nacht in Tokio 23:00–07:00 (+09:00), also 14:00–22:00 UTC.
    private func item(_ zone: TimeZone?) -> HistoryItem {
        let start = Date(timeIntervalSince1970: 1_790_863_200) // 2026-10-01T14:00:00Z
        let end = start.addingTimeInterval(8 * 3600)
        let session = HealthSession(start: start, end: end, stages: [StageSpan(start: start, end: end, stage: .light)], timeZone: zone)
        return HistoryItem(kind: .health, date: "2026-10-01", session: session)
    }

    func testTimesAndTitleUseTheZoneOfTheRecording() {
        let data = NightDetailData(item: item(tokyo), stages: [])
        XCTAssertEqual(data.timeZone, tokyo)
        XCTAssertEqual(data.timeline?.timeZone, tokyo)
        XCTAssertEqual(data.title, Format.day(data.item.at, in: tokyo))
        XCTAssertEqual(Format.time(data.inBed.start, in: tokyo), Format.time(hour: 23, minute: 0))
        XCTAssertNil(NightDetailData(item: item(nil), stages: []).timeZone)
    }

    func testFormatFallsBackToTheDeviceZone() {
        let at = Date(timeIntervalSince1970: 1_790_863_200)
        XCTAssertEqual(Format.time(at, in: nil), Format.time(at))
        XCTAssertEqual(Format.day(at, in: nil), at.formatted(date: .abbreviated, time: .omitted))
    }
}
