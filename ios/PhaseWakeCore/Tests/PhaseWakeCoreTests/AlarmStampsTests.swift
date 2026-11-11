import XCTest
@testable import PhaseWakeCore

/// Abgleich der Wecker je Wecker (Audit A-4), wie `AlarmRepositoryTest` auf Android.
final class AlarmStampsTests: XCTestCase {
    private func storage() -> (PhaseWakeStorage, UserDefaults) {
        let suite = "test-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        return (PhaseWakeStorage(defaults: defaults, directory: FileManager.default.temporaryDirectory.appendingPathComponent(suite)), defaults)
    }

    private func alarm(_ id: String, _ hour: Int = 7, _ minute: Int = 0) -> AlarmSpec {
        try! AlarmSpec(id: id, hour: hour, minute: minute)
    }

    private func at(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }

    /// Uhr schaltet den Einmal-Wecker nach dem Klingeln aus, gleichzeitig legt der Nutzer am iPhone einen Wecker an und
    /// ändert einen anderen. Früher gewann die neuere Liste, und eine der Änderungen ging verloren.
    func testAutomaticChangeAndUserEditOnTheOtherDeviceBothSurvive() throws {
        let (phone, _) = storage(), (watch, _) = storage()
        let base = phone.replaceAll([alarm("once"), alarm("daily", 6)], now: at(1_000))
        XCTAssertTrue(watch.applyRemote(base, now: at(1_000)))
        XCTAssertEqual(watch.snapshot().alarms, base.alarms)

        let fromWatch = try XCTUnwrap(watch.mutate({ $0.map { $0.id == "once" ? $0.with(enabled: false) : $0 } }, now: at(5_000)))
        phone.mutate({ $0 + [self.alarm("new", 9)] }, now: at(6_000))
        let fromPhone = try XCTUnwrap(phone.mutate({ $0.map { $0.id == "daily" ? self.alarm("daily", 6, 15) : $0 } }, now: at(6_000)))

        // Beide Stände kreuzen sich; beide Seiten führen zusammen und kommen zum selben Stand.
        XCTAssertTrue(phone.applyRemote(fromWatch, now: at(7_000)))
        XCTAssertTrue(watch.applyRemote(fromPhone, now: at(7_000)))
        let expected = [alarm("daily", 6, 15), alarm("once").with(enabled: false), alarm("new", 9)]
        XCTAssertEqual(phone.snapshot().alarms, expected)
        XCTAssertEqual(watch.snapshot().alarms, expected)
        // Nochmals derselbe Stand: ändert nichts.
        XCTAssertFalse(phone.applyRemote(fromWatch, now: at(7_000)))
    }

    func testDeletionWinsOverOlderEditAndNewerEditWinsOverDeletion() throws {
        let (phone, _) = storage(), (watch, _) = storage()
        watch.applyRemote(phone.replaceAll([alarm("a"), alarm("b", 8)], now: at(1_000)), now: at(1_000))
        // Am iPhone «a» gelöscht (später), auf der Uhr «a» geändert (früher): gelöscht bleibt gelöscht.
        let edited = try XCTUnwrap(watch.mutate({ $0.map { $0.id == "a" ? self.alarm("a", 5) : $0 } }, now: at(2_000)))
        let removed = try XCTUnwrap(phone.mutate({ $0.filter { $0.id != "a" } }, now: at(3_000)))
        phone.applyRemote(edited, now: at(3_000))
        watch.applyRemote(removed, now: at(3_000))
        XCTAssertEqual(phone.snapshot().alarms.map(\.id), ["b"])
        XCTAssertEqual(watch.snapshot().alarms.map(\.id), ["b"])
        // Ein Wecker, den nur eine Seite kennt und nirgends gelöscht ist, bleibt (eben auf der Uhr angelegt).
        let added = try XCTUnwrap(watch.mutate({ $0 + [self.alarm("c", 9)] }, now: at(4_000)))
        XCTAssertTrue(phone.applyRemote(added, now: at(4_000)))
        XCTAssertEqual(phone.snapshot().alarms.map(\.id), ["b", "c"])
        // Neuere Änderung nach dem Löschen (Wiederherstellen einer Sicherung mit «a»): «a» ist wieder da.
        let restored = phone.replaceAll([alarm("a"), alarm("b", 8), alarm("c", 9)], now: at(5_000))
        XCTAssertTrue(watch.applyRemote(restored, now: at(5_000)))
        XCTAssertEqual(watch.snapshot().alarms.map(\.id), ["a", "b", "c"])
        XCTAssertEqual(watch.snapshot().deleted, [:])
    }

