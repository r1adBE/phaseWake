import XCTest
import PhaseWakeCore

/// Lernphase (``Learning``): gezählte Nächte, gelernte Werte, Schlafenszeit-Empfehlung. Dieselben Fälle prüft die
/// Kotlin-Umsetzung (`LearningTest`); dazu die Apple-Fälle (Puls aus Apple Health, Einschlafdauer aus «Im Bett»).
final class LearningTests: XCTestCase {
    private let calendar = Calendar.gregorian(in: TimeZone(identifier: "Europe/Zurich")!)

    /// 23:00 Ortszeit am 1. + `day` November 2026 (2026-11-01T22:00:00Z).
    private func evening(_ day: Int) -> Date {
        Date(timeIntervalSince1970: 1_793_570_400 + TimeInterval(day) * 86_400)
    }

    private func night(_ day: Int, _ epochs: Int, motion: Double? = 0.02, hr: Double? = 55, bedAt: Date? = nil, asleepAt: Date? = nil) -> Learning.Night {
        Learning.Night(start: evening(day), epochs: Array(repeating: Epoch(motion: motion, heartRate: hr), count: epochs), bedAt: bedAt, asleepAt: asleepAt)
    }

    func testEveningIsElevenPmInZurich() {
        XCTAssertEqual(SleepNotes.dateString(evening(0), calendar: calendar), "2026-11-01")
        XCTAssertEqual(calendar.component(.hour, from: evening(0)), 23)
    }

    func testNightCountsFromThreeHoursOfMotion() {
        XCTAssertTrue(Learning.counts(night(0, Learning.minSpanEpochs)))
        XCTAssertFalse(Learning.counts(night(0, Learning.minSpanEpochs - 1)), "durchgehend, aber unter 3 Std.")
        XCTAssertFalse(Learning.counts(night(0, 0)))
        // Epochen ohne Bewegung (Uhr abgelegt, Lücken) zählen nicht.
        let gaps = Learning.Night(start: evening(0), epochs: (0..<400).map { Epoch(motion: $0 < 100 ? nil : 0.02, heartRate: 55) })
        XCTAssertFalse(Learning.counts(gaps))
        XCTAssertEqual(Learning.measuredEpochs(gaps), 300)
    }

    /// Stichproben wie in der Lernphase der Galaxy Watch: je 5 Min (10 Epochen) 2 gemessene Epochen, die übrigen leer.
    private func sampled(_ samples: Int, hr: Double? = 55) -> Learning.Night {
        Learning.Night(start: evening(0), epochs: (0..<(samples * 10 - 8)).map {
            $0 % 10 < 2 ? Epoch(motion: 0.02, heartRate: hr) : Epoch(motion: nil, heartRate: nil)
        })
    }

    func testSampledNightCountsFromThirtySixSamplesOverThreeHours() {
        // 36 Stichproben = 72 Epochen über 35 · 10 + 2 = 352 Epochen: noch keine 3 Std.
        XCTAssertEqual(Learning.measuredEpochs(sampled(36)), 72)
        XCTAssertFalse(Learning.counts(sampled(36)))
        // 37 Stichproben: 74 Epochen über 362 Epochen.
        XCTAssertTrue(Learning.counts(sampled(37)))
        // Über 3 Std. verteilt, aber zu wenige Werte (eine Stichprobe je 15 Min).
        let sparse = Learning.Night(start: evening(0), epochs: (0..<400).map {
            $0 % 30 < 2 ? Epoch(motion: 0.02, heartRate: 55) : Epoch(motion: nil, heartRate: nil)
        })
        XCTAssertFalse(Learning.counts(sparse))
        // Ruhepuls aus Stichproben: ab 30 Stichproben (60 Epochen mit Puls).
        XCTAssertNil(Learning.nightRestingHeartRate(sampled(29)))
        XCTAssertEqual(Learning.nightRestingHeartRate(sampled(30)), 55)
        // Grundrauschen aus den gemessenen Epochen, die leeren zählen nicht.
        XCTAssertEqual(Learning.nightFloor(sampled(37)), 0.02)
    }

