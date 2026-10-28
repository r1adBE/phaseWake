import XCTest
@testable import PhaseWakeCore

/// Gleiche Fälle wie die Kotlin-Tests zu Notizen, Schlafziel und Bettzeit.
final class SleepNotesTests: XCTestCase {
    private var zurich: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }

    /// Ortszeit Zürich «yyyy-MM-ddTHH:mm».
    private func at(_ text: String) -> Date {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "Europe/Zurich")!
        f.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return f.date(from: text)!
    }

    private func night(_ windowStart: String, feeling: Int?) -> NightRecord {
        let ws = at(windowStart)
        return NightRecord(alarmId: "a", windowStart: ws, target: ws.addingTimeInterval(1800), wokeAt: ws.addingTimeInterval(1200),
                           reason: .lightSleep, snoozes: 0, epochs: [], feeling: feeling)
    }

    // MARK: Nachtdatum und Sichtbarkeit

    func testNightDateIsEveningOfTheNight() {
        XCTAssertEqual(SleepNotes.nightDate(at("2026-10-28T06:30"), calendar: zurich), "2026-10-27")
        XCTAssertEqual(SleepNotes.nightDate(at("2026-10-28T11:59"), calendar: zurich), "2026-10-27")
        XCTAssertEqual(SleepNotes.nightDate(at("2026-10-28T12:00"), calendar: zurich), "2026-10-28")
        XCTAssertEqual(SleepNotes.nightDate(at("2026-10-28T22:00"), calendar: zurich), "2026-10-28")
        XCTAssertEqual(SleepNotes.nightDate(of: night("2026-10-28T06:00", feeling: nil), calendar: zurich), "2026-10-27")
    }

    func testEntryVisibleFromEveningUntilWindow() {
        let window = at("2026-10-29T06:00")
        XCTAssertFalse(SleepNotes.entryVisible(now: at("2026-10-28T16:59"), nextWindowStart: window, calendar: zurich))
        XCTAssertTrue(SleepNotes.entryVisible(now: at("2026-10-28T17:00"), nextWindowStart: window, calendar: zurich))
        XCTAssertTrue(SleepNotes.entryVisible(now: at("2026-10-29T01:00"), nextWindowStart: window, calendar: zurich))
        XCTAssertFalse(SleepNotes.entryVisible(now: at("2026-10-29T06:00"), nextWindowStart: window, calendar: zurich))
        // Nach dem Wecken: nächstes Fenster erst morgen – am Vormittag keine Notizen.
        XCTAssertFalse(SleepNotes.entryVisible(now: at("2026-10-29T08:00"), nextWindowStart: at("2026-10-30T06:00"), calendar: zurich))
        // Ohne Wecker: abends ja, nach Mitternacht nein.
        XCTAssertTrue(SleepNotes.entryVisible(now: at("2026-10-28T23:00"), nextWindowStart: nil, calendar: zurich))
        XCTAssertFalse(SleepNotes.entryVisible(now: at("2026-10-29T01:00"), nextWindowStart: nil, calendar: zurich))
    }

    // MARK: Format

    func testNormalizeOrdersFixedThenCustom() {
        XCTAssertEqual(SleepNotes.normalize(["custom:b", "SICK", "CAFFEINE", "custom:a", "SICK", ""]), ["CAFFEINE", "SICK", "custom:a", "custom:b"])
        XCTAssertEqual(SleepNotes.toggle("ALCOHOL", in: ["SICK"]), ["ALCOHOL", "SICK"])
        XCTAssertEqual(SleepNotes.toggle("SICK", in: ["ALCOHOL", "SICK"]), ["ALCOHOL"])
    }

    func testEncodeDecodeRoundTripAndTrim() {
        let notes = ["2026-10-27": ["STRESS", "CAFFEINE"], "2026-10-26": ["custom:x"], "2026-10-25": []]
        let text = SleepNotes.encode(notes)
        XCTAssertEqual(text, "2026-10-26|custom:x\n2026-10-27|CAFFEINE,STRESS")
        XCTAssertEqual(SleepNotes.decode(text), ["2026-10-26": ["custom:x"], "2026-10-27": ["CAFFEINE", "STRESS"]])
        XCTAssertEqual(SleepNotes.decode("kaputt\n2026-10-27|SICK\n|x"), ["2026-10-27": ["SICK"]])
        var many: [String: [String]] = [:]
        for day in 0..<100 { many[SleepNotes.dateString(at("2026-01-01T12:00").addingTimeInterval(Double(day) * 86_400), calendar: zurich)] = ["NAP"] }
        let trimmed = SleepNotes.decode(SleepNotes.encode(many))
        XCTAssertEqual(trimmed.count, 90)
        XCTAssertNil(trimmed["2026-01-01"])
        XCTAssertNotNil(trimmed["2026-04-10"])
    }

    func testCustomNotes() {
        let long = String(repeating: "x", count: 40)
        let notes = (0..<7).map { CustomNote(id: "custom:\($0)", name: $0 == 0 ? long : "Tee|\($0)") }
        XCTAssertEqual(notes[0].name.count, 30)
        let decoded = SleepNotes.decodeCustom(SleepNotes.encodeCustom(notes))
        XCTAssertEqual(decoded.count, 5)
        XCTAssertEqual(decoded[1], CustomNote(id: "custom:1", name: "Tee 1"))
        XCTAssertEqual(SleepNotes.decodeCustom("CAFFEINE|nein\ncustom:a|\ncustom:b|Tee"), [CustomNote(id: "custom:b", name: "Tee")])
        XCTAssertTrue(SleepNotes.newCustomId().hasPrefix("custom:"))
    }

    func testCsvColumn() {
        XCTAssertEqual(SleepNotes.csvValue(["custom:a", "SICK", "ALCOHOL"]), "ALCOHOL;SICK;custom")
        XCTAssertEqual(SleepNotes.csvValue([]), "")
        let ws = at("2026-10-28T06:00")
        let n = NightRecord(alarmId: "a", windowStart: ws, target: ws.addingTimeInterval(1800), wokeAt: ws.addingTimeInterval(1200),
                            reason: .lightSleep, snoozes: 0, epochs: [Epoch(motion: 0, heartRate: nil)], feeling: 3)
        let csv = NightHistory.exportCsv([n], notes: ["2026-10-27": ["ALCOHOL", "custom:a"]], calendar: zurich)
        XCTAssertEqual(csv.split(separator: "\n")[1], "1,30,LIGHT_SLEEP,10,0,3,0,0.000000,,ALCOHOL;custom")
    }

    // MARK: Wirkung

    func testNoteEffectNeedsThreeNightsEachSide() {
        // 3 Nächte mit Alkohol (Befinden 2), 4 ohne (Befinden 4); Stress nur 2-mal.
        var nights: [NightRecord] = []
        var notes: [String: [String]] = [:]
        for day in 1...7 {
            let ws = "2026-10-\(String(format: "%02d", day + 1))T06:00"
            let alcohol = day <= 3
            nights.append(night(ws, feeling: alcohol ? 2 : 4))
            notes["2026-10-\(String(format: "%02d", day))"] = (alcohol ? ["ALCOHOL"] : []) + (day <= 2 ? ["STRESS"] : [])
        }
        // Nacht ohne Befinden zählt nicht.
        nights.append(night("2026-10-20T06:00", feeling: nil))
        notes["2026-10-19"] = ["ALCOHOL"]
        let effects = NoteEffect.compute(nights: nights, notes: notes, calendar: zurich)
        XCTAssertEqual(effects.map(\.noteId), ["ALCOHOL"])
        let e = effects[0]
        XCTAssertEqual(e.nightsWith, 3)
        XCTAssertEqual(e.nightsWithout, 4)
        XCTAssertEqual(e.avgFeelingWith, 2, accuracy: 1e-9)
        XCTAssertEqual(e.avgFeelingWithout, 4, accuracy: 1e-9)
        XCTAssertEqual(e.delta, -2, accuracy: 1e-9)
        XCTAssertTrue(e.isVisible)
        XCTAssertNil(e.avgAsleepWith)
    }

    func testNoteEffectSortsByAbsoluteDeltaAndAddsSleep() {
        var nights: [NightRecord] = []
        var notes: [String: [String]] = [:]
        var asleep: [String: Int] = [:]
        // Sport: Befinden +2; Kaffee: −2,5. Schlafdauer für alle Nächte bekannt.
        let feelings = [4, 4, 4, 1, 1, 1, 3, 3, 3]
        for (i, feeling) in feelings.enumerated() {
            let date = "2026-10-\(String(format: "%02d", i + 1))"
            nights.append(night("2026-10-\(String(format: "%02d", i + 2))T06:00", feeling: feeling))
            notes[date] = (i < 3 ? ["EXERCISE"] : []) + (i >= 3 && i < 6 ? ["CAFFEINE"] : [])
            asleep[date] = i >= 3 && i < 6 ? 360 : 450
        }
        let effects = NoteEffect.compute(nights: nights, notes: notes, asleepByNight: asleep, calendar: zurich)
        XCTAssertEqual(effects.map(\.noteId), ["CAFFEINE", "EXERCISE"])
        XCTAssertEqual(effects[0].delta, 1 - 3.5, accuracy: 1e-9)
        XCTAssertEqual(effects[0].avgAsleepWith!, 360, accuracy: 1e-9)
        XCTAssertEqual(effects[0].avgAsleepWithout!, 450, accuracy: 1e-9)
        XCTAssertEqual(effects[1].delta, 4 - 2, accuracy: 1e-9)
    }

    func testSameNightDateCountsOnce() {
        // Zwei Wecker am selben Morgen: eine Nacht, das Befinden der zuletzt geweckten zählt (wie Kotlin).
        let first = night("2026-09-01T06:00", feeling: 1)
        let second = NightRecord(alarmId: "b", windowStart: first.windowStart.addingTimeInterval(1800), target: first.target,
                                 wokeAt: first.wokeAt.addingTimeInterval(1800), reason: .lightSleep, snoozes: 0, epochs: [], feeling: 5)
        var nights = [first, second]
        for day in 2...6 { nights.append(night("2026-09-0\(day)T06:00", feeling: 3)) }
        let notes = ["2026-08-31": ["NAP"], "2026-09-01": ["NAP"], "2026-09-02": ["NAP"]]
        let effects = NoteEffect.compute(nights: nights, notes: notes, calendar: zurich)
        XCTAssertEqual(effects.count, 1)
        XCTAssertEqual(effects[0].nightsWith, 3)
        XCTAssertEqual(effects[0].nightsWithout, 3)
        XCTAssertEqual(effects[0].avgFeelingWith, (5.0 + 3 + 3) / 3, accuracy: 1e-9)
    }

    func testSmallDeltaIsNotVisible() {
        let e = NoteEffect(noteId: "NAP", nightsWith: 3, nightsWithout: 3, avgFeelingWith: 3.4, avgFeelingWithout: 3, avgAsleepWith: nil, avgAsleepWithout: nil)
        XCTAssertFalse(e.isVisible)
        let f = NoteEffect(noteId: "NAP", nightsWith: 3, nightsWithout: 3, avgFeelingWith: 3.5, avgFeelingWithout: 3, avgAsleepWith: nil, avgAsleepWithout: nil)
        XCTAssertTrue(f.isVisible)
    }

    // MARK: Schlafziel

    func testSleepGoal() {
        XCTAssertEqual(SleepGoal.defaultMinutes, 450)
        XCTAssertEqual(SleepGoal.clamp(300), 360)
        XCTAssertEqual(SleepGoal.clamp(700), 600)
        XCTAssertEqual(SleepGoal.clamp(457), 450)
        XCTAssertEqual(SleepGoal.clamp(458), 465)
        XCTAssertTrue(SleepGoal.reached(asleepMinutes: 450, goalMinutes: 450))
        XCTAssertFalse(SleepGoal.reached(asleepMinutes: 449, goalMinutes: 450))
    }

    func testWeekStatusCountsLastSevenNights() {
        var list: [(date: String, asleepMinutes: Int)] = (1...9).map { ("2026-10-0\($0)", $0 % 2 == 0 ? 480 : 400) }
        // Zweiter Eintrag zum selben Datum: der längere zählt.
        list.append(("2026-10-09", 470))
        let status = SleepGoal.weekStatus(list, goalMinutes: 450)
        // Nächte 3–9: gerade (4, 6, 8) und die 9 mit 470.
        XCTAssertEqual(status.total, 7)
        XCTAssertEqual(status.reached, 4)
        XCTAssertEqual(SleepGoal.weekStatus([], goalMinutes: 450).total, 0)
    }

    // MARK: Bettzeit

    func testBedtimeAndReminder() {
        let target = at("2026-10-29T06:00")
        XCTAssertEqual(Bedtime.bedtime(nextAlarmTarget: target, goalMinutes: 450), at("2026-10-28T22:30"))
        XCTAssertEqual(Bedtime.reminderAt(nextAlarmTarget: target, goalMinutes: 450), at("2026-10-28T22:00"))
        XCTAssertEqual(Bedtime.reminderAt(nextAlarmTarget: target, goalMinutes: 480, leadMinutes: 15), at("2026-10-28T21:45"))
    }
}
