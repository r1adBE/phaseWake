import XCTest
@testable import PhaseWakeCore

/// Akkuverbrauch je Nacht: nur Teilstrecken ohne Ladegerät, unbekannt statt falsch, Format alt/neu. Wie `BatteryTrackTest` (Kotlin).
final class BatteryTrackTests: XCTestCase {
    /// 2026-11-13T21:00:00Z.
    private let t0 = Date(timeIntervalSince1970: 1_794_603_600)

    private func at(_ minute: Double) -> Date { t0.addingTimeInterval(minute * 60) }

    private func p(_ minute: Double, _ percent: Int, _ plugged: Bool, _ mark: BatteryMark) -> BatteryPoint {
        BatteryPoint(at: at(minute), percent: percent, plugged: plugged, mark: mark)
    }

    func testUsedWithoutCharging() {
        XCTAssertEqual(BatteryTrack.used([p(0, 80, false, .start), p(570, 71, false, .sample), p(600, 66, false, .end)]), 14)
    }

    func testChargingInBetweenIsLeftOut() {
        XCTAssertEqual(BatteryTrack.used([p(0, 80, false, .start), p(120, 70, true, .plug), p(240, 95, false, .unplug), p(600, 90, false, .end)]), 15)
        XCTAssertEqual(BatteryTrack.used([p(0, 60, true, .start), p(240, 95, false, .unplug), p(600, 90, false, .end)]), 5)
        XCTAssertEqual(BatteryTrack.used([p(0, 80, false, .start), p(120, 70, true, .plug), p(600, 100, true, .end)]), 10)
    }

    func testUnknownGivesNoValue() {
        XCTAssertNil(BatteryTrack.used(nil))
        XCTAssertNil(BatteryTrack.used([]))
        XCTAssertNil(BatteryTrack.used([p(0, 80, false, .start)]))
        XCTAssertNil(BatteryTrack.used([p(0, 80, false, .start), p(10, 79, false, .sample)]))
        XCTAssertNil(BatteryTrack.used([p(0, 80, false, .sample), p(10, 79, false, .end)]))
        XCTAssertNil(BatteryTrack.used([p(0, 80, false, .start), p(600, 90, true, .end)]))
        XCTAssertNil(BatteryTrack.used([p(0, 80, true, .start), p(300, 99, false, .sample), p(600, 90, false, .end)]))
        XCTAssertNil(BatteryTrack.used([p(0, 80, false, .start), p(120, 70, true, .plug), p(600, 90, false, .end)]))
        XCTAssertNil(BatteryTrack.used([p(0, 50, false, .start), p(600, 55, false, .end)]))
        XCTAssertNil(BatteryTrack.used([p(0, 50, true, .start), p(600, 100, true, .end)]))
        XCTAssertNil(BatteryTrack.used([p(10, 80, false, .start), p(0, 79, false, .end)]))
        XCTAssertNil(BatteryTrack.used([p(0, 180, false, .start), p(10, 79, false, .end)]))
        XCTAssertNil(BatteryTrack.used([p(0, 80, false, .start), p(5, 80, false, .plug), p(10, 79, false, .end)]))
        XCTAssertNil(BatteryTrack.used([p(0, 80, false, .start), p(5, 80, false, .start), p(10, 79, false, .end)]))
    }

    func testLifecycle() throws {
        var open = BatteryTrack.begin([], at: at(0), percent: 80, plugged: false)
        XCTAssertEqual(open.count, 1)
        XCTAssertTrue(BatteryTrack.isOpen(open, now: at(1)))
        open = BatteryTrack.begin(open, at: at(570), percent: 72, plugged: false)
        XCTAssertEqual(open.map(\.mark), [.start, .sample])
        XCTAssertEqual(BatteryTrack.charge(open, at: at(575), percent: 72, plugged: false), open)
        open = BatteryTrack.charge(open, at: at(580), percent: 71, plugged: true)
        open = BatteryTrack.charge(open, at: at(585), percent: 73, plugged: false)
        XCTAssertEqual(open.last?.mark, .unplug)
        let track = try XCTUnwrap(BatteryTrack.end(open, at: at(600), percent: 70, plugged: false))
        XCTAssertEqual(track.last?.mark, .end)
        XCTAssertEqual(BatteryTrack.used(track), 8 + 1 + 3)
        XCTAssertFalse(BatteryTrack.isOpen(track, now: at(601)))
        XCTAssertNil(BatteryTrack.end(track, at: at(601), percent: 70, plugged: false))
        XCTAssertEqual(BatteryTrack.sample(track, at: at(601), percent: 70, plugged: false), track)
    }