    /// Wiederherstellen: gleiche Kennung zählt einmal, alle Wecker gelten als jetzt geändert, fehlende als gelöscht.
    func testReplaceAllStampsEverythingAndMarksMissingDeleted() {
        let (s, _) = storage()
        s.mutate({ _ in [self.alarm("a"), self.alarm("b", 8)] }, now: at(1_000))
        let snap = s.replaceAll([alarm("c", 9), alarm("b", 6), alarm("b", 5)], now: at(2_000))
        XCTAssertEqual(snap.alarms, [alarm("b", 6), alarm("c", 9)])
        XCTAssertEqual(snap.stamps, ["b": 2_000, "c": 2_000])
        XCTAssertEqual(snap.deleted, ["a": 2_000])
        XCTAssertEqual(s.snapshot(), snap)
    }

    /// Gespeichert von einer früheren Version: nur der Listen-Zeitpunkt (7). Eine Änderung an «b» gibt nur «b» den neuen
    /// Zeitpunkt; «a» behält 7 und verliert darum gegen eine neuere Fassung der Gegenseite.
    func testOlderDataWithoutStampsKeepsItsListStamp() throws {
        let (s, defaults) = storage()
        defaults.set(Codec.encodeAlarms([alarm("a"), alarm("b", 8)]), forKey: "alarms")
        defaults.set(NSNumber(value: Int64(7)), forKey: "alarmsUpdatedAt")
        let changed = try XCTUnwrap(s.mutate({ $0.map { $0.id == "b" ? self.alarm("b", 8, 30) : $0 } }, now: at(1_000)))
        XCTAssertEqual(changed.stamps, ["a": 7, "b": 1_000])
        XCTAssertTrue(s.applyRemote(AlarmSnapshot(alarms: [alarm("a", 5)], updatedAt: 8, stamps: ["a": 8]), now: at(1_000)))
        XCTAssertEqual(s.snapshot().alarms, [alarm("a", 5), alarm("b", 8, 30)])
    }

    func testMergeIsTheSameOnBothSidesAndPrunesOldTombstones() {
        let left = AlarmSnapshot(alarms: [alarm("a", 6), alarm("b")], updatedAt: 10, stamps: ["a": 10, "b": 4], deleted: ["old": 1])
        let right = AlarmSnapshot(alarms: [alarm("a", 5), alarm("c")], updatedAt: 9, stamps: ["a": 10, "c": 9], deleted: ["b": 5])
        let now = AlarmStamps.tombstoneMaxAgeMs + 2
        let one = AlarmStamps.merge(local: left, remote: right, now: now)
        let other = AlarmStamps.merge(local: right, remote: left, now: now)
        XCTAssertEqual(one, other)
        XCTAssertEqual(one.alarms.map(\.id), ["a", "c"], "b gelöscht (5 nach 4)")
        XCTAssertEqual(one.deleted, ["b": 5], "zu alter Grabstein «old» weg")
        XCTAssertEqual(one.updatedAt, 10)
    }

    func testPruneKeepsNewestTombstones() {
        let many = Dictionary(uniqueKeysWithValues: (0..<(AlarmStamps.maxTombstones + 5)).map { ("x\($0)", Int64($0)) })
        let kept = AlarmStamps.prune(many, now: 1_000)
        XCTAssertEqual(kept.count, AlarmStamps.maxTombstones)
        XCTAssertNil(kept["x0"])
        XCTAssertNotNil(kept["x\(AlarmStamps.maxTombstones + 4)"])
    }

    /// Anwendungskontext: Zeitpunkte je Wecker und Grabsteine gehen mit; ohne sie (ältere Version) gilt die alte Regel.
    func testContextRoundTripAndLegacy() {
        let snap = AlarmSnapshot(alarms: [alarm("a"), alarm("b", 8)], updatedAt: 9, stamps: ["a": 3], deleted: ["z": 4])
        var context: [String: Any] = [:]
        AlarmStamps.apply(snap, to: &context)
        let read = AlarmStamps.snapshot(context: context)
        XCTAssertEqual(read?.alarms, snap.alarms)
        XCTAssertEqual(read?.stamps, ["a": 3, "b": 9], "fehlender Zeitpunkt = Listen-Zeitpunkt")
        XCTAssertEqual(read?.deleted, ["z": 4])
        XCTAssertEqual(read?.perAlarm, true)
        context[SyncKeys.alarmStamps] = nil
        context[SyncKeys.alarmsDeleted] = nil
        XCTAssertEqual(AlarmStamps.snapshot(context: context)?.perAlarm, false)
        XCTAssertNil(AlarmStamps.snapshot(context: [SyncKeys.alarms: "kaputt", SyncKeys.updatedAt: Int64(1)]))
    }

