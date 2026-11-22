import XCTest
import PhaseWakeCore

/// Ungültige Werte auf gültig begrenzen (``ClampedSpec``). Gegenstück zu `ClampedSpecTest` (Kotlin), gleiche Fälle.
final class ClampedSpecTests: XCTestCase {

    func testSharedVectors() throws {
        // Gemeinsame Fälle mit Kotlin (shared/testdata/alarm-clamp.vectors); Spalte 3 = Apple (Fenster bis 30).
        let text = try String(contentsOf: NightFixtures.directory.appendingPathComponent("alarm-clamp.vectors"),
                              encoding: .utf8)
        let lines = text.components(separatedBy: "\n").filter { !$0.isEmpty && !$0.hasPrefix("#") }
        for line in lines {
            let p = line.components(separatedBy: "\t")
            XCTAssertEqual(p.count, 3, line)
            guard p.count == 3 else { continue }
            let input = p[0].components(separatedBy: "|")
            let expected = p[2].components(separatedBy: "|")
            XCTAssertEqual(input.count, 5, line)
            XCTAssertEqual(expected.count, 5, line)
            guard input.count == 5, expected.count == 5,
                  let hour = Int(input[1]), let minute = Int(input[2]), let window = Int(input[3]) else {
                XCTFail("Zeile unlesbar: \(line)")
                continue
            }
            let clamped = ClampedSpec(id: input[0], hour: hour, minute: minute, windowMinutes: window,
                                      sleepDurationMinutes: input[4] == "-" ? nil : Int(input[4]))
            let spec = clamped.spec
            if input[0].trimmingCharacters(in: .whitespaces).isEmpty {
                XCTAssertFalse(spec.id.trimmingCharacters(in: .whitespaces).isEmpty, line)
                XCTAssertNotEqual(spec.id, input[0], line)
            } else {
                XCTAssertEqual(spec.id, input[0], line)
            }
            XCTAssertEqual(spec.hour, Int(expected[0]), line)
            XCTAssertEqual(spec.minute, Int(expected[1]), line)
            XCTAssertEqual(spec.windowMinutes, Int(expected[2]), line)
            XCTAssertEqual(spec.sleepDurationMinutes, expected[3] == "-" ? nil : Int(expected[3]), line)
            XCTAssertEqual(clamped.diagLine, expected[4] == "-" ? nil : expected[4], line)
            XCTAssertEqual(clamped.diagLine == nil, clamped.changes.isEmpty, line)
        }
        XCTAssertEqual(lines.count, 25)
    }

    func testValidValuesGiveTheSameSpecAsTheInitializer() throws {
        let clamped = ClampedSpec(
            id: "a", hour: 6, minute: 45, days: [.monday, .saturday], windowMinutes: 30, enabled: false,
            phone: .vibrate, watchSound: true, sleepDurationMinutes: 450, sound: "raw:wake_birds", label: "Arbeit",
            sunrise: .window, wakeTask: .math, sunriseDevice: .both, skipUntil: 1_794_200_000_000, skipHolidays: true
        )
        let direct = try AlarmSpec(
            id: "a", hour: 6, minute: 45, days: [.monday, .saturday], windowMinutes: 30, enabled: false,
            phone: .vibrate, watchSound: true, sleepDurationMinutes: 450, sound: "raw:wake_birds", label: "Arbeit",
            sunrise: .window, wakeTask: .math, sunriseDevice: .both, skipUntil: 1_794_200_000_000, skipHolidays: true
        )
        XCTAssertEqual(clamped.spec, direct)
        XCTAssertEqual(clamped.changes, [])
        XCTAssertNil(clamped.diagLine)
    }

    func testFieldsWithoutRulesAreKeptWhenClamping() {
        let clamped = ClampedSpec(
            id: "a", hour: 25, minute: 0, days: [.friday], windowMinutes: 5, enabled: false, phone: .off,
            label: "Früh", sunrise: .ring, skipUntil: 5, skipHolidays: true
        )
        XCTAssertEqual(clamped.changes, [SpecChange(field: "hour", from: "25", to: "23"),
                                         SpecChange(field: "windowMinutes", from: "5", to: "10")])
        let spec = clamped.spec
        XCTAssertEqual(spec.days, [.friday])
        XCTAssertFalse(spec.enabled)
        XCTAssertEqual(spec.phone, .off)
        XCTAssertEqual(spec.label, "Früh")
        XCTAssertEqual(spec.sunrise, .ring)
        XCTAssertEqual(spec.skipUntil, 5)
        XCTAssertTrue(spec.skipHolidays)
    }

    func testWindowAndSleepRules() {
        XCTAssertEqual(ClampedSpec.window(0), AlarmSpec.exactWindow)
        XCTAssertEqual(ClampedSpec.window(-1), AlarmSpec.minWindowMinutes)
        XCTAssertEqual(ClampedSpec.window(Int.max), AlarmSpec.maxWindowMinutes)
        XCTAssertNil(ClampedSpec.sleep(nil))
        XCTAssertEqual(ClampedSpec.sleep(Int.max), AlarmSpec.maxSleepMinutes)
        XCTAssertEqual(ClampedSpec.diagPrefix, "SPEC clamped")
        XCTAssertEqual(SpecChange(field: "windowMinutes", from: "45", to: "30").description, "windowMinutes 45->30")
    }
}
