import XCTest
@testable import PhaseWakeCore

/// Gleiche Fälle wie die Kotlin-Tests zu den Einschlafklängen.
final class SleepSoundsTests: XCTestCase {
    private let rate = 48_000.0

    private func samples(_ sound: SleepSound, seconds: Double, seed: UInt64 = 7) -> [Float] {
        var generator = NoiseGenerator(sound: sound, sampleRate: rate, seed: seed)
        return (0..<Int(seconds * rate)).map { _ in generator.next() }
    }

    private func rms(_ values: ArraySlice<Float>) -> Double {
        sqrt(values.reduce(0) { $0 + Double($1) * Double($1) } / Double(values.count))
    }

    func testOutputStaysBelowPeakWithoutDcOffset() {
        for sound in SleepSound.allCases where !sound.recorded {
            let out = samples(sound, seconds: 10)
            let peak = out.map { abs(Double($0)) }.max() ?? 0
            let mean = out.reduce(0) { $0 + Double($1) } / Double(out.count)
            XCTAssertLessThanOrEqual(peak, NoiseGenerator.peak, "\(sound)")
            XCTAssertLessThan(abs(mean), 0.005, "\(sound)")
            // Hörbar, aber leise (zwischen −40 und −18 dBFS).
            let level = rms(out[...])
            XCTAssertGreaterThan(level, 0.01, "\(sound)")
            XCTAssertLessThan(level, 0.125, "\(sound)")
        }
    }

    func testDeterministicPerSeed() {
        for sound in SleepSound.allCases where !sound.recorded {
            XCTAssertEqual(samples(sound, seconds: 0.05, seed: 3), samples(sound, seconds: 0.05, seed: 3))
            XCTAssertNotEqual(samples(sound, seconds: 0.05, seed: 3), samples(sound, seconds: 0.05, seed: 4))
        }
    }

    /// Ältere gespeicherte Werte bleiben gültig; die Aufnahmen sind genau diese fünf.
    func testSoundsAndStoredNames() {
        for name in ["WHITE", "PINK", "BROWN", "RAIN", "OCEAN"] { XCTAssertNotNil(SleepSound(rawValue: name), name) }
        XCTAssertEqual(SleepSound.allCases.filter(\.recorded), [.rain, .ocean, .forest, .fire, .brook])
    }

    /// Einschlaf-Wellen: zwei Kanäle, je Ohr etwa −30 dBFS, links und rechts verschieden (200 gegen 202 Hz).
    func testBinauralIsStereoAndSoft() {
        XCTAssertEqual(NoiseGenerator(sound: .binaural).channels, 2)
        XCTAssertEqual(NoiseGenerator(sound: .pink).channels, 1)
        let out = samples(.binaural, seconds: 8)
        let left = stride(from: 0, to: out.count, by: 2).map { out[$0] }
        let right = stride(from: 1, to: out.count, by: 2).map { out[$0] }
        for channel in [left, right] {
            let level = rms(channel[...])
            XCTAssertGreaterThan(level, 0.025)
            XCTAssertLessThan(level, 0.045)
        }
        XCTAssertTrue(zip(left, right).contains { abs($0 - $1) > 0.01 })
    }

    /// Ventilator: tieffrequent, Sprünge von Wert zu Wert deutlich kleiner als beim weissen Rauschen.
    func testFanIsDark() {
        func maxStep(_ v: [Float]) -> Float { zip(v.dropFirst(), v).map { abs($0 - $1) }.max() ?? 0 }
        XCTAssertLessThan(maxStep(samples(.fan, seconds: 5)), maxStep(samples(.white, seconds: 5)) / 4)
    }

    /// Wie `recordingsPlayLouderWithoutClipping` (Kotlin).
    func testRecordingsPlayLouderWithoutClipping() {
        for sound in SleepSound.allCases {
            XCTAssertEqual(sound.gainDb, sound.recorded ? 6 : 0, sound.rawValue)
            XCTAssertEqual(sound.gain, sound.recorded ? 1.9952623 : 1, accuracy: 1e-6, sound.rawValue)
        }
        XCTAssertEqual(SleepSounds.amplify(0.2, gain: 2), 0.4, accuracy: 1e-7)
        XCTAssertEqual(SleepSounds.amplify(-0.2, gain: 2), -0.4, accuracy: 1e-7)
        XCTAssertEqual(SleepSounds.amplify(0.9, gain: SleepSound.rain.gain), SleepSounds.ceiling)
        XCTAssertEqual(SleepSounds.amplify(-1, gain: SleepSound.rain.gain), -SleepSounds.ceiling)
        XCTAssertEqual(SleepSounds.amplify(0.3, gain: SleepSound.pink.gain), 0.3)
    }

    func testFadeAndStop() {
        XCTAssertEqual(SleepSounds.gain(elapsed: 0, total: 600), 0)
        XCTAssertEqual(SleepSounds.gain(elapsed: 1.5, total: 600), 0.5, accuracy: 1e-9)
        XCTAssertEqual(SleepSounds.gain(elapsed: 100, total: 600), 1)
        XCTAssertEqual(SleepSounds.gain(elapsed: 585, total: 600), 0.5, accuracy: 1e-9)
        XCTAssertEqual(SleepSounds.gain(elapsed: 600, total: 600), 0)
        XCTAssertEqual(SleepSounds.gain(elapsed: 5000, total: nil), 1)

        let start = Date(timeIntervalSince1970: 1_793_000_000)
        XCTAssertEqual(SleepSounds.stopAt(start: start, timerMinutes: 30, nextWindowStart: nil), start.addingTimeInterval(1800))
        XCTAssertEqual(SleepSounds.stopAt(start: start, timerMinutes: 60, nextWindowStart: start.addingTimeInterval(600)), start.addingTimeInterval(600))
        XCTAssertEqual(SleepSounds.stopAt(start: start, timerMinutes: nil, nextWindowStart: start.addingTimeInterval(9000)), start.addingTimeInterval(9000))
        XCTAssertNil(SleepSounds.stopAt(start: start, timerMinutes: nil, nextWindowStart: nil))
        XCTAssertEqual(SleepSounds.timerOptions, [15, 30, 60, nil])
        XCTAssertEqual(SleepSounds.defaultTimerMinutes, 30)
    }
}
