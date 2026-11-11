import XCTest
import PhaseWakeCore

/// Apple Watch im Gleichstand mit der Galaxy Watch: Einschlafzeit aus dem Rekorder (``RecorderOnset``, Regeln wie
/// `OnsetMonitor`/`OnsetRules` in Kotlin), Schätzung bei «Gute Nacht» (``GoodNightEstimate``), frühere Zielzeit fürs
/// iPhone (``OnsetShift``, wie Kotlin) und «Nacht aufzeichnen» (``NightRecording``: Ende, CSV wie Kotlin).
final class AppleOnsetTests: XCTestCase {
    private let since = Date(timeIntervalSince1970: 1_793_570_400) // 2026-11-01 23:00 Zürich
    private let quiet = 0.0005
    private let busy = 0.5

    private func minutes(_ m: Double) -> Date { since.addingTimeInterval(m * 60) }

    private func find(_ motions: [Double?], latestHours: Double = 10, cap: Date? = nil, personal: Double? = nil) -> RecorderOnset.Result {
        RecorderOnset.find(motions: motions, since: since, threshold: DetectorConfig.defaultLightThreshold, personalFloor: personal,
                           latest: since.addingTimeInterval(latestHours * 3600), extendCap: cap)
    }

    func testQuietFromTheStartIsAsleepAtTheStart() {
        XCTAssertEqual(find(Array(repeating: quiet, count: 40)), .asleep(at: since, index: 0))
        // 29 ruhige Epochen reichen nicht: noch offen.
        XCTAssertEqual(find(Array(repeating: quiet, count: 29)), .open)
    }

    func testRestlessStartThenQuiet() {
        let motions: [Double?] = Array(repeating: busy, count: 20) + Array(repeating: quiet, count: 30)
        XCTAssertEqual(find(motions), .asleep(at: minutes(10), index: 20))
    }

    func testEmptyEpochsBreakTheRun() {
        var motions: [Double?] = Array(repeating: quiet, count: 20) + [nil] + Array(repeating: quiet, count: 29)
        XCTAssertEqual(find(motions), .open)
        motions.append(quiet)
        XCTAssertEqual(find(motions), .asleep(at: since.addingTimeInterval(21 * 30), index: 21))
    }

    func testNoiseFloorRaisesTheThreshold() {
        // Gleichmässig 0.03 m/s² (Rauschen wie die Galaxy Watch 07.11.): über 0.002, aber unter 2.5 × Grundrauschen.
        XCTAssertEqual(find(Array(repeating: 0.03, count: 30)), .asleep(at: since, index: 0))
        // Mit eigenem Grundrauschen gilt es schon vor der vierten Epoche (gleiches Ergebnis).
        XCTAssertEqual(find(Array(repeating: 0.03, count: 30), personal: 0.03), .asleep(at: since, index: 0))
    }

    func testGivesUpAfterTwoHoursWithoutExtension() {
        let motions: [Double?] = Array(repeating: nil, count: 300)
        XCTAssertEqual(find(motions), .gaveUp(at: minutes(120), reason: "give up (max duration)"))
        // Unruhig, aber ohne Grenze für Verlängerungen: ebenso nach 2 Std.
        XCTAssertEqual(find(Array(repeating: busy, count: 300)), .gaveUp(at: minutes(120), reason: "give up (max duration)"))
    }

    func testGivesUpFifteenMinutesBeforeTheAlarm() {
        XCTAssertEqual(find(Array(repeating: nil, count: 200), latestHours: 1), .gaveUp(at: minutes(45), reason: "give up"))
    }