    func testOrphanedNightIsReplaced() {
        let open = BatteryTrack.begin([], at: at(0), percent: 80, plugged: false)
        let later = at(0).addingTimeInterval(BatteryTrack.maxSpan + 1)
        XCTAssertFalse(BatteryTrack.isOpen(open, now: later))
        XCTAssertNil(BatteryTrack.end(open, at: later, percent: 50, plugged: false))
        XCTAssertEqual(BatteryTrack.begin(open, at: later, percent: 50, plugged: false), [BatteryPoint(at: later, percent: 50, plugged: false, mark: .start)])
        XCTAssertFalse(BatteryTrack.isOpen(open, now: at(-1)))
    }

    func testSamplesAreCapped() {
        var open = BatteryTrack.begin([], at: at(0), percent: 90, plugged: false)
        for i in 0..<(BatteryTrack.maxSamples + 5) { open = BatteryTrack.sample(open, at: at(Double(i) + 1), percent: 90, plugged: false) }
        XCTAssertEqual(open.count, BatteryTrack.maxSamples + 1)
        XCTAssertEqual(BatteryTrack.charge(open, at: at(100), percent: 89, plugged: true).last?.mark, .plug)
    }

    func testFormatRoundTrip() {
        let track = [p(0, 80, false, .start), p(120, 70, true, .plug), p(240, 95, false, .unplug), p(600, 90, false, .end)]
        let text = BatteryTrack.encode(track)
        // Byte-gleich mit Kotlin (BatteryTrackTest.formatRoundTrip).
        XCTAssertEqual(text, "s,1794603600000,80,0;c,1794610800000,70,1;u,1794618000000,95,0;e,1794639600000,90,0")
        XCTAssertEqual(BatteryTrack.decode(text), track)
        XCTAssertNil(BatteryTrack.decode(nil))
        XCTAssertNil(BatteryTrack.decode(""))
        XCTAssertNil(BatteryTrack.decode("x,1,80,0"))
        XCTAssertNil(BatteryTrack.decode("s,1,101,0"))
        XCTAssertNil(BatteryTrack.decode("s,-1,80,0"))
        XCTAssertNil(BatteryTrack.decode("s,1,80,2"))
        XCTAssertNil(BatteryTrack.decode("s,1,80"))
        XCTAssertNil(BatteryTrack.decode("c,1,80,0"))
        XCTAssertNil(BatteryTrack.decode(Array(repeating: "m,1,80,0", count: BatteryTrack.maxPoints + 1).joined(separator: ";")))
    }

    func testBelongsToAndArrival() {
        let night = NightRecord(alarmId: "a", windowStart: at(570), target: at(600), wokeAt: at(590), reason: .lightSleep, snoozes: 0, epochs: [])
        XCTAssertTrue(BatteryTrack.belongs([p(0, 80, false, .start), p(595, 70, false, .end)], to: night))
        XCTAssertFalse(BatteryTrack.belongs([p(591, 80, false, .start), p(595, 70, false, .end)], to: night))
        XCTAssertFalse(BatteryTrack.belongs([p(0, 80, false, .start), p(589, 70, false, .end)], to: night))
        XCTAssertFalse(BatteryTrack.belongs([], to: night))
        XCTAssertTrue(BatteryTrack.arrivedFresh(night, now: at(594)))
        XCTAssertFalse(BatteryTrack.arrivedFresh(night, now: at(596)))
        let withWatch = NightRecord(alarmId: "a", windowStart: at(570), target: at(600), wokeAt: at(590), reason: .lightSleep, snoozes: 0, epochs: [],
                                    watchBattery: [p(0, 90, false, .start), p(610, 70, false, .end)])
        XCTAssertTrue(BatteryTrack.arrivedFresh(withWatch, now: at(614)))
        XCTAssertFalse(BatteryTrack.arrivedFresh(withWatch, now: at(616)))
    }

