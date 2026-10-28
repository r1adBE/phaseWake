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
        for sound in SleepSound.allCases {
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
        for sound in SleepSound.allCases {
            XCTAssertEqual(samples(sound, seconds: 0.05, seed: 3), samples(sound, seconds: 0.05, seed: 3))
            XCTAssertNotEqual(samples(sound, seconds: 0.05, seed: 3), samples(sound, seconds: 0.05, seed: 4))
        }
    }

    /// Meer: Die Welle (8–12 s) hebt und senkt die Lautstärke deutlich.
    func testOceanSwells() {
        let out = samples(.ocean, seconds: 24)
        let blocks = stride(from: 0, to: out.count, by: Int(rate)).map { rms(out[$0..<min(out.count, $0 + Int(rate))]) }
        XCTAssertGreaterThan(blocks.max()! / blocks.min()!, 1.5)
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
