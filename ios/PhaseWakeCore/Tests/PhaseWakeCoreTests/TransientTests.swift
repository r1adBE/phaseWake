import XCTest
@testable import PhaseWakeCore

/// Probewecker, Powernap und Testergebnis; Gegenstück zu `TransientTest` (Kotlin).
final class TransientTests: XCTestCase {
    private var zurich: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }

    /// Donnerstag, 29. Oktober 2026, 14:00 in Zürich (13:00 UTC).
    private let now = ISO8601DateFormatter().date(from: "2026-10-29T13:00:00Z")!
    private let base = try! AlarmSpec(
        id: "a", hour: 7, minute: 0, phone: .vibrate, watchSound: true, sound: "raw:wake_birds", label: "Arbeit",
        sunrise: .window, wakeTask: .math, sunriseDevice: .both
    )

    private func minutes(_ m: Int) -> TimeInterval { TimeInterval(m * 60) }

    func testTestPlanStartsInOneAndRingsInTwoMinutes() {
        let t = Transient.test(base: base, now: now, calendar: zurich, uuid: "1234")
        XCTAssertEqual(t.kind, .test)
        XCTAssertEqual(t.plan.alarmId, "test-1234")
        XCTAssertEqual(t.plan.windowStart, now.addingTimeInterval(60))
        XCTAssertEqual(t.plan.target, now.addingTimeInterval(120))
        XCTAssertEqual(t.spec.id, t.plan.alarmId)
        XCTAssertEqual(t.spec.hour, 14)
        XCTAssertEqual(t.spec.minute, 2)
        XCTAssertEqual(t.spec.phone, .vibrate)
        XCTAssertTrue(t.spec.watchSound)
        XCTAssertEqual(t.spec.sound, "raw:wake_birds")
        XCTAssertEqual(t.spec.sunrise, .window)
        XCTAssertEqual(t.spec.sunriseDevice, .both)
        XCTAssertEqual(t.spec.wakeTask, WakeTask.none)
        XCTAssertEqual(t.spec.label, "")
        XCTAssertTrue(t.spec.isOneShot)
    }

    func testTestWithoutAlarmUsesDefaults() {
        let t = Transient.test(base: nil, now: now, calendar: zurich, uuid: "x")
        XCTAssertEqual(t.spec.phone, .sound)
        XCTAssertFalse(t.spec.watchSound)
        XCTAssertEqual(t.spec.sunrise, .off)
    }

    func testNapWindowAndLimits() {
        let nap = Transient.nap(base: base, now: now, calendar: zurich, minutes: 20, uuid: "n1")
        XCTAssertEqual(nap.plan.alarmId, "nap-n1")
        XCTAssertEqual(nap.plan.target, now.addingTimeInterval(minutes(20)))
        XCTAssertEqual(nap.plan.windowStart, now.addingTimeInterval(minutes(15)))
        XCTAssertEqual(nap.spec.sunrise, .off)
        XCTAssertEqual(nap.spec.phone, .vibrate)
        XCTAssertEqual(Transient.nap(base: nil, now: now, calendar: zurich, minutes: 15, uuid: "n2").plan.windowStart, now.addingTimeInterval(minutes(12)))
        XCTAssertEqual(Transient.nap(base: nil, now: now, calendar: zurich, minutes: 1, uuid: "n3").plan.target, now.addingTimeInterval(minutes(10)))
        XCTAssertEqual(Transient.nap(base: nil, now: now, calendar: zurich, minutes: 500, uuid: "n4").plan.target, now.addingTimeInterval(minutes(90)))
        XCTAssertEqual(Transient.napWindowMinutes(10), 3)
        XCTAssertEqual(Transient.napWindowMinutes(16), 5)
    }

    func testKindAndSnoozeByPrefix() {
        XCTAssertEqual(Transient.kindOf("test-1"), .test)
        XCTAssertEqual(Transient.kindOf("nap-1"), .nap)
        XCTAssertNil(Transient.kindOf("3f2a-77"))
        XCTAssertNil(Transient.kindOf(nil))
        XCTAssertFalse(Transient.allowsSnooze("test-1"))
        XCTAssertTrue(Transient.allowsSnooze("nap-1"))
        XCTAssertTrue(Transient.allowsSnooze("3f2a"))
        XCTAssertTrue(Transient.allowsSnooze(nil))
    }

    func testActiveUntilTwoHoursAfterTarget() {
        let t = Transient.test(base: base, now: now, calendar: zurich, uuid: "1")
        XCTAssertTrue(Transient.isActive(t, now: now))
        XCTAssertTrue(Transient.isActive(t, now: t.plan.target.addingTimeInterval(minutes(119))))
        XCTAssertFalse(Transient.isActive(t, now: t.plan.target.addingTimeInterval(minutes(120))))
    }

    func testEncodeAndDecode() throws {
        let t = Transient.test(base: base, now: now, calendar: zurich, uuid: "abc")
        let text = Transient.encode(t)
        let start = AlarmSchedule.millis(now.addingTimeInterval(60)), target = AlarmSchedule.millis(now.addingTimeInterval(120))
        XCTAssertTrue(text.hasPrefix("phasewake-transient 1\nTEST|\(start)|\(target)\nphasewake-alarms 1\n"))
        XCTAssertEqual(try Transient.decode(text), t)
        let nap = Transient.nap(base: nil, now: now, calendar: zurich, minutes: 30, uuid: "n")
        XCTAssertEqual(try Transient.decode(Transient.encode(nap)), nap)
    }

    func testDecodeRejectsForeignOrBrokenText() throws {
        let good = Transient.encode(Transient.test(base: base, now: now, calendar: zurich, uuid: "abc"))
        let other = try AlarmSpec(id: "test-1", hour: 7, minute: 0)
        for text in [
            "phasewake-alarms 1\n", good.replacingOccurrences(of: "TEST|", with: "LAMP|"), good.replacingOccurrences(of: "TEST|", with: "NAP|"),
            "phasewake-transient 1\nTEST|x|1\n", "phasewake-transient 1\nTEST|1|x\n", "phasewake-transient 1\nTEST|1|2\nphasewake-alarms 1\n",
            "phasewake-transient 1\nTEST|9|2\n" + Codec.encodeAlarms([other]),
        ] {
            XCTAssertThrowsError(try Transient.decode(text), text)
        }
    }

    func testTestRunRecordsFirstSeen() {
        let target = now.addingTimeInterval(120)
        var run = TestRun(id: "test-1", target: target, phoneAlert: .sound)
        run = TestRuns.record(run, .received, at: now)
        run = TestRuns.record(run, .received, at: now.addingTimeInterval(5))
        XCTAssertEqual(run.seen[.received], now)
        XCTAssertEqual(TestRuns.result(run, .received), true)
        XCTAssertEqual(TestRuns.result(run, .watchRang), false)
        XCTAssertFalse(TestRuns.isFinished(run, now: target))
        XCTAssertTrue(TestRuns.isFinished(run, now: target.addingTimeInterval(TestRuns.resultAfterTarget)))
        run = TestRuns.record(run, .stopArrived, at: target.addingTimeInterval(10))
        XCTAssertTrue(TestRuns.isFinished(run, now: target))
    }

    func testPhoneStepNotCheckedWhenPhoneAlarmOff() {
        let run = TestRun(id: "test-1", target: now, phoneAlert: .off)
        XCTAssertNil(TestRuns.result(run, .phoneRang))
        XCTAssertEqual(TestRuns.result(run, .stopArrived), false)
    }

    func testTestRunCodec() {
        let ms = AlarmSchedule.millis(now)
        let run = TestRun(id: "test-1", target: now, phoneAlert: .vibrate, seen: [.watchRang: now.addingTimeInterval(3), .received: now])
        let text = TestRuns.encode(run)
        XCTAssertEqual(text, "test-1|\(ms)|VIBRATE|RECEIVED=\(ms),WATCH_RANG=\(ms + 3000)")
        XCTAssertEqual(TestRuns.decode(text), run)
        XCTAssertEqual(TestRuns.decode("t|\(ms)|NEU|"), TestRun(id: "t", target: now, phoneAlert: .sound))
        XCTAssertEqual(TestRuns.decode("t|\(ms)|SOUND|LAMP=1,RECEIVED=x"), TestRun(id: "t", target: now, phoneAlert: .sound))
        XCTAssertNil(TestRuns.decode(nil))
        XCTAssertNil(TestRuns.decode("t|1"))
        XCTAssertNil(TestRuns.decode("t|x|SOUND|"))
    }
}