    func testDescribeForDiagnosis() {
        XCTAssertEqual(BatteryTrack.describe(p(0, 80, false, .start)), "start 80% unplugged")
        XCTAssertEqual(BatteryTrack.describe(p(0, 70, true, .plug)), "charge start 70% plugged")
        XCTAssertEqual(BatteryTrack.describe(p(0, 95, false, .unplug)), "charge end 95% unplugged")
        XCTAssertEqual(BatteryTrack.describe(p(0, 75, false, .sample)), "sample 75% unplugged")
        XCTAssertEqual(BatteryTrack.describe(p(0, 66, false, .end)), "end 66% unplugged")
    }

    func testNightCodecOldAndNew() throws {
        let watch = [p(0, 80, false, .start), p(600, 66, false, .end)]
        let phone = [p(1, 50, false, .start), p(601, 47, false, .end)]
        let night = NightRecord(alarmId: "a", windowStart: Date(timeIntervalSince1970: 0), target: Date(timeIntervalSince1970: 1800),
                                wokeAt: Date(timeIntervalSince1970: 1080), reason: .awake, snoozes: 0, epochs: [])
        XCTAssertEqual(Codec.encodeNight(night), "phasewake-night 1\na|0|1800000|1080000|AWAKE|0|\n\n")
        let both = NightRecord(alarmId: "a", windowStart: night.windowStart, target: night.target, wokeAt: night.wokeAt, reason: .awake, snoozes: 0,
                               epochs: [], watchBattery: watch, phoneBattery: phone)
        let text = Codec.encodeNight(both)
        // Byte-gleich mit Kotlin (BatteryTrackTest.nightCodecOldAndNew).
        XCTAssertEqual(text, "phasewake-night 1\na|0|1800000|1080000|AWAKE|0||"
                       + "|s,1794603600000,80,0;e,1794639600000,66,0|s,1794603660000,50,0;e,1794639660000,47,0\n\n")
        XCTAssertEqual(try Codec.decodeNight(text), both)
        let phoneOnly = NightRecord(alarmId: "a", windowStart: night.windowStart, target: night.target, wokeAt: night.wokeAt, reason: .exact, snoozes: 0,
                                    epochs: [], phoneBattery: phone)
        XCTAssertEqual(Codec.encodeNight(phoneOnly), "phasewake-night 1\na|0|1800000|1080000|DEADLINE|0||EXACT||s,1794603660000,50,0;e,1794639660000,47,0\n\n")
        XCTAssertEqual(try Codec.decodeNight(Codec.encodeNight(phoneOnly)), phoneOnly)
        // Kopien behalten den Akku.
        XCTAssertEqual(both.with(feeling: 4).watchBattery, watch)
        XCTAssertEqual(night.with(phoneBattery: phone).phoneBattery, phone)
        // Wie eine ältere Version liest: leeres Feld 8 = Grund aus Feld 5.
        let fields = text.components(separatedBy: "\n")[1].components(separatedBy: "|")
        XCTAssertEqual(fields[4], "AWAKE")
        XCTAssertEqual(fields[7], "")
        XCTAssertNil(try Codec.decodeNight("phasewake-night 1\na|0|1800000|1080000|AWAKE|0|\n\n").watchBattery)
        let broken = try Codec.decodeNight("phasewake-night 1\na|0|1800000|1080000|AWAKE|0|||kaputt|s,1,80\n\n")
        XCTAssertEqual(broken.reason, .awake)
        XCTAssertNil(broken.watchBattery)
        XCTAssertNil(broken.phoneBattery)
    }
}