    func testCountsOneNightPerDateFromTheStart() {
        let since = evening(1)
        let later = evening(1).addingTimeInterval(90 * 60)
        let nights = [
            night(0, 500), // vor dem Beginn der Lernphase
            night(1, 400),
            // Zweite Aufzeichnung derselben Nacht (00:30): zählt einmal, die längere gilt.
            Learning.Night(start: later, epochs: Array(repeating: Epoch(motion: 0.03, heartRate: 55), count: 450)),
            night(2, 100), // zu kurz
            night(3, 360),
        ]
        let counted = Learning.counted(nights, since: since, calendar: calendar)
        XCTAssertEqual(counted.map(\.start), [later, evening(3)])
        XCTAssertEqual(Learning.progress(counted.count), 3)
        XCTAssertEqual(Learning.progress(0), 1)
        XCTAssertEqual(Learning.progress(9), Learning.nights)
        XCTAssertFalse(Learning.finished(6))
        XCTAssertTrue(Learning.finished(7))
        // Gleich viele Epochen: die erste bleibt.
        let twin = Learning.Night(start: later, epochs: night(1, 400).epochs)
        XCTAssertEqual(Learning.counted([night(1, 400), twin], since: since, calendar: calendar).map(\.start), [evening(1)])
    }

    func testRestingHeartRateIsTenthPercentileWithEnoughPulse() {
        let pulses = (0..<200).map { Epoch(motion: 0.02, heartRate: 50 + Double($0 % 20)) } // je 10 Werte 50 … 69
        // n = 200: Position 20 → der 20. kleinste Wert ist 51.
        XCTAssertEqual(Learning.nightRestingHeartRate(Learning.Night(start: evening(0), epochs: pulses)), 51)
        XCTAssertNil(Learning.nightRestingHeartRate(night(0, 400, hr: nil)))
        let few = Learning.Night(start: evening(0), epochs: (0..<400).map {
            Epoch(motion: 0.02, heartRate: $0 < Learning.minHeartRateEpochs - 1 ? 55 : nil)
        })
        XCTAssertNil(Learning.nightRestingHeartRate(few))
    }

    func testAppleNeedsFewerPulseEpochsFromAppleHealth() {
        // Apple Health legt in Ruhe etwa alle 5 Min einen Puls ab: 36 Epochen mit Puls genügen.
        let health = Learning.Night(start: evening(0), epochs: (0..<720).map {
            Epoch(motion: 0.02, heartRate: $0 % 10 == 0 && $0 < 360 ? 52 : nil)
        })
        XCTAssertNil(Learning.nightRestingHeartRate(health))
        XCTAssertEqual(Learning.nightRestingHeartRate(health, minEpochs: Learning.minHeartRateEpochsApple), 52)
        let thin = Learning.Night(start: evening(0), epochs: Array(health.epochs.prefix(350)))
        XCTAssertNil(Learning.nightRestingHeartRate(thin, minEpochs: Learning.minHeartRateEpochsApple), "nur 35 Werte")
        let values = Learning.learn([health, health, health], minHeartRateEpochs: Learning.minHeartRateEpochsApple)
        XCTAssertEqual(values.restingHeartRate, 52)
        XCTAssertNil(Learning.learn([health, health, health]).restingHeartRate)
    }

    func testLatencyFromBedToAsleepCapped() {
        let bed = evening(0)
        XCTAssertEqual(Learning.nightLatencyMinutes(night(0, 1, bedAt: bed, asleepAt: bed.addingTimeInterval(12 * 60 + 59))), 12)
        XCTAssertEqual(Learning.nightLatencyMinutes(night(0, 1, bedAt: bed, asleepAt: bed.addingTimeInterval(2 * 3600))), Learning.maxLatencyMinutes)
        // Eingeschlafen vor der geplanten Bettzeit: wann ins Bett gegangen wurde, ist unbekannt.
        XCTAssertNil(Learning.nightLatencyMinutes(night(0, 1, bedAt: bed, asleepAt: bed.addingTimeInterval(-60))))
        XCTAssertNil(Learning.nightLatencyMinutes(night(0, 1)))
    }

    func testLearnsMediansAndLeavesTooFewValuesOut() {
        let bed = evening(0)
        let nights = [0.010, 0.020, 0.030, 0.025].enumerated().map { i, floor in
            Learning.Night(
                start: evening(i), epochs: Array(repeating: Epoch(motion: floor, heartRate: i < 2 ? nil : 50 + Double(i)), count: 400),
                bedAt: bed, asleepAt: i == 0 ? nil : bed.addingTimeInterval(TimeInterval(10 * 60 * i))
            )
        }
        let values = Learning.learn(nights)
        XCTAssertEqual(values.floor ?? 0, 0.0225, accuracy: 1e-12) // Median von 0.010, 0.020, 0.025, 0.030
        XCTAssertNil(values.restingHeartRate, "nur 2 Nächte mit Puls")
        XCTAssertEqual(values.latencyMinutes, 20) // 10, 20, 30
        XCTAssertEqual(values.nights, 4)
        let more = Learning.learn(nights + [Learning.Night(start: evening(5), epochs: Array(repeating: Epoch(motion: 0.02, heartRate: 60), count: 400))])
        XCTAssertEqual(more.restingHeartRate, 53) // Median von 52, 53, 60
        // Gerade Anzahl Einschlafdauern: Mittel der beiden mittleren, halbe Minuten aufgerundet (wie `Math.round`).
        let even = [10, 20, 30, 40].map { Learning.Night(start: bed, epochs: [], bedAt: bed, asleepAt: bed.addingTimeInterval(TimeInterval($0 * 60))) }
        XCTAssertEqual(Learning.learn(even).latencyMinutes, 25)
        let half = [10, 20, 25, 30].map { Learning.Night(start: bed, epochs: [], bedAt: bed, asleepAt: bed.addingTimeInterval(TimeInterval($0 * 60))) }
        XCTAssertEqual(Learning.learn(half).latencyMinutes, 23) // Median 22.5 → 23
    }