    func testRestlessExtendsUpToTheCap() {
        let cap = RecorderOnset.extendCap(since: since, windowStart: since.addingTimeInterval(9 * 3600))
        XCTAssertEqual(cap, minutes(240))
        // Durchgehend unruhig: verlängert in Schritten von 15 Min bis 4 Std.
        XCTAssertEqual(find(Array(repeating: busy, count: 600), cap: cap), .gaveUp(at: minutes(240), reason: "give up (max duration)"))
        // Unruhig bis 2:10, dann ruhig: Die Verlängerung findet das späte Einschlafen.
        let late: [Double?] = Array(repeating: busy, count: 260) + Array(repeating: quiet, count: 40)
        XCTAssertEqual(find(late, cap: cap), .asleep(at: minutes(130), index: 260))
        // Ohne Unruhe in den letzten 15 Min keine Verlängerung.
        let calm: [Double?] = Array(repeating: busy, count: 200) + Array(repeating: nil, count: 100)
        XCTAssertEqual(find(calm, cap: cap), .gaveUp(at: minutes(120), reason: "give up (max duration)"))
        // Der Beginn des Weckfensters begrenzt die Verlängerung.
        XCTAssertEqual(RecorderOnset.extendCap(since: since, windowStart: minutes(150)), minutes(150))
    }

    /// Fix KN-3: Grundrauschen der Einschlaf-Messung nur aus den Epochen bis zum erkannten Einschlafen bzw. bis zum
    /// Aufgeben (wie `OnsetMonitor`); lief die Messung ins Weckfenster, keines.
    func testOnsetFloorUpToDetectionOnly() throws {
        func measure(_ motions: [Double?]) -> (result: RecorderOnset.Result, floor: Double?) {
            RecorderOnset.measure(motions: motions, since: since, threshold: DetectorConfig.defaultLightThreshold, personalFloor: nil,
                                  latest: since.addingTimeInterval(10 * 3600), extendCap: nil)
        }
        // Erkannt in Epoche 29 (30 × 0.01); die sehr ruhigen Epochen danach zählen nicht (über alle: 0.001).
        let asleep = measure(Array(repeating: 0.01, count: 30) + Array(repeating: 0.001, count: 30))
        XCTAssertEqual(asleep.result, .asleep(at: since, index: 0))
        XCTAssertEqual(try XCTUnwrap(asleep.floor), 0.01, accuracy: 1e-12)
        // Aufgegeben nach 2 Std. (240 unruhige Epochen): 0.04 (Obergrenze); die ruhigen danach zählen nicht.
        let gaveUp = measure(Array(repeating: busy, count: 300) + Array(repeating: quiet, count: 60))
        XCTAssertEqual(gaveUp.result, .gaveUp(at: minutes(120), reason: "give up (max duration)"))
        XCTAssertEqual(try XCTUnwrap(gaveUp.floor), NoiseFloor.maxFloor, accuracy: 1e-12)
        // Bis ins Weckfenster ohne Ergebnis: keines (früher das aller Epochen).
        let open = measure(Array(repeating: quiet, count: 29))
        XCTAssertEqual(open.result, .open)
        XCTAssertNil(open.floor)
        XCTAssertEqual(RecorderOnset.find(motions: Array(repeating: quiet, count: 29), since: since,
                                          threshold: DetectorConfig.defaultLightThreshold, personalFloor: nil,
                                          latest: since.addingTimeInterval(10 * 3600), extendCap: nil), .open)
    }

    func testRulesMatchKotlin() {
        XCTAssertTrue(RecorderOnset.restless([nil, 0.2], threshold: 0.1))
        XCTAssertFalse(RecorderOnset.restless([0.2] + Array(repeating: nil, count: 30), threshold: 0.1))
        XCTAssertNil(RecorderOnset.extendUntil(at: minutes(100), maxEnd: minutes(120), latestGiveUp: minutes(400), cap: minutes(240), restless: true))
        XCTAssertNil(RecorderOnset.extendUntil(at: minutes(120), maxEnd: minutes(120), latestGiveUp: minutes(400), cap: minutes(240), restless: false))
        XCTAssertEqual(RecorderOnset.extendUntil(at: minutes(120), maxEnd: minutes(120), latestGiveUp: minutes(125), cap: minutes(240), restless: true),
                       minutes(125))
        XCTAssertNil(RecorderOnset.giveUpReason(at: minutes(60), latestGiveUp: minutes(400), maxEnd: minutes(120)))
        XCTAssertEqual(RecorderOnset.bedAt(since: minutes(0), automatic: true, latest: minutes(540), sleepMinutes: 450), minutes(90))
        XCTAssertEqual(RecorderOnset.bedAt(since: minutes(120), automatic: true, latest: minutes(540), sleepMinutes: 450), minutes(120))
        XCTAssertEqual(RecorderOnset.bedAt(since: minutes(5), automatic: false, latest: minutes(540), sleepMinutes: 450), minutes(5))
    }

