import XCTest
import PhaseWakeCore

/// Lernphase auf der Apple Watch: Epochen aus dem Bewegungs-Rekorder (``RecorderEpochs``), Format der Aufzeichnung
/// (``NightRecording``), Start des Rekorders (``AppleLearnRecord``) und Stand am iPhone (``LearningState``).
final class AppleLearningTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_793_570_400) // 2026-11-01 23:00 Zürich

    /// 50 Hz wie der Rekorder: Wert `i` bei `start + offset + i · 20 ms`; die übernommenen Werte (alle 40 ms)
    /// wechseln zwischen 0 und `g` (in g) auf der x-Achse.
    private func feed(_ epochs: RecorderEpochs, from offset: TimeInterval, seconds: Int, g: Double) {
        for i in 0..<(seconds * 50) {
            let x = (i / 2) % 2 == 0 ? 0 : g
            epochs.add(at: start.addingTimeInterval(offset + Double(i) * 0.02), x: x, y: 0, z: 0)
        }
    }

    func testRecorderEpochUsesTheSameMotionRule() throws {
        let epochs = RecorderEpochs(start: start)
        feed(epochs, from: 0, seconds: 30, g: 0.01)
        // Die Werte alle 20 ms: Takt 35 ms übernimmt jeden zweiten (749 Differenzen von 0.0980665 m/s²), minus 0.05.
        epochs.close(until: start.addingTimeInterval(30))
        XCTAssertEqual(epochs.motions.count, 1)
        XCTAssertEqual(try XCTUnwrap(epochs.motions[0]), 0.01 * RecorderEpochs.gravity - 0.05, accuracy: 1e-9)
        XCTAssertEqual(epochs.closedUntil, start.addingTimeInterval(30))
    }

    func testGapsAndShortEpochsAreEmpty() {
        let epochs = RecorderEpochs(start: start)
        epochs.add(at: start.addingTimeInterval(-1), x: 1, y: 0, z: 0) // vor dem Beginn
        feed(epochs, from: 0, seconds: 10, g: 0.01) // 249 Differenzen: zu wenig
        feed(epochs, from: 95, seconds: 1, g: 0.01) // Epoche 3; Epochen 1 und 2 ohne Werte
        XCTAssertEqual(epochs.motions.count, 3)
        XCTAssertEqual(epochs.motions, [nil, nil, nil])
        // Ein Wert für eine abgeschlossene Epoche wird verworfen.
        epochs.add(at: start.addingTimeInterval(45), x: 5, y: 5, z: 5)
        XCTAssertEqual(epochs.motions.count, 3)
        // Nur ganz vergangene Epochen schliessen; vor dem Beginn nichts.
        epochs.close(until: start.addingTimeInterval(119))
        XCTAssertEqual(epochs.motions.count, 3)
        epochs.close(until: start.addingTimeInterval(120))
        XCTAssertEqual(epochs.motions, [nil, nil, nil, nil])
        epochs.close(until: start.addingTimeInterval(-5))
        XCTAssertEqual(epochs.motions.count, 4)
    }

    func testContinuesAfterSavedEpochsAndStopsAtTwelveHours() throws {
        let epochs = RecorderEpochs(start: start, motions: [0.03])
        feed(epochs, from: 10, seconds: 5, g: 0.5) // in der schon abgeschlossenen Epoche 0
        XCTAssertEqual(epochs.motions, [0.03])
        epochs.resetAccumulator()
        feed(epochs, from: 30, seconds: 30, g: 0.02)
        epochs.close(until: start.addingTimeInterval(60))
        XCTAssertEqual(try XCTUnwrap(epochs.motions[1]), 0.02 * RecorderEpochs.gravity - 0.05, accuracy: 1e-9)
        // Nach 12 Std. nimmt sie nichts mehr auf.
        let full = RecorderEpochs(start: start)
        full.close(until: start.addingTimeInterval(13 * 3600))
        XCTAssertEqual(full.motions.count, NightRecording.maxEpochs)
        full.add(at: start.addingTimeInterval(12 * 3600 + 1), x: 1, y: 1, z: 1)
        XCTAssertEqual(full.motions.count, NightRecording.maxEpochs)
    }

    func testHeartRatePerEpoch() {
        let samples: [(at: Date, bpm: Double)] = [
            (start.addingTimeInterval(5), 50), (start.addingTimeInterval(25), 54), // Epoche 0: Mittel 52
            (start.addingTimeInterval(65), 300), // unplausibel
            (start.addingTimeInterval(-5), 60), (start.addingTimeInterval(95), 60), // ausserhalb
        ]
        XCTAssertEqual(RecorderEpochs.heartRates(samples, start: start, count: 3), [52, nil, nil])
        XCTAssertEqual(RecorderEpochs.heartRates(samples, start: start, count: 0), [])
        let epochs = RecorderEpochs.epochs(motions: [0.02, nil, 0.03], heartRates: [52, 55])
        XCTAssertEqual(epochs, [Epoch(motion: 0.02, heartRate: 52), Epoch(motion: nil, heartRate: 55), Epoch(motion: 0.03, heartRate: nil)])
    }

    func testRecordingRoundTripAndTolerantReading() {
        let recording = NightRecording(
            start: start, epochs: [Epoch(motion: 0.0213, heartRate: 54.5), Epoch(motion: nil, heartRate: nil), Epoch(motion: 0.03, heartRate: nil)],
            end: start.addingTimeInterval(90), endReason: .wake, bedAt: start, asleepAt: start.addingTimeInterval(60)
        )
        let text = NightRecording.encode(recording)
        XCTAssertTrue(text.hasPrefix("phasewake-recording 1\nstart=1793570400000\n0.0213;54.5\n;\n0.03;\n"))
        XCTAssertEqual(NightRecording.decode(text), recording)
        XCTAssertEqual(recording.learnNight.asleepAt, start.addingTimeInterval(60))
        // Abgebrochene letzte Zeile, unlesbare Epoche, unbekannter Grund, zwei «onset»-Zeilen (die jüngste gilt).
        let messy = "phasewake-recording 1\nstart=1793570400000\nx;1\n0.02;\nonset=1,2\nonset=3,4\n0.04;60\nend=1793570490000,later\n0.05;"
        let read = NightRecording.decode(messy)
        XCTAssertEqual(read?.epochs, [Epoch(motion: 0.02, heartRate: nil), Epoch(motion: 0.04, heartRate: 60)])
        XCTAssertEqual(read?.endReason, .service)
        XCTAssertEqual(read?.bedAt, Date(timeIntervalSince1970: 0.003))
        XCTAssertEqual(read?.end, start.addingTimeInterval(90))
        XCTAssertNil(NightRecording.decode("phasewake-night 1\nstart=1\n"))
        XCTAssertNil(NightRecording.decode("phasewake-recording 1\n"))
        XCTAssertNil(NightRecording.decode(""))
        // Ohne Ende läuft sie noch; mit Einschlafzeit nachgetragen.
        let open = NightRecording(start: start, epochs: [])
        XCTAssertEqual(NightRecording.decode(NightRecording.encode(open)), open)
        XCTAssertEqual(open.with(bedAt: start, asleepAt: start).bedAt, start)
        XCTAssertEqual(RecordEnd.of(nil), .service)
        XCTAssertEqual(RecordEnd.of("phone"), .phone)
    }

    func testRecorderStartsOneHourBeforeBedtimeAtTheEarliest() {
        let target = start.addingTimeInterval(8 * 3600) // 07:00
        let onset = start.addingTimeInterval(-30 * 60) // 22:30
        let end = target.addingTimeInterval(AppleLearnRecord.afterTarget)
        // 18:00 und 22:29: vor der Einschlaf-Messung nicht starten (Entscheid r1AD 10.11.2026, kein Start 12 Std. im Voraus).
        XCTAssertEqual(AppleLearnRecord.decide(now: start.addingTimeInterval(-5 * 3600), target: target, onsetStart: onset, battery: 90),
                       .wait(until: onset))
        XCTAssertEqual(AppleLearnRecord.decide(now: onset.addingTimeInterval(-60), target: target, onsetStart: onset, battery: 90),
                       .wait(until: onset))
        // 22:30: starten, ausgewertet ab jetzt.
        XCTAssertEqual(AppleLearnRecord.decide(now: onset, target: target, onsetStart: onset, battery: 90),
                       .start(from: onset, until: end, duration: end.timeIntervalSince(onset)))
        let evening = onset.addingTimeInterval(10 * 60)
        // 01:00: ausgewertet ab jetzt (6 Std. bleiben).
        let late = start.addingTimeInterval(2 * 3600)
        XCTAssertEqual(AppleLearnRecord.decide(now: late, target: target, onsetStart: onset, battery: nil),
                       .start(from: late, until: end, duration: end.timeIntervalSince(late)))
        // 04:01: keine 3 Std. mehr; nach der Zielzeit vorbei; unter 50 % nicht.
        XCTAssertEqual(AppleLearnRecord.decide(now: target.addingTimeInterval(-3 * 3600 + 60), target: target, onsetStart: onset, battery: 90),
                       .skip(reason: "too late"))
        XCTAssertEqual(AppleLearnRecord.decide(now: target, target: target, onsetStart: onset, battery: 90), .skip(reason: "past"))
        XCTAssertEqual(AppleLearnRecord.decide(now: evening, target: target, onsetStart: onset, battery: 49), .skip(reason: "battery 49%"))
        XCTAssertNotEqual(AppleLearnRecord.decide(now: evening, target: target, onsetStart: onset, battery: 50), .skip(reason: "battery 50%"))
        // Ende der Auswertung: Ende des Klingelns, höchstens Ende des Rekorders; ohne Klingeln die Zielzeit.
        XCTAssertEqual(AppleLearnRecord.end(wokeAt: target.addingTimeInterval(-600), target: target, until: end), target.addingTimeInterval(-600))
        XCTAssertEqual(AppleLearnRecord.end(wokeAt: end.addingTimeInterval(600), target: target, until: end), end)
        XCTAssertEqual(AppleLearnRecord.end(wokeAt: nil, target: target, until: end), target)
    }

    func testLearningStateFollowsConsentRelearnAndRestart() {
        let t0 = start
        var state = LearningState()
        XCTAssertTrue(state.askConsent)
        XCTAssertFalse(state.active)
        // «Später»: gefragt, nicht begonnen.
        XCTAssertFalse(state.setConsent(false, now: t0))
        XCTAssertFalse(state.askConsent)
        XCTAssertNil(state.since)
        // Schalter an: beginnt jetzt; nochmals an beginnt nicht neu.
        XCTAssertTrue(state.setConsent(true, now: t0.addingTimeInterval(60)))
        XCTAssertEqual(state.since, t0.addingTimeInterval(60))
        XCTAssertTrue(state.active)
        XCTAssertFalse(state.setConsent(true, now: t0.addingTimeInterval(120)))
        XCTAssertEqual(state.since, t0.addingTimeInterval(60))
        // Fertig: Werte, nicht mehr aktiv; ein zweites Fertig ersetzt sie nicht.
        let values = Learning.Values(floor: 0.02, restingHeartRate: 52, latencyMinutes: 12, nights: 7)
        XCTAssertTrue(state.finish(values, now: t0.addingTimeInterval(200)))
        XCTAssertFalse(state.active)
        XCTAssertFalse(state.seen)
        XCTAssertFalse(state.finish(Learning.Values(floor: 0.01, restingHeartRate: nil, latencyMinutes: nil, nights: 7), now: t0))
        XCTAssertEqual(state.values, values)
        state.setSeen()
        XCTAssertTrue(state.seen)
        // «Neu lernen»: läuft wieder, die bisherigen Werte gelten bis zum neuen Fertig.
        state.relearn(now: t0.addingTimeInterval(300))
        XCTAssertTrue(state.relearning)
        XCTAssertTrue(state.active)
        XCTAssertEqual(state.snapshot.floor, 0.02)
        let newer = Learning.Values(floor: 0.01, restingHeartRate: nil, latencyMinutes: 5, nights: 7)
        XCTAssertTrue(state.finish(newer, now: t0.addingTimeInterval(400)))
        XCTAssertEqual(state.values, newer)
        XCTAssertFalse(state.relearning)
        // Schalter aus: Werte bleiben, nicht aktiv.
        state.setConsent(false, now: t0.addingTimeInterval(500))
        XCTAssertEqual(state.values, newer)
        XCTAssertFalse(state.snapshot.active)
        // «Verlauf löschen»: von vorn, Werte weg, Zustimmung bleibt.
        state.restart(now: t0.addingTimeInterval(600))
        XCTAssertNil(state.values)
        XCTAssertEqual(state.since, t0.addingTimeInterval(600))
        XCTAssertEqual(state.consent, false)
        XCTAssertEqual(state.updatedAtMs, 1_793_571_000_000)
        // «Neu lernen» ohne Werte: wie von vorn.
        var fresh = LearningState(since: t0, values: nil, seen: false, updatedAtMs: 0, consent: true)
        fresh.relearn(now: t0.addingTimeInterval(60))
        XCTAssertFalse(fresh.relearning)
        XCTAssertTrue(fresh.active)
        // Nie begonnen: Fertig geht nicht.
        var never = LearningState()
        XCTAssertFalse(never.finish(values, now: t0))
    }

    func testStateSurvivesStorageAndSnapshotTravelsInTheContext() throws {
        var state = LearningState()
        state.setConsent(true, now: start)
        state.finish(Learning.Values(floor: 0.02, restingHeartRate: nil, latencyMinutes: 8, nights: 7), now: start)
        let data = try JSONEncoder().encode(state)
        XCTAssertEqual(try JSONDecoder().decode(LearningState.self, from: data), state)

        let snapshot = LearningSnapshot(active: true, floor: 0.02, restingHeartRate: nil, updatedAtMs: 5)
        var context: [String: Any] = [SyncKeys.learnRestingHr: 60.0]
        snapshot.apply(to: &context)
        XCTAssertNil(context[SyncKeys.learnRestingHr], "fehlender Wert fehlt auch im Paket")
        XCTAssertEqual(LearningSnapshot(context: context), snapshot)
        XCTAssertNil(LearningSnapshot(context: [:]), "älteres iPhone")
        XCTAssertEqual(snapshot.diagLine, "active=1 floor=0.0200 hr=-")
        XCTAssertEqual(LearningSnapshot.empty.diagLine, "active=0 floor=- hr=-")
        XCTAssertTrue(snapshot.replaces(.empty))
        XCTAssertFalse(LearningSnapshot.empty.replaces(snapshot))
        let config = LearningSnapshot(active: false, floor: 0.02, restingHeartRate: 50, updatedAtMs: 1).applied(to: .standard)
        XCTAssertEqual(config.personalFloor, 0.02)
        XCTAssertEqual(config.restingHeartRate, 50)
        XCTAssertEqual(LearningSnapshot.empty.applied(to: .standard), .standard)
    }

    func testRecordingWithImplausibleTimes() {
        // Vollaudit K-7: Zeiten ausserhalb 1970–2100 gelten als unlesbar (Beginn → nil, «onset»/«end» fallen weg).
        let max = "9223372036854775807"
        XCTAssertNil(NightRecording.decode("phasewake-recording 1\nstart=\(max)\n0.02;\n"))
        XCTAssertNil(NightRecording.decode("phasewake-recording 1\nstart=-1\n0.02;\n"))
        let read = NightRecording.decode("phasewake-recording 1\nstart=1793570400000\n0.02;\nonset=\(max),1793570460000\nend=\(max),phone\n")
        XCTAssertNotNil(read)
        XCTAssertNil(read?.bedAt)
        XCTAssertNil(read?.end)
        if let read { XCTAssertTrue(NightRecording.encode(read).hasPrefix("phasewake-recording 1\nstart=1793570400000\n")) }
    }
}
