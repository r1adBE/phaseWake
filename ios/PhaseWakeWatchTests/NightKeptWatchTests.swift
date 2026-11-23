import PhaseWakeCore
import XCTest
@testable import PhaseWakeWatch

/// Zeitzone der Nacht, «stop» vom iPhone ohne Klingeln und gesicherter Klingel-Zustand, Apple Watch
/// (``NightRules``): gemerkter Plan, Nacht über eine Reise, Ende per iPhone, Zustand nach einem Neustart der App.
final class NightKeptWatchTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)
    private let zurich = TimeZone(identifier: "Europe/Zurich")!
    private let newYork = TimeZone(identifier: "America/New_York")!

    private func plan(_ id: String = "d", targetIn seconds: TimeInterval) -> WakePlan {
        WakePlan(alarmId: id, windowStart: now.addingTimeInterval(seconds - 1800),
                 target: now.addingTimeInterval(seconds))
    }

    private func saved(_ night: WakePlan, zone: String? = "Europe/Zurich") -> [String: Any] {
        var entry: [String: Any] = ["target": night.target.timeIntervalSince1970, "alarm": night.alarmId]
        if let zone { entry["zone"] = zone }
        return entry
    }

    @MainActor
    func testSavedPlanZoneReadsWhatIsReadable() throws {
        XCTAssertNil(NightRules.planZone(nil))
        let night = plan(targetIn: 600)
        let full = try XCTUnwrap(NightRules.planZone(saved(night)))
        XCTAssertEqual(full.zone, "Europe/Zurich")
        XCTAssertEqual(full.target, night.target)
        XCTAssertEqual(full.alarmId, "d")
        // Falsche Typen und ältere Einträge ohne Wecker: nur die lesbaren Felder.
        let broken = try XCTUnwrap(NightRules.planZone(["zone": 3, "target": "x"] as [String: Any]))
        XCTAssertNil(broken.zone)
        XCTAssertNil(broken.target)
        XCTAssertNil(broken.alarmId)
    }

    /// Der gemerkte Plan bleibt für dieselbe Nacht eines echten Weckers (er nennt die Zone der Berechnung, auch nach
    /// einer Reise); für Probewecker und Powernap wird er nicht gelesen, sondern neu gemerkt.
    @MainActor
    func testPlanZoneKeptForTheSameRealNight() {
        let night = plan(targetIn: 600)
        let entry = NightRules.planZone(saved(night))
        XCTAssertTrue(NightRules.keepsPlanZone(entry, plan: night))
        let rounded = WakePlan(alarmId: "d", windowStart: night.windowStart,
                               target: night.target.addingTimeInterval(0.0004))
        XCTAssertTrue(NightRules.keepsPlanZone(entry, plan: rounded))
        XCTAssertFalse(NightRules.keepsPlanZone(entry, plan: plan(targetIn: 660)))
        XCTAssertFalse(NightRules.keepsPlanZone(entry, plan: plan("x", targetIn: 600)))
        XCTAssertFalse(NightRules.keepsPlanZone(nil, plan: night))
        XCTAssertFalse(NightRules.keepsPlanZone(NightRules.planZone(["alarm": "d"]), plan: night))
        var read = false
        func probeEntry() -> SmartAlarm.PlanZone? {
            read = true
            return entry
        }
        XCTAssertFalse(NightRules.keepsPlanZone(probeEntry(), plan: plan(Transient.testPrefix + "1", targetIn: 120)))
        XCTAssertFalse(read)
    }

    /// Reise Zürich → New York: Die in Zürich geplante Nacht kann bleiben. Gleiche Zone, gleicher Versatz, unlesbare
    /// Zone, älterer Eintrag ohne Wecker, Probewecker oder eine andere Nacht: nicht.
    @MainActor
    func testNightPlannedInOtherZoneCanBeKept() throws {
        let night = plan(targetIn: 600)
        let entry = NightRules.planZone(saved(night))
        let kept = try XCTUnwrap(NightRules.plannedNight(entry, night: night, current: newYork))
        XCTAssertEqual(kept, NightRules.PlannedNight(alarmId: "d", target: night.target, zone: zurich))
        // Ohne Angabe der Nacht (nach einem Neustart der App): die gemerkte.
        XCTAssertNotNil(NightRules.plannedNight(entry, night: nil, current: newYork))
        XCTAssertNil(NightRules.plannedNight(entry, night: night, current: zurich))
        XCTAssertNil(NightRules.plannedNight(entry, night: night, current: TimeZone(identifier: "Europe/Berlin")!))
        XCTAssertNil(NightRules.plannedNight(entry, night: plan(targetIn: 86400), current: newYork))
        XCTAssertNil(NightRules.plannedNight(entry, night: plan("x", targetIn: 600), current: newYork))
        XCTAssertNil(NightRules.plannedNight(nil, night: night, current: newYork))
        XCTAssertNil(NightRules.plannedNight(NightRules.planZone(saved(night, zone: "Mars/Olympus")), night: night,
                                             current: newYork))
        XCTAssertNil(NightRules.plannedNight(NightRules.planZone(saved(night, zone: nil)), night: night,
                                             current: newYork))
        var legacy = saved(night)
        legacy["alarm"] = nil
        XCTAssertNil(NightRules.plannedNight(NightRules.planZone(legacy), night: night, current: newYork))
        let nap = plan(Transient.napPrefix + "1", targetIn: 600)
        XCTAssertNil(NightRules.plannedNight(NightRules.planZone(saved(nap)), night: nap, current: newYork))
    }

    /// Geplante oder wartende andere Nacht bleibt nach einer Reise statt des neuen Plans; sonst nicht (dann wird nicht
    /// nach der Reise gefragt, ausser es kommt darauf an).
    @MainActor
    func testHeldNightOnlyForOtherScheduledOrWaitingNight() {
        let night = plan(targetIn: 600)
        let other = plan(targetIn: 4200)
        var asked = 0
        func kept(_ value: Bool) -> (WakePlan) -> Bool {
            { _ in asked += 1; return value }
        }
        XCTAssertEqual(NightRules.heldNight(.scheduled(night), plan: other, kept: kept(true)), night)
        XCTAssertEqual(NightRules.heldNight(.waiting(night), plan: nil, kept: kept(true)), night)
        XCTAssertNil(NightRules.heldNight(.waiting(night), plan: other, kept: kept(false)))
        XCTAssertEqual(asked, 3)
        XCTAssertNil(NightRules.heldNight(.scheduled(night), plan: night, kept: kept(true)))
        XCTAssertNil(NightRules.heldNight(.measuring(night), plan: other, kept: kept(true)))
        XCTAssertNil(NightRules.heldNight(.idle, plan: other, kept: kept(true)))
        XCTAssertEqual(asked, 3)
    }

    /// «stop» vom iPhone, während die Uhr nicht klingelt: Kandidat ist die wartende, geplante oder messende Nacht;
    /// misst sie, endet sie mit ihren Epochen; sonst ist ein echter Wecker erledigt, ein vorübergehender Plan endet.
    @MainActor
    func testPhoneStopWithoutRinging() {
        let night = plan(targetIn: 600)
        let nap = plan(Transient.napPrefix + "1", targetIn: 600)
        XCTAssertEqual(NightRules.phoneStopCandidate(.waiting(night)), night)
        XCTAssertEqual(NightRules.phoneStopCandidate(.scheduled(night)), night)
        XCTAssertEqual(NightRules.phoneStopCandidate(.measuring(night)), night)
        XCTAssertNil(NightRules.phoneStopCandidate(.idle))
        XCTAssertNil(NightRules.phoneStopCandidate(.ringing(.deadline, snoozesLeft: 3)))
        XCTAssertNil(NightRules.phoneStopCandidate(.snoozed(until: now, snoozesLeft: 2)))
        XCTAssertEqual(NightRules.phoneStopEnd(.measuring(night), night: night), .finishMeasuring)
        XCTAssertEqual(NightRules.phoneStopEnd(.measuring(nap), night: nap), .finishMeasuring)
        // Gestoppt wurde die ausstehende Nacht, gemessen wird eine andere.
        XCTAssertEqual(NightRules.phoneStopEnd(.measuring(plan(targetIn: 4200)), night: night), .markDone)
        XCTAssertEqual(NightRules.phoneStopEnd(.waiting(night), night: night), .markDone)
        XCTAssertEqual(NightRules.phoneStopEnd(.scheduled(nap), night: nap), .endTransient)
        XCTAssertEqual(NightRules.phoneStopEnd(.idle, night: nap), .endTransient)
    }

    /// Klingel-Zustand übersteht einen Neustart der App: Nacht und Schlummer-Ende; unlesbare Schlummer-Zeit gilt als
    /// fehlend (dann wie beim Klingeln), unlesbare Nacht als kein Zustand.
    func testRingStateRoundTripAndBrokenValues() throws {
        let night = NightRecord(alarmId: "d", windowStart: now.addingTimeInterval(-1800), target: now,
                                wokeAt: now.addingTimeInterval(-300), reason: .lightSleep, snoozes: 1,
                                epochs: [Epoch(motion: 0.5, heartRate: 60), Epoch(motion: nil, heartRate: nil)])
        let until = now.addingTimeInterval(300)
        let snoozed = try XCTUnwrap(NightRules.ringNight(NightRules.ringState(night, snoozedUntil: until)))
        XCTAssertEqual(snoozed.night.alarmId, "d")
        XCTAssertEqual(snoozed.night.target, now)
        XCTAssertEqual(snoozed.night.wokeAt, night.wokeAt)
        XCTAssertEqual(snoozed.night.reason, .lightSleep)
        XCTAssertEqual(snoozed.night.snoozes, 1)
        XCTAssertEqual(snoozed.night.epochs.count, 2)
        XCTAssertEqual(snoozed.snoozedUntil, until)
        let ringing = try XCTUnwrap(NightRules.ringNight(NightRules.ringState(night, snoozedUntil: nil)))
        XCTAssertNil(ringing.snoozedUntil)
        var state = NightRules.ringState(night, snoozedUntil: nil)
        for broken: Any in [Double.nan, Double.infinity, "später"] {
            state["snoozedUntil"] = broken
            XCTAssertNil(try XCTUnwrap(NightRules.ringNight(state)).snoozedUntil)
        }
        XCTAssertNil(NightRules.ringNight(nil))
        XCTAssertNil(NightRules.ringNight(["snoozedUntil": 1.0]))
        XCTAssertNil(NightRules.ringNight(["night": "kaputt"]))
        XCTAssertNil(NightRules.ringNight(["night": 42]))
    }
}