    // MARK: «Gute Nacht»

    func testGoodNightEstimate() {
        let latest = minutes(8 * 60) // 07:00
        // 15 Min Standard + 7½ Std.: 06:45.
        XCTAssertEqual(GoodNightEstimate.target(tap: since, latencyMinutes: nil, sleepDurationMinutes: 450, latest: latest), minutes(465))
        // Gelernt 20 Min: 06:50; nie nach der eingestellten Weckzeit.
        XCTAssertEqual(GoodNightEstimate.target(tap: since, latencyMinutes: 20, sleepDurationMinutes: 450, latest: latest), minutes(470))
        XCTAssertEqual(GoodNightEstimate.target(tap: since, latencyMinutes: 45, sleepDurationMinutes: 450, latest: latest), latest)
        XCTAssertFalse(GoodNightEstimate.tooFar(now: since, latest: latest))
        XCTAssertTrue(GoodNightEstimate.tooFar(now: since, latest: minutes(17 * 60)))
        XCTAssertTrue(GoodNightEstimate.tooFar(now: since, latest: nil))
        XCTAssertEqual(GoodNightEstimate.doneUntil(target: minutes(465), nominal: latest), latest)
        XCTAssertEqual(GoodNightEstimate.doneUntil(target: minutes(465), nominal: nil), minutes(465))
        XCTAssertEqual(GoodNightEstimate.doneUntil(target: latest, nominal: minutes(8 * 60 + 24 * 60)), latest)
    }

    func testAutoOnsetStartsTheRecorderOneHourBeforeBedtime() {
        let target = minutes(8 * 60) // 07:00
        let window = minutes(8 * 60 - 30)
        let onset = minutes(-30) // 22:30 (Bettzeit 23:30 minus 60 Min)
        // Vor dem Beginn der Einschlaf-Messung: warten (kein Start mehr bis 12 Std. im Voraus, Entscheid r1AD 10.11.2026).
        XCTAssertEqual(AppleAutoOnset.decide(now: minutes(-6 * 60), target: target, windowStart: window, onsetStart: onset, battery: 80),
                       .wait(until: onset))
        XCTAssertEqual(AppleAutoOnset.decide(now: minutes(-31), target: target, windowStart: window, onsetStart: onset, battery: 80),
                       .wait(until: onset))
        XCTAssertEqual(AppleAutoOnset.decide(now: onset, target: target, windowStart: window, onsetStart: onset, battery: 80), .start(until: window))
        XCTAssertEqual(AppleAutoOnset.decide(now: since, target: target, windowStart: window, onsetStart: onset, battery: 80), .start(until: window))
        XCTAssertEqual(AppleAutoOnset.decide(now: since, target: target, windowStart: window, onsetStart: onset, battery: nil), .start(until: window))
        XCTAssertEqual(AppleAutoOnset.decide(now: since, target: target, windowStart: window, onsetStart: onset, battery: 29), .skip(reason: "battery 29%"))
        XCTAssertEqual(AppleAutoOnset.decide(now: since, target: target, windowStart: window, onsetStart: onset, battery: 30), .start(until: window))
        XCTAssertEqual(AppleAutoOnset.decide(now: window, target: target, windowStart: window, onsetStart: onset, battery: 80), .skip(reason: "window begun"))
        // Fenster 10 Min: ab 15 Min vor der Zielzeit nicht mehr.
        XCTAssertEqual(AppleAutoOnset.decide(now: minutes(8 * 60 - 15), target: target, windowStart: minutes(8 * 60 - 10), onsetStart: onset, battery: 80),
                       .skip(reason: "window begun"))
    }

