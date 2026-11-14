import XCTest
@testable import PhaseWakeCore

/// Auto-Aus des Einmal-Weckers nach dem Klingeln (Fix G-1/S-1), erneut angewendeter Anwendungskontext (Fix S-5) und
/// Stand der Lernphase ohne Zeitpunkt (Fix S-3). Wie `AlarmAutoOffTest` und `LearningStampTest` (Kotlin).
final class AlarmAutoOffTests: XCTestCase {
    private let calendar = Calendar.gregorian(in: TimeZone(identifier: "UTC")!)

    private func storage() -> PhaseWakeStorage {
        let suite = "test-\(UUID().uuidString)"
        return PhaseWakeStorage(defaults: UserDefaults(suiteName: suite)!,
                                directory: FileManager.default.temporaryDirectory.appendingPathComponent(suite))
    }

    private func alarm(_ hour: Int, _ minute: Int = 0, days: Set<Weekday> = [], enabled: Bool = true, label: String = "",
                       id: String = "a") -> AlarmSpec {
        try! AlarmSpec(id: id, hour: hour, minute: minute, days: days, enabled: enabled, label: label)
    }

    /// 14.11.2026 hh:mm UTC.
    private func at(_ hour: Int, _ minute: Int = 0, day: Int = 14) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 11, day: day, hour: hour, minute: minute))!
    }

    private func rangAt7(_ spec: AlarmSpec) -> Bool {
        AlarmStamps.rang(spec, rung: nil, target: at(7), calendar: calendar, shifted: false)
    }

    /// Beide Geräte mit Wecker «a» um 07:00, am Abend davor gestellt und abgeglichen.
    private func pair() -> (PhaseWakeStorage, PhaseWakeStorage) {
        let phone = storage(), watch = storage()
        let first = phone.mutate({ $0 + [self.alarm(7)] }, now: at(20, day: 13))!
        watch.applyRemote(first, now: at(20, day: 13))
        return (phone, watch)
    }

    private func exchange(_ a: PhaseWakeStorage, _ b: PhaseWakeStorage, now: Date) {
        for _ in 0..<3 {
            a.applyRemote(b.snapshot(), now: now)
            b.applyRemote(a.snapshot(), now: now)
        }
    }

    func testChangeOnPhoneWhileWatchUnreachableSurvivesAutoOff() throws {
        let (phone, watch) = pair()
        // 23:00 am iPhone auf 08:00 verschoben; die Uhr ist nicht erreichbar.
        phone.mutate({ _ in [self.alarm(8)] }, now: at(23, day: 13))
        // 07:05: Die Uhr klingelt nach altem Stand und schaltet ihn aus.
        XCTAssertNotNil(watch.disableAfterRing("a", now: at(7, 5), rang: rangAt7))
        exchange(phone, watch, now: at(7, 5))
        for device in [phone, watch] {
            let a = try XCTUnwrap(device.snapshot().alarms.first)
            XCTAssertEqual(a.hour, 8)
            XCTAssertTrue(a.enabled, "die neuere Änderung vom iPhone gilt auf beiden Geräten")
        }
    }

    func testAutoOffWithoutNewerChangeReachesTheOtherDevice() throws {
        let (phone, watch) = pair()
        let before = watch.snapshot()
        let off = try XCTUnwrap(watch.disableAfterRing("a", now: at(7, 5), rang: rangAt7))
        XCTAssertEqual(off.stampOf("a"), before.stampOf("a") + 1, "Fassung, die geklingelt hat, + 1 ms")
        XCTAssertEqual(off.updatedAt, TimeBounds.millis(at(7, 5)))
        exchange(phone, watch, now: at(7, 5))
        XCTAssertEqual(phone.snapshot().alarms.map(\.enabled), [false])
        XCTAssertEqual(watch.snapshot().alarms.map(\.enabled), [false])
    }

    func testVersionMovedAfterTheRingIsNotSwitchedOff() throws {
        let (_, watch) = pair()
        let rung = try XCTUnwrap(watch.snapshot().alarms.first)
        // Im Schlummern auf 07:30 verschoben.
        watch.mutate({ _ in [self.alarm(7, 30)] }, now: at(7, 3))
        XCTAssertNil(watch.disableAfterRing("a", now: at(7, 10)) {
            AlarmStamps.rang($0, rung: rung, target: self.at(7), calendar: self.calendar, shifted: false)
        })
        // Ohne bekannte Fassung (Neustart der App): die Zeitregel erkennt die Verschiebung ebenso.
        XCTAssertNil(watch.disableAfterRing("a", now: at(7, 10), rang: rangAt7))
        XCTAssertEqual(watch.snapshot().alarms.map(\.enabled), [true])
    }

    func testRepeatingDisabledOrMissingAlarmsAreNeverSwitchedOff() {
        let device = storage()
        device.mutate({ _ in [self.alarm(7, days: [.saturday])] }, now: at(6))
        XCTAssertNil(device.disableAfterRing("a", now: at(7)) { _ in true })
        device.mutate({ _ in [self.alarm(7, enabled: false)] }, now: at(6))
        XCTAssertNil(device.disableAfterRing("a", now: at(7)) { _ in true })
        XCTAssertNil(device.disableAfterRing("missing", now: at(7)) { _ in true })
    }

    func testRangRules() {
        let target = at(7)
        // Bekannte Fassung: Uhrzeit und Tage entscheiden.
        XCTAssertTrue(AlarmStamps.rang(alarm(7, label: "neu"), rung: alarm(7), target: target, calendar: calendar, shifted: false))
        XCTAssertFalse(AlarmStamps.rang(alarm(7, 1), rung: alarm(7), target: target, calendar: calendar, shifted: false))
        XCTAssertFalse(AlarmStamps.rang(alarm(7, days: [.monday]), rung: alarm(7), target: target, calendar: calendar, shifted: false))
        // Unbekannt: eingestellte Weckzeit = Zielzeit.
        XCTAssertTrue(AlarmStamps.rang(alarm(7), rung: nil, target: target, calendar: calendar, shifted: false))
        XCTAssertFalse(AlarmStamps.rang(alarm(7, 1), rung: nil, target: target, calendar: calendar, shifted: false))
        XCTAssertFalse(AlarmStamps.rang(alarm(6, 59), rung: nil, target: target, calendar: calendar, shifted: false))
        // Früher geweckt («Gute Nacht»): eingestellte Weckzeit bis 16 Std. nach der Zielzeit.
        XCTAssertTrue(AlarmStamps.rang(alarm(7), rung: nil, target: at(6, 20), calendar: calendar, shifted: true))
        XCTAssertFalse(AlarmStamps.rang(alarm(7), rung: nil, target: at(6, 20), calendar: calendar, shifted: false))
        let sixteen = target.addingTimeInterval(-16 * 3600)
        XCTAssertTrue(AlarmStamps.rang(alarm(7), rung: nil, target: sixteen, calendar: calendar, shifted: true))
        XCTAssertFalse(AlarmStamps.rang(alarm(7), rung: nil, target: sixteen.addingTimeInterval(-0.001), calendar: calendar, shifted: true))
    }

    func testShiftLimitIsSixteenHours() {
        XCTAssertEqual(AlarmStamps.shiftMaxAheadMs, 57_600_000)
        XCTAssertEqual(Double(AlarmStamps.shiftMaxAheadMs) / 1000, GoodNightEstimate.maxAhead)
    }

    func testAutoOffWithoutPlausibleStampUsesTheListStamp() throws {
        let local = AlarmSnapshot(alarms: [alarm(7)], updatedAt: 5_000, stamps: ["a": Int64.max])
        let off = try XCTUnwrap(AlarmStamps.autoOff(local, id: "a", listStamp: 6_000) { _ in true })
        XCTAssertEqual(off.stampOf("a"), 6_000)
        XCTAssertEqual(off.alarms.map(\.enabled), [false])
    }

    // MARK: Audit BE-3 (wie `SyncConstantsTest`, Kotlin)

    func testTombstoneIsKeptNinetyDaysInclusive() {
        XCTAssertEqual(AlarmStamps.tombstoneMaxAgeMs, 7_776_000_000)
        let deleted: [String: Int64] = ["x": 1_000]
        XCTAssertEqual(AlarmStamps.prune(deleted, now: 1_000 + 7_776_000_000), deleted, "genau 90 Tage: bleibt")
        XCTAssertEqual(AlarmStamps.prune(deleted, now: 1_000 + 7_776_000_001), [:], "90 Tage + 1 ms: weg")
    }

    func testDeletionWinsATieWithTheAlarm() {
        let kept = AlarmSnapshot(alarms: [alarm(7)], updatedAt: 5_000, stamps: ["a": 5_000])
        let gone = AlarmSnapshot(alarms: [], updatedAt: 5_000, deleted: ["a": 5_000])
        XCTAssertEqual(AlarmStamps.merge(local: kept, remote: gone, now: 5_000).alarms, [], "gleicher Zeitpunkt: gelöscht")
        XCTAssertEqual(AlarmStamps.merge(local: gone, remote: kept, now: 5_000).alarms, [])
        let newer = AlarmSnapshot(alarms: [alarm(7)], updatedAt: 5_001, stamps: ["a": 5_001])
        XCTAssertEqual(AlarmStamps.merge(local: newer, remote: gone, now: 5_001).alarms, [alarm(7)], "1 ms neuer: bleibt")
    }

    // MARK: Fix S-5

    func testSameContextIsNotAppliedTwiceAfterTheTombstoneExpired() throws {
        let phone = storage(), watch = storage()
        let day: TimeInterval = 24 * 3600
        let start = at(8)
        // Kontext der Uhr mit Wecker «x», am iPhone übernommen.
        let fromWatch = try XCTUnwrap(watch.mutate({ $0 + [self.alarm(7, id: "x")] }, now: start))
        XCTAssertTrue(phone.applyRemoteContext(fromWatch, now: start))
        // «x» am iPhone gelöscht; der Grabstein verfällt nach 90 Tagen.
        phone.mutate({ $0.filter { $0.id != "x" } }, now: start.addingTimeInterval(60))
        let later = start.addingTimeInterval(91 * day)
        // Eine spätere Änderung am iPhone speichert ohne den verfallenen Grabstein.
        phone.mutate({ $0 + [self.alarm(6, id: "z")] }, now: later)
        XCTAssertEqual(phone.snapshot().deleted, [:])
        // Neustart: WatchConnectivity liefert denselben alten Kontext erneut.
        XCTAssertFalse(phone.applyRemoteContext(fromWatch, now: later))
        XCTAssertEqual(phone.snapshot().alarms.map(\.id), ["z"])
        // Ein neuer Stand der Uhr gilt wie bisher.
        let next = try XCTUnwrap(watch.mutate({ $0 + [self.alarm(9, id: "y")] }, now: later))
        XCTAssertTrue(phone.applyRemoteContext(next, now: later))
        XCTAssertTrue(phone.snapshot().alarms.map(\.id).contains("y"))
    }

    func testFingerprintDependsOnEveryPart() {
        let base = AlarmSnapshot(alarms: [alarm(7)], updatedAt: 1_000, stamps: ["a": 900], deleted: ["b": 800])
        XCTAssertEqual(AlarmStamps.fingerprint(base), AlarmStamps.fingerprint(base))
        let variants = [
            AlarmSnapshot(alarms: [alarm(7, enabled: false)], updatedAt: 1_000, stamps: ["a": 900], deleted: ["b": 800]),
            AlarmSnapshot(alarms: [alarm(7)], updatedAt: 1_001, stamps: ["a": 900], deleted: ["b": 800]),
            AlarmSnapshot(alarms: [alarm(7)], updatedAt: 1_000, stamps: ["a": 901], deleted: ["b": 800]),
            AlarmSnapshot(alarms: [alarm(7)], updatedAt: 1_000, stamps: ["a": 900], deleted: [:]),
            AlarmSnapshot(alarms: [alarm(7)], updatedAt: 1_000, stamps: ["a": 900], deleted: ["b": 800], perAlarm: false),
        ]
        for variant in variants { XCTAssertNotEqual(AlarmStamps.fingerprint(variant), AlarmStamps.fingerprint(base)) }
    }

    // MARK: Fix S-3

    func testFreshPhoneStampsItsLearningStateOnce() {
        var state = LearningState()
        XCTAssertEqual(state.updatedAtMs, 0)
        XCTAssertTrue(state.stampIfNever(now: at(8)))
        XCTAssertEqual(state.updatedAtMs, TimeBounds.millis(at(8)))
        XCTAssertFalse(state.stampIfNever(now: at(9)))
        XCTAssertEqual(state.updatedAtMs, TimeBounds.millis(at(8)))
        XCTAssertNil(state.consent)
        // Die Uhr mit dem Stand eines früheren iPhones übernimmt den frischen Stand.
        let old = LearningSnapshot(active: true, floor: 0.02, restingHeartRate: nil, updatedAtMs: TimeBounds.millis(at(8, day: 13)))
        XCTAssertTrue(state.snapshot.replaces(old))
    }
}
