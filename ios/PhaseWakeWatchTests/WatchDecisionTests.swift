import PhaseWakeCore
import XCTest
@testable import PhaseWakeWatch

/// Entscheidungen der Uhr ohne Sensoren (Audit F-03): Erinnerung am Abend ([EveningReminder]), Beginn der
/// Smart-Alarm-Sitzung und Kennung des Rückfalls ([SmartAlarm]), Einschlafzeit ohne Rekorder ([WatchOnset]).
final class WatchDecisionTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    @MainActor
    func testEveningReminderOnlyWhenSomethingIsToDo() {
        let later = now.addingTimeInterval(3600)
        // Sitzung noch nicht geplant: erinnern.
        XCTAssertTrue(EveningReminder.needed(at: later, now: now, goodNight: false, armed: false, recorder: false))
        // Schon scharf: nur, wenn der Rekorder (Einschlaf-Messung, Lernphase) die offene App braucht.
        XCTAssertFalse(EveningReminder.needed(at: later, now: now, goodNight: false, armed: true, recorder: false))
        XCTAssertTrue(EveningReminder.needed(at: later, now: now, goodNight: false, armed: true, recorder: true))
        // Nach «Gute Nacht», ab dem Zeitpunkt selbst und ohne Wecker nicht.
        XCTAssertFalse(EveningReminder.needed(at: later, now: now, goodNight: true, armed: false, recorder: true))
        XCTAssertFalse(EveningReminder.needed(at: now, now: now, goodNight: false, armed: false, recorder: true))
        XCTAssertFalse(EveningReminder.needed(at: nil, now: now, goodNight: false, armed: false, recorder: true))
    }

    @MainActor
    func testSessionStartsAtWindowButAtMost25MinutesBeforeTarget() {
        let target = now.addingTimeInterval(8 * 3600)
        let wide = WakePlan(alarmId: "a", windowStart: target.addingTimeInterval(-30 * 60), target: target)
        XCTAssertEqual(SmartAlarm.sessionStart(wide), target.addingTimeInterval(-SmartAlarm.sessionLead))
        let narrow = WakePlan(alarmId: "a", windowStart: target.addingTimeInterval(-10 * 60), target: target)
        XCTAssertEqual(SmartAlarm.sessionStart(narrow), narrow.windowStart)
    }

    @MainActor
    func testFallbackOfTransientPlansNeverReplacesTheRealOne() {
        XCTAssertEqual(SmartAlarm.fallbackKey(for: "a"), SmartAlarm.fallbackId)
        XCTAssertEqual(SmartAlarm.fallbackKey(for: nil), SmartAlarm.fallbackId)
        XCTAssertEqual(SmartAlarm.fallbackKey(for: Transient.testPrefix + "1"), SmartAlarm.transientFallbackId)
        XCTAssertEqual(SmartAlarm.fallbackKey(for: Transient.napPrefix + "1"), SmartAlarm.transientFallbackId)
    }

    @MainActor
    func testSameTimeToTheMillisecond() {
        XCTAssertTrue(SmartAlarm.sameTime(now, now.addingTimeInterval(0.0004)))
        XCTAssertFalse(SmartAlarm.sameTime(now, now.addingTimeInterval(0.002)))
    }

    @MainActor
    func testPhaseFlags() {
        XCTAssertTrue(SmartAlarm.Phase.ringing(.deadline, snoozesLeft: 3).isRinging)
        XCTAssertTrue(SmartAlarm.Phase.snoozed(until: now, snoozesLeft: 2).isSnoozed)
        let plan = WakePlan(alarmId: "a", windowStart: now, target: now.addingTimeInterval(1800))
        XCTAssertTrue(SmartAlarm.Phase.measuring(plan).isMeasuring)
        XCTAssertFalse(SmartAlarm.Phase.scheduled(plan).isMeasuring || SmartAlarm.Phase.idle.isRinging || SmartAlarm.Phase.waiting(plan).isSnoozed)
    }

    @MainActor
    func testOnsetNotMeasuredWithoutAlarmOrForTransientPlans() async throws {
        let target = now.addingTimeInterval(8 * 3600)
        let plan = WakePlan(alarmId: "a", windowStart: target.addingTimeInterval(-1800), target: target)
        // Wecker inzwischen gelöscht: unbekannt, keine Untergrenze kurzer Nächte.
        let withoutSpec = await WatchOnset.compute(plan: plan, spec: nil, config: .standard, goodNight: nil, sleepGoal: 450)
        XCTAssertEqual(withoutSpec.result, .notMeasured)
        XCTAssertNil(withoutSpec.floor)
        // Probewecker und Powernap: nie gemessen.
        let nap = WakePlan(alarmId: Transient.napPrefix + "1", windowStart: plan.windowStart, target: target)
        let spec = try AlarmSpec(id: nap.alarmId, hour: 7, minute: 0)
        let napResult = await WatchOnset.compute(plan: nap, spec: spec, config: .standard, goodNight: nil, sleepGoal: 450)
        XCTAssertEqual(napResult.result, .notMeasured)
        XCTAssertNil(napResult.floor)
    }

    /// Audit UI-9, Nachtrag: Räder im Editor der Uhr mit den Ziffern der Sprache (Arabisch ٠–٩, Deutsch 0–9).
    func testTwoDigitsUseDigitsOfLanguage() {
        XCTAssertEqual(Format.twoDigits(7, locale: Locale(identifier: "de_CH")), "07")
        XCTAssertEqual(Format.twoDigits(30, locale: Locale(identifier: "de_CH")), "30")
        XCTAssertEqual(Format.twoDigits(7, locale: Locale(identifier: "ar_EG")), "٠٧")
    }

    /// Diagnose-Protokoll: Uhrzeit immer «HH:mm» mit 0–9, auch auf Arabisch (wie Android).
    func testLogTimeIsLatin() {
        let zone = TimeZone(identifier: "Europe/Zurich")!
        let date = Date(timeIntervalSince1970: 1_794_382_800) // 11.11.2026 08:40 Ortszeit
        XCTAssertEqual(Format.logTime(date, zone: zone), "08:40")
    }
}
