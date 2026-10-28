import XCTest
@testable import PhaseWakeCore

/// Gleiche Fälle wie `SoundEventsTest` (Kotlin).
final class SoundEventsTests: XCTestCase {
    private typealias Action = SoundEventDetector.Action

    private var zurich: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }

    private func at(_ text: String) -> Date {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "Europe/Zurich")!
        f.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return f.date(from: text)!
    }

    private let night = "2026-10-27"

    /// Speist Pegel ein: `quiet` dB, ausser in den lauten Bereichen (ms, Ende exklusiv).
    private func run(_ totalMs: Int64, loud: [Range<Int64>], quiet: Double = -60, loudDb: Double = -30) -> [Action] {
        var detector = SoundEventDetector()
        var actions: [Action] = []
        var t: Int64 = 0
        while t < totalMs {
            let level = loud.contains { $0.contains(t) } ? loudDb : quiet
            actions += detector.onFrame(atMs: t, levelDb: level)
            t += SoundEventDetector.frameMs
        }
        actions += detector.finish(atMs: t)
        return actions
    }

    func testNoDetectionDuringWarmup() {
        XCTAssertEqual(run(9_000, loud: [2_000..<5_000]), [])
    }

    func testShortSoundIsIgnoredLongerOneIsAnEvent() {
        // 0,4 s laut: zu kurz. 3 s laut ab 40 s: ein Geräusch mit Ausschnitt ab 38 s.
        XCTAssertEqual(run(120_000, loud: [20_000..<20_400, 40_000..<43_000]), [
            .clipStart(clipStartMs: 38_000, onsetMs: 40_000),
            // 2 s nach dem letzten lauten Block (endet bei 43 s) ist der Ausschnitt zu Ende.
            .clipEnd(onsetMs: 40_000, clipEndMs: 45_000),
            .eventEnd(onsetMs: 40_000, durationMs: 3_000, peakDb: -30),
        ])
    }

    func testClipIsAtMostTenSecondsAndEventMergesWithin30Seconds() {
        // Laut 30–45 s, Pause 20 s, nochmals laut 65–70 s: ein Geräusch (Pause < 30 s), ein Ausschnitt.
        let actions = run(200_000, loud: [30_000..<45_000, 65_000..<70_000])
        XCTAssertEqual(actions, [
            .clipStart(clipStartMs: 28_000, onsetMs: 30_000),
            .clipEnd(onsetMs: 30_000, clipEndMs: 38_000),
            .eventEnd(onsetMs: 30_000, durationMs: 40_000, peakDb: -30),
        ])
    }

    func testNextEventNeedsThirtySecondsQuiet() {
        // Zweites Geräusch 31 s nach dem Ende des ersten: eigener Ausschnitt.
        let actions = run(200_000, loud: [30_000..<31_000, 62_000..<63_000])
        XCTAssertEqual(actions.filter { if case .clipStart = $0 { true } else { false } }.count, 2)
        XCTAssertEqual(actions.filter { if case .eventEnd = $0 { true } else { false } }.count, 2)
    }

    func testFinishClosesOpenEvent() {
        XCTAssertEqual(run(36_000, loud: [30_000..<36_000]), [
            .clipStart(clipStartMs: 28_000, onsetMs: 30_000),
            .clipEnd(onsetMs: 30_000, clipEndMs: 36_000),
            .eventEnd(onsetMs: 30_000, durationMs: 6_000, peakDb: -30),
        ])
    }

    func testThresholdFollowsNoiseFloor() {
        var detector = SoundEventDetector()
        var t: Int64 = 0
        for _ in 0..<150 {
            _ = detector.onFrame(atMs: t, levelDb: -50)
            t += 100
        }
        XCTAssertEqual(detector.threshold!, -38, accuracy: 1e-9)
        // Lauter als vorher, aber nicht 12 dB über dem Grundrauschen: kein Geräusch.
        let actions = (0..<20).flatMap { detector.onFrame(atMs: t + Int64($0) * 100, levelDb: -40) }
        XCTAssertEqual(actions, [])
    }

    func testQuietSoundsBelowMinus50DbAreIgnored() {
        // Sehr stilles Zimmer (−70 dB): −55 dB liegt 15 dB darüber, ist aber leiser als −50 dBFS.
        XCTAssertEqual(run(120_000, loud: [40_000..<43_000], quiet: -70, loudDb: -55), [])
        // −50 dB zählt (Grenze eingeschlossen).
        XCTAssertEqual(run(120_000, loud: [40_000..<43_000], quiet: -70, loudDb: -50).last,
                       .eventEnd(onsetMs: 40_000, durationMs: 3_000, peakDb: -50))
    }

    func testAutoStartFromBedtimeOfTheComingNight() {
        let target = at("2026-10-28T06:30")
        // Schlafziel 7,5 Std.: Bettzeit 23:00.
        XCTAssertFalse(SoundEvents.mayAutoStart(now: at("2026-10-27T22:59"), nextTarget: target, goalMinutes: 450, calendar: zurich))
        XCTAssertTrue(SoundEvents.mayAutoStart(now: at("2026-10-27T23:00"), nextTarget: target, goalMinutes: 450, calendar: zurich))
        XCTAssertTrue(SoundEvents.mayAutoStart(now: at("2026-10-28T02:00"), nextTarget: target, goalMinutes: 450, calendar: zurich))
        // Im Weckfenster nicht mehr automatisch.
        XCTAssertFalse(SoundEvents.mayAutoStart(now: target.addingTimeInterval(-600), nextTarget: target, goalMinutes: 450, calendar: zurich, windowStart: target.addingTimeInterval(-1800)))
        // Ab 21 Uhr gilt mit Wecker die Bettzeit, nicht 21 Uhr.
        XCTAssertFalse(SoundEvents.mayAutoStart(now: at("2026-10-27T21:30"), nextTarget: target, goalMinutes: 450, calendar: zurich))
        // Nach der Zielzeit zählt der nächste Wecker (hier am Abend darauf noch vor der Bettzeit).
        XCTAssertFalse(SoundEvents.mayAutoStart(now: at("2026-10-28T07:00"), nextTarget: at("2026-10-29T06:30"), goalMinutes: 450, calendar: zurich))
    }

    func testAutoStartWithoutAlarmFrom21() {
        XCTAssertFalse(SoundEvents.mayAutoStart(now: at("2026-10-27T20:59"), nextTarget: nil, goalMinutes: 450, calendar: zurich))
        XCTAssertTrue(SoundEvents.mayAutoStart(now: at("2026-10-27T21:00"), nextTarget: nil, goalMinutes: 450, calendar: zurich))
        XCTAssertTrue(SoundEvents.mayAutoStart(now: at("2026-10-27T23:59"), nextTarget: nil, goalMinutes: 450, calendar: zurich))
        XCTAssertFalse(SoundEvents.mayAutoStart(now: at("2026-10-28T00:30"), nextTarget: nil, goalMinutes: 450, calendar: zurich))
        // Wecker erst in mehr als 24 Std.: wie ohne Wecker.
        XCTAssertTrue(SoundEvents.mayAutoStart(now: at("2026-10-27T21:30"), nextTarget: at("2026-10-29T06:30"), goalMinutes: 450, calendar: zurich))
        XCTAssertFalse(SoundEvents.mayAutoStart(now: at("2026-10-27T20:00"), nextTarget: at("2026-10-29T06:30"), goalMinutes: 450, calendar: zurich))
    }

    func testNightsWithoutRecordNewestFirst() {
        func clip(_ night: String, _ time: String, _ file: String) -> SoundClip {
            SoundClip(night: night, at: at(time), type: .snore, durationMs: 1_000, peakDb: -30, file: file)
        }
        let clips = [
            clip("2026-10-27", "2026-10-27T23:00", "a.m4a"),
            clip("2026-10-27", "2026-10-28T03:00", "b.m4a"),
            clip("2026-10-26", "2026-10-27T01:00", "c.m4a"),
            clip("2026-10-25", "2026-10-26T02:00", "d.m4a"),
        ]
        let result = SoundEvents.nightsWithoutRecord(clips, recorded: ["2026-10-26"])
        XCTAssertEqual(result.map { $0.night }, ["2026-10-27", "2026-10-25"])
        XCTAssertEqual(result.map { $0.last }, [at("2026-10-28T03:00"), at("2026-10-26T02:00")])
        XCTAssertTrue(SoundEvents.nightsWithoutRecord(clips, recorded: ["2026-10-27", "2026-10-26", "2026-10-25"]).isEmpty)
    }

    func testLabelsMapToTypes() {
        XCTAssertEqual(SoundEvents.typeOf("Snoring"), .snore)
        XCTAssertEqual(SoundEvents.typeOf("snoring"), .snore)
        XCTAssertEqual(SoundEvents.typeOf("Speech"), .talk)
        XCTAssertEqual(SoundEvents.typeOf("whispering"), .talk)
        XCTAssertEqual(SoundEvents.typeOf("Conversation"), .talk)
        XCTAssertEqual(SoundEvents.typeOf("shout"), .talk)
        XCTAssertEqual(SoundEvents.typeOf("Dog"), .other)
        XCTAssertEqual(SoundEvents.classify(["Snoring": 0.6, "Speech": 0.3, "Silence": 0.1]), .snore)
        // Nur Ziel-Bezeichnungen zählen: Atmen schlägt Schnarchen nicht.
        XCTAssertEqual(SoundEvents.classify(["Snoring": 0.2, "Breathing": 0.5]), .snore)
        XCTAssertEqual(SoundEvents.classify(["Snoring": 0.2, "Whispering": 0.1, "Speech": 0.3]), .talk)
        // Unter dem Mindestwert: anderes.
        XCTAssertEqual(SoundEvents.classify(["Snoring": 0.1, "Speech": 0.149, "Silence": 0.9]), .other)
        XCTAssertEqual(SoundEvents.classify(["Snoring": 0.15, "Speech": 0.15]), .snore)
        XCTAssertEqual(SoundEvents.classify([:]), .other)
    }

    func testScoresAreAveragedOverFrames() {
        // Fehlt eine Bezeichnung in einem Abschnitt, zählt sie dort als 0 (wie Kotlin).
        let frames: [[String: Double]] = [["Snoring": 0.6, "Speech": 0.1], ["Speech": 0.3], ["Snoring": 0.0]]
        let average = SoundEvents.averageScores(frames)
        XCTAssertEqual(average["Snoring"]!, 0.2, accuracy: 1e-9)
        XCTAssertEqual(average["Speech"]!, 0.4 / 3, accuracy: 1e-9)
        XCTAssertEqual(SoundEvents.classify(average), .snore)
        // Ein kurzer Ausreisser in einem von zehn Abschnitten reicht nicht.
        let spike: [[String: Double]] = [["Speech": 0.9]] + Array(repeating: ["Silence": 0.9], count: 9)
        XCTAssertEqual(SoundEvents.classify(SoundEvents.averageScores(spike)), .other)
        XCTAssertEqual(SoundEvents.averageScores([]), [:])
    }

    func testLevelOfSilenceAndFullScale() {
        [Float](repeating: 0, count: 160).withUnsafeBufferPointer { XCTAssertEqual(SoundEvents.levelDb($0), SoundEvents.silenceDb) }
        let square = (0..<160).map { $0 % 2 == 0 ? Float(1) : Float(-1) }
        square.withUnsafeBufferPointer { XCTAssertEqual(SoundEvents.levelDb($0), 0, accuracy: 0.01) }
    }

    func testRecordingEndsThirtyMinutesAfterTargetOrAfterTwelveHours() {
        let start = at("2026-10-27T22:00")
        XCTAssertEqual(SoundEvents.recordingEnd(start: start, nextTarget: at("2026-10-28T06:30")), at("2026-10-28T07:00"))
        XCTAssertEqual(SoundEvents.recordingEnd(start: start, nextTarget: nil), at("2026-10-28T10:00"))
        // Wecker mehr als 24 Std. entfernt: wie ohne Wecker.
        XCTAssertEqual(SoundEvents.recordingEnd(start: start, nextTarget: at("2026-10-29T06:30")), at("2026-10-28T10:00"))
        // Früh eingeschaltet (18:00), Wecker 07:00 (13 Std.): bis Zielzeit + 30 Min, nicht schon um 06:00.
        XCTAssertEqual(SoundEvents.recordingEnd(start: at("2026-10-27T18:00"), nextTarget: at("2026-10-28T07:00")), at("2026-10-28T07:30"))
        // Genau 24 Std. zählt noch.
        XCTAssertEqual(SoundEvents.recordingEnd(start: start, nextTarget: at("2026-10-28T22:00")), at("2026-10-28T22:30"))
    }

    private func clip(_ time: String, _ type: SoundType, _ duration: Int64, _ peak: Int, file: String? = nil) -> SoundClip {
        SoundClip(night: SoundEvents.nightDate(at(time), calendar: zurich), at: at(time), type: type, durationMs: duration, peakDb: peak,
                  file: file ?? (time + ".m4a").replacingOccurrences(of: ":", with: "-"))
    }

    func testSummaryCountsSnoreMinutesAndClips() {
        let clips = [
            clip("2026-10-27T23:10", .snore, 6 * 60_000, -30),
            clip("2026-10-28T01:00", .snore, 5 * 60_000 + 40_000, -32),
            clip("2026-10-28T02:00", .talk, 3_000, -25),
            clip("2026-10-28T03:00", .other, 2_000, -40),
        ]
        let summary = SoundEvents.summary(clips)
        XCTAssertEqual(summary.snoreMinutes, 12)
        XCTAssertEqual(summary.snoreClips, 2)
        XCTAssertEqual(summary.talkClips, 1)
        XCTAssertEqual(summary.clips, 4)
        XCTAssertEqual(SoundEvents.summary([clip("2026-10-27T23:10", .snore, 5_000, -30)]).snoreMinutes, 1)
        XCTAssertEqual(SoundEvents.summary([]).snoreMinutes, 0)
        XCTAssertTrue(clips.allSatisfy { $0.night == night })
    }

    func testCapKeepsLoudestClipsOfTheNight() {
        let existing = [
            clip("2026-10-27T23:00", .other, 1_000, -40, file: "a.m4a"),
            clip("2026-10-27T23:30", .other, 1_000, -20, file: "b.m4a"),
            // Andere Nacht: zählt nicht mit.
            clip("2026-10-26T23:30", .other, 1_000, -50, file: "x.m4a"),
        ]
        let louder = SoundEvents.add(existing, clip("2026-10-28T01:00", .snore, 1_000, -30, file: "c.m4a"), max: 2)
        XCTAssertEqual(louder.dropped.map(\.file), ["a.m4a"])
        XCTAssertEqual(louder.kept.map(\.file), ["x.m4a", "b.m4a", "c.m4a"])
        let quieter = SoundEvents.add(existing, clip("2026-10-28T01:00", .snore, 1_000, -45, file: "d.m4a"), max: 2)
        XCTAssertEqual(quieter.dropped.map(\.file), ["d.m4a"])
        XCTAssertEqual(SoundEvents.add(existing, clip("2026-10-28T01:00", .snore, 1_000, -45, file: "e.m4a")).dropped.count, 0)
    }

    func testExpireAfterSevenDays() {
        // 7 × 24 h zurück (über die Zeitumstellung am 25. 10. hinweg): 21. 10., 23:15 Sommerzeit.
        let old = clip("2026-10-20T23:00", .snore, 1_000, -30, file: "old.m4a")
        let fresh = clip("2026-10-21T23:30", .snore, 1_000, -30, file: "fresh.m4a")
        let result = SoundEvents.expire([old, fresh], now: at("2026-10-28T22:15"))
        XCTAssertEqual(result.kept, [fresh])
        XCTAssertEqual(result.expired, [old])
    }

    func testStorageRoundTripSkipsBrokenLinesAndPaths() {
        let clips = [
            clip("2026-10-28T01:00", .talk, 4_200, -21, file: "b.m4a"),
            clip("2026-10-27T23:00", .snore, 61_000, -33, file: "a.m4a"),
        ]
        let text = SoundEvents.encode(clips)
        XCTAssertEqual(text.components(separatedBy: "\n").first, "2026-10-27|\(SoundEvents.millis(at("2026-10-27T23:00")))|SNORE|61000|-33|a.m4a")
        XCTAssertEqual(SoundEvents.decode(text), clips.sorted { $0.at < $1.at })
        let broken = text + "\nkaputt\n2026-10-27|1|SNORE|1|-3|../etwas\n2026-10-27|2|LAUT|1|-3|z.m4a"
        let read = SoundEvents.decode(broken)
        XCTAssertEqual(read.count, 3)
        XCTAssertEqual(read.first { $0.file == "z.m4a" }?.type, .other)
        XCTAssertEqual(SoundEvents.decode(nil), [])
        XCTAssertTrue(!SoundEvents.isSafeFile("../x") && SoundEvents.isSafeFile("a.m4a"))
    }

    func testForNightFilters() {
        let clips = [clip("2026-10-28T01:00", .talk, 1, -1, file: "b.m4a"), clip("2026-10-26T23:00", .snore, 1, -1, file: "a.m4a")]
        XCTAssertEqual(SoundEvents.forNight(clips, night: night).map(\.file), ["b.m4a"])
        XCTAssertNil(SoundEvents.forNight(clips, night: NightDate.adding(5, to: night)).first)
    }
}