    /// Fix KN-4: unplausible Zeitpunkte der Gegenseite (z. B. `Int64.max`): Listen-Zeitpunkt → Nachricht verworfen,
    /// Zeitpunkte je Wecker und Grabsteine fallen weg; nichts bricht ab (`now − Zeitpunkt`, `+ 1`).
    func testImplausibleTimesFromThePeer() throws {
        var context: [String: Any] = [:]
        AlarmStamps.apply(AlarmSnapshot(alarms: [alarm("a")], updatedAt: Int64.max), to: &context)
        XCTAssertNil(AlarmStamps.snapshot(context: context))
        context[SyncKeys.updatedAt] = Int64(-1)
        XCTAssertNil(AlarmStamps.snapshot(context: context))
        context[SyncKeys.updatedAt] = Int64(9)
        context[SyncKeys.alarmStamps] = ["a": Int64.max]
        context[SyncKeys.alarmsDeleted] = ["z": Int64.min, "y": Int64(4)]
        let read = try XCTUnwrap(AlarmStamps.snapshot(context: context))
        XCTAssertEqual(read.stamps, [:])
        XCTAssertEqual(read.stampOf("a"), 9)
        XCTAssertEqual(read.deleted, ["y": 4])
        XCTAssertEqual(AlarmStamps.prune(["z": Int64.min, "x": Int64.max, "y": 4], now: Int64.max), [:])
        XCTAssertEqual(AlarmStamps.prune(["z": Int64.min, "y": 4], now: 10), ["y": 4])
    }

    /// Fix KN-4: Ein früher gespeicherter kaputter Listen-Zeitpunkt (`Int64.max`) bricht die nächste Änderung nicht ab
    /// (`updatedAt + 1`); er gilt als nie geändert, der neue Zeitpunkt ist «jetzt».
    func testStoredImplausibleStampDoesNotTrap() throws {
        let (s, defaults) = storage()
        defaults.set(Codec.encodeAlarms([alarm("a")]), forKey: "alarms")
        defaults.set(NSNumber(value: Int64.max), forKey: "alarmsUpdatedAt")
        defaults.set(["a": NSNumber(value: Int64.max)], forKey: "alarmsStamps")
        XCTAssertEqual(s.snapshot().updatedAt, 0)
        XCTAssertEqual(s.snapshot().stamps, [:])
        let changed = try XCTUnwrap(s.mutate({ $0 + [self.alarm("b", 8)] }, now: at(1_000)))
        XCTAssertEqual(changed.updatedAt, 1_000)
        XCTAssertEqual(TimeBounds.next(now: 5, previous: Int64.max), 5)
        XCTAssertEqual(TimeBounds.next(now: 5, previous: 9), 10)
        XCTAssertEqual(TimeBounds.next(now: 5, previous: nil), 5)
    }

    /// Fix KN-5: Unlesbare gespeicherte Wecker (Format einer neueren Version) werden nie mit einer Änderung
    /// überschrieben; `mutate` verweigert (wie `AlarmRepository` auf Android).
    func testUnreadableAlarmsAreNotOverwritten() {
        let (s, defaults) = storage()
        defaults.set("phasewake-alarms 99", forKey: "alarms")
        defaults.set(NSNumber(value: Int64(7)), forKey: "alarmsUpdatedAt")
        XCTAssertEqual(s.snapshot(), AlarmSnapshot(alarms: [], updatedAt: 7))
        XCTAssertNil(s.mutate({ $0 + [self.alarm("a")] }, now: at(1_000)))
        XCTAssertEqual(defaults.string(forKey: "alarms"), "phasewake-alarms 99")
        XCTAssertEqual((defaults.object(forKey: "alarmsUpdatedAt") as? NSNumber)?.int64Value, 7)
    }
}