    func testOneRecorderCallPerNight() {
        let now = minutes(-30) // 22:30
        let window = minutes(8 * 60 - 30) // 06:30
        let afterTarget = minutes(8 * 60).addingTimeInterval(AppleLearnRecord.afterTarget) // 07:30
        // Erster Start: das späteste Bedürfnis der Nacht (Lernphase bis nach der Zielzeit), nicht nur die Einschlaf-Messung.
        XCTAssertEqual(AppleNightRecorder.decide(now: now, need: window, others: [afterTarget], runningUntil: nil), .start(until: afterTarget))
        XCTAssertEqual(AppleNightRecorder.decide(now: now, need: window, runningUntil: nil), .start(until: window))
        // Höchstens 12 Std. ab jetzt («Nacht aufzeichnen»).
        let twelve = now.addingTimeInterval(NightRecording.maxDuration)
        XCTAssertEqual(AppleNightRecorder.decide(now: now, need: twelve.addingTimeInterval(3600), runningUntil: nil), .start(until: twelve))
        // Ein abgelaufener Rekorder zählt nicht.
        XCTAssertEqual(AppleNightRecorder.decide(now: now, need: window, runningUntil: now.addingTimeInterval(-1)), .start(until: window))
        // Läuft er schon und reicht: kein zweiter Aufruf.
        XCTAssertEqual(AppleNightRecorder.decide(now: since, need: window, runningUntil: afterTarget), .running)
        // Läuft er, reicht aber nicht (z. B. «Gute Nacht» nach der Einschlaf-Messung): hinnehmen, nie verlängern.
        XCTAssertEqual(AppleNightRecorder.decide(now: since, need: afterTarget, runningUntil: window),
                       .short(until: window, need: afterTarget))
        // Ein Bedarf über 12 Std. hinaus gilt als gedeckt, wenn der Rekorder 12 Std. ab jetzt reicht.
        XCTAssertEqual(AppleNightRecorder.decide(now: now, need: twelve.addingTimeInterval(60), runningUntil: twelve), .running)
        // Kein Bedarf mehr.
        XCTAssertEqual(AppleNightRecorder.decide(now: now, need: now, runningUntil: nil), .nothing)
    }