    func testMedianOfEvenAndOddCounts() {
        XCTAssertNil(Learning.median([]))
        XCTAssertEqual(Learning.median([3, 1, 2]), 2)
        XCTAssertEqual(Learning.median([4, 1, 2, 3]), 2.5)
    }

    func testAppliesLearnedValuesToTheConfig() {
        let values = Learning.Values(floor: 0.02, restingHeartRate: 52, latencyMinutes: 10, nights: 7)
        let config = Learning.withLearned(Calibration.calibrate(nights: []), values)
        XCTAssertEqual(config.personalFloor, 0.02)
        XCTAssertEqual(config.restingHeartRate, 52)
        XCTAssertEqual(config.lightThreshold, DetectorConfig.defaultLightThreshold)
        XCTAssertEqual(Learning.withLearned(.standard, nil), .standard)
        XCTAssertTrue(Learning.raisesThresholds(0.02))
        XCTAssertFalse(Learning.raisesThresholds(0.0004))
        XCTAssertFalse(Learning.raisesThresholds(nil))
        // Ungültige Werte (nie aus der Lernphase) gelten als keine.
        let odd = Learning.withLearned(.standard, Learning.Values(floor: -1, restingHeartRate: 0, latencyMinutes: nil, nights: 7))
        XCTAssertNil(odd.personalFloor)
        XCTAssertNil(odd.restingHeartRate)
    }

    func testBedtimeRangeEndsAtGoalPlusLatencyBeforeTarget() {
        let target = Date(timeIntervalSince1970: 1_794_290_400) // 2026-11-10T06:00:00Z, 07:00 Zürich
        // 07:00 − 7½ Std. − 12 Min = 23:18 → abgerundet 23:15, frühestens 22:45.
        let range = Learning.bedtimeRange(target: target, sleepMinutes: 450, latencyMinutes: 12)
        XCTAssertEqual(range.until, Date(timeIntervalSince1970: 1_794_262_500)) // 2026-11-09T22:15:00Z
        XCTAssertEqual(range.from, Date(timeIntervalSince1970: 1_794_260_700)) // 2026-11-09T21:45:00Z
        // Ohne gelernte Einschlafdauer: die Bettzeit selbst (23:30).
        XCTAssertEqual(Learning.bedtimeRange(target: target, sleepMinutes: 450, latencyMinutes: nil).until, Date(timeIntervalSince1970: 1_794_263_400))
    }

    func testHealthOnsetFromInBedAndFirstSleep() {
        let start = evening(0)
        // «Im Bett» 23:20, eingeschlafen 23:41 → 21 Min.
        let onset = Learning.healthOnset(start: start, inBed: [start.addingTimeInterval(40 * 60), start.addingTimeInterval(20 * 60)],
                                         asleep: [start.addingTimeInterval(80 * 60), start.addingTimeInterval(41 * 60)])
        XCTAssertEqual(onset?.bedAt, start.addingTimeInterval(20 * 60))
        XCTAssertEqual(onset?.asleepAt, start.addingTimeInterval(41 * 60))
        let night = Learning.Night(start: start, epochs: [], bedAt: onset?.bedAt, asleepAt: onset?.asleepAt)
        XCTAssertEqual(Learning.nightLatencyMinutes(night), 21)
        // Im Bett vor dem Beginn der Messung: frühestens der Beginn.
        XCTAssertEqual(Learning.healthOnset(start: start, inBed: [start.addingTimeInterval(-600)], asleep: [start])?.bedAt, start)
        // Ohne «Im Bett» oder ohne Schlaf: unbekannt.
        XCTAssertNil(Learning.healthOnset(start: start, inBed: [], asleep: [start]))
        XCTAssertNil(Learning.healthOnset(start: start, inBed: [start], asleep: []))
    }
}