    func testLearnedLatencyGoesToTheWatch() {
        let snapshot = LearningSnapshot(active: false, floor: 0.02, restingHeartRate: 50, updatedAtMs: 7, latencyMinutes: 18)
        var context: [String: Any] = [:]
        snapshot.apply(to: &context)
        XCTAssertEqual(LearningSnapshot(context: context), snapshot)
        // Ältere Stände ohne Einschlafdauer bleiben lesbar.
        let old = try? JSONDecoder().decode(LearningSnapshot.self, from: Data(#"{"active":true,"updatedAtMs":3}"#.utf8))
        XCTAssertEqual(old, LearningSnapshot(active: true, floor: nil, restingHeartRate: nil, updatedAtMs: 3))
    }

    func testOnsetShiftMovesOnlyThatNight() throws {
        let plan = WakePlan(alarmId: "a|1", windowStart: minutes(450), target: minutes(480))
        let shift = OnsetShift(alarmId: "a|1", original: OnsetShift.millis(minutes(480)), target: OnsetShift.millis(minutes(465)),
                               at: OnsetShift.millis(since))
        XCTAssertEqual(OnsetShift.decode(shift.encode()), shift)
        XCTAssertNil(OnsetShift.decode("x|1|2"))
        let moved = shift.applyTo(plan, now: since, grace: 120)
        XCTAssertEqual(moved.target, minutes(465))
        XCTAssertEqual(moved.windowStart, minutes(450))
        // Anderer Wecker, andere Nacht oder vorbei: unverändert.
        XCTAssertEqual(shift.applyTo(WakePlan(alarmId: "b", windowStart: minutes(450), target: minutes(480)), now: since, grace: 120).target, minutes(480))
        XCTAssertEqual(shift.applyTo(WakePlan(alarmId: "a|1", windowStart: minutes(1890), target: minutes(1920)), now: since, grace: 120).target,
                       minutes(1920))
        XCTAssertEqual(shift.applyTo(plan, now: minutes(470), grace: 120).target, minutes(480))
        // Aufgehoben: nicht früher, gilt also die eingestellte Zeit.
        XCTAssertEqual(OnsetShift.cancel(plan, at: 1).applyTo(plan, now: since, grace: 120), plan)
        XCTAssertFalse(shift.expired(now: minutes(481), grace: 120))
        XCTAssertTrue(shift.expired(now: minutes(482), grace: 120))
    }

    // MARK: «Nacht aufzeichnen»

    func testRecordingEndsAtCapAndLowBattery() {
        XCTAssertNil(NightRecording.endNow(start: since, now: minutes(60), battery: nil))
        XCTAssertNil(NightRecording.endNow(start: since, now: minutes(60), battery: 15))
        XCTAssertEqual(NightRecording.endNow(start: since, now: minutes(60), battery: 14), .battery)
        XCTAssertEqual(NightRecording.endNow(start: since, now: minutes(720), battery: 80), .cap)
    }

    func testRecordingCsvLikeKotlin() {
        let first = NightRecording(start: since, epochs: [Epoch(motion: 0.0123456789, heartRate: 52.34), Epoch(motion: nil, heartRate: nil)],
                                   end: minutes(1), endReason: .watch)
        let running = NightRecording(start: minutes(-60), epochs: [Epoch(motion: 0.5, heartRate: nil)])
        let older = NightRecording(start: minutes(-1440), epochs: [Epoch(motion: nil, heartRate: 60)], end: minutes(-1400), endReason: .wake)
        XCTAssertEqual(NightRecording.exportCsv([first, running, older]),
                       "recording,end_reason,epoch,motion,hr\n1,wake,0,,60.0\n2,watch,0,0.012346,52.3\n2,watch,1,,\n")
        XCTAssertEqual(first.duration, 60)
        XCTAssertTrue(first.finished)
        XCTAssertEqual(running.duration, 30)
        XCTAssertEqual(running.until, minutes(-59.5))
        XCTAssertEqual(first.until, minutes(1))
        XCTAssertFalse(running.finished)
    }

    func testAutomaticOnsetNeverBeforeBedtime() {
        // Vollaudit K-4 (wie `OnsetRules.onsetAt`, Kotlin): Weckzeit 07:30, Schlaf 8 Std. → Bettzeit 23:30, Messung ab
        // 22:30. Still ab 22:30: eingeschlafen frühestens 23:30.
        let start = since.addingTimeInterval(-30 * 60)
        let latest = since.addingTimeInterval(8.5 * 3600)
        let bed = RecorderOnset.bedAt(since: start, automatic: true, latest: latest, sleepMinutes: 480)
        XCTAssertEqual(bed, since.addingTimeInterval(30 * 60))
        XCTAssertEqual(RecorderOnset.onsetAt(found: start, bedAt: bed), bed)
        XCTAssertEqual(RecorderOnset.onsetAt(found: start.addingTimeInterval(15 * 60), bedAt: bed), bed)
        let later = since.addingTimeInterval(50 * 60)
        XCTAssertEqual(RecorderOnset.onsetAt(found: later, bedAt: bed), later)
        // «Gute Nacht»: Beginn der Einschlafdauer ist das Drücken, die erkannte Zeit gilt unverändert.
        let tap = RecorderOnset.bedAt(since: start, automatic: false, latest: latest, sleepMinutes: 480)
        XCTAssertEqual(RecorderOnset.onsetAt(found: start.addingTimeInterval(10 * 60), bedAt: tap), start.addingTimeInterval(10 * 60))
    }
}
