import XCTest
import PhaseWakeCore

/// Sicherungsdatei und ihr JSON; der Testvektor kommt von `make-backup-vector.py`. Gegenstück zu `BackupTest` (Kotlin).
final class BackupTests: XCTestCase {
    private let salt = Data((0..<16).map { UInt8($0) })
    private let nonce = Data((0..<12).map { UInt8($0 + 100) })

    private func testdata(_ name: String) throws -> String {
        try String(contentsOf: NightFixtures.directory.appendingPathComponent(name), encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var payload: BackupPayload {
        let alarm = try! AlarmSpec(id: "a", hour: 6, minute: 30, days: [.monday], label: "Früh \"zeitig\"\n")
        return BackupPayload(
            createdAt: 1_793_260_800_000, alarms: Codec.encodeAlarms([alarm]), nights: ["phasewake-night 1\na|1|2|3|DEADLINE|0|\n\n"],
            notes: "2026-10-28|caffeine", customNotes: "custom:1|Tee\twarm\u{01}", routineItems: nil, routineTicks: "",
            settings: BackupSettings(sleepGoal: 420, bedtimeReminder: true, theme: "LIGHT", holidayCountry: "DE", holidayRegion: "BY")
        )
    }

    func testVectorFromPythonDecrypts() throws {
        let file = try XCTUnwrap(Data(base64Encoded: try testdata("backup-v1.pwbak.b64")))
        let password = try testdata("backup-v1.password")
        let plain = try BackupFile.decrypt(file, password: password)
        let text = String(decoding: plain, as: UTF8.self)
        XCTAssertEqual(text, try testdata("backup-v1.json"))
        let p = try BackupPayload.fromJson(text)
        XCTAssertEqual(p.createdAt, 1_793_260_800_000)
        let alarms = try Codec.decodeAlarms(p.alarms)
        XCTAssertEqual(alarms.count, 2)
        XCTAssertEqual(alarms.first?.label, "Arbeit | früh")
        XCTAssertEqual(alarms.first?.skipUntil, 1_793_338_200_000)
        XCTAssertEqual(alarms.first?.skipHolidays, true)
        XCTAssertEqual(try Codec.decodeNight(try XCTUnwrap(p.nights.first)).reason, .lightSleep)
        XCTAssertEqual(SleepNotes.decodeCustom(p.customNotes).first?.name, "Spät gegessen «Pizza»")
        XCTAssertEqual(Routine.decodeItems(p.routineItems).first { $0.id == "custom:9a" }?.text, "Tee \"Kamille\"")
        XCTAssertEqual(p.settings, BackupSettings(sleepGoal: 480, bedtimeReminder: true, weeklyReview: false, soundRecDefault: true,
                                                  theme: "DARK", holidayCountry: "CH", holidayRegion: "BE"))
        // Dasselbe mit den Werten des Vektors verschlüsselt ergibt dieselbe Datei.
        XCTAssertEqual(try BackupFile.encrypt(plain, password: password, salt: salt, nonce: nonce), file)
    }

    func testUnicodePasswordIsNormalised() throws {
        // Passwort-Datei absichtlich in NFD («a» + Trema), Schlüssel aus NFC abgeleitet.
        let file = try XCTUnwrap(Data(base64Encoded: try testdata("backup-v1-nfc.pwbak.b64")))
        let nfd = try testdata("backup-v1-nfc.password")
        // Swift vergleicht Zeichenketten kanonisch; die Codepunkte zeigen, dass die Datei wirklich NFD ist.
        XCTAssertEqual(Array(nfd.unicodeScalars), Array("Mondlicht-a\u{0308}€-2026".unicodeScalars))
        let expected = try testdata("backup-v1.json")
        XCTAssertEqual(String(decoding: try BackupFile.decrypt(file, password: nfd), as: UTF8.self), expected)
        XCTAssertEqual(String(decoding: try BackupFile.decrypt(file, password: "Mondlicht-\u{e4}€-2026"), as: UTF8.self), expected)
        XCTAssertEqual(try BackupFile.encrypt(Data(expected.utf8), password: "Mondlicht-\u{e4}€-2026", salt: salt, nonce: nonce), file)
    }

    func testShortPasswordIsRefused() {
        XCTAssertFalse(BackupFile.passwordOk(""))
        XCTAssertFalse(BackupFile.passwordOk("1234567"))
        XCTAssertTrue(BackupFile.passwordOk("12345678"))
        // «ä» als NFD (zwei Codepunkte) zählt als ein Zeichen: «abcdefä» hat 7.
        XCTAssertFalse(BackupFile.passwordOk("abcdefa\u{0308}"))
        XCTAssertTrue(BackupFile.passwordOk("abcdefga\u{0308}"))
        XCTAssertThrowsError(try BackupFile.encrypt(Data([1]), password: "", salt: salt, nonce: nonce))
        XCTAssertThrowsError(try BackupFile.encrypt(Data([1]), password: "1234567", salt: salt, nonce: nonce))
        XCTAssertGreaterThanOrEqual(BackupFile.maxFileBytes, 1_000_000)
    }

    func testRoundTrip() throws {
        let sealed = try BackupFile.encrypt(Data(payload.toJson().utf8), password: "geheim123", salt: salt, nonce: nonce)
        XCTAssertEqual(sealed.prefix(5), BackupFile.magic)
        let back = try BackupFile.decrypt(sealed, password: "geheim123")
        XCTAssertEqual(try BackupPayload.fromJson(String(decoding: back, as: UTF8.self)), payload)
    }

    func testBackupKeepsClearedAt() throws {
        var p = payload
        XCTAssertTrue(p.toJson().contains(#""historyClearedAt":null"#))
        XCTAssertNil(try BackupPayload.fromJson(p.toJson()).settings.historyClearedAt)
        p.settings.historyClearedAt = 1_790_000_000_000
        XCTAssertEqual(try BackupPayload.fromJson(p.toJson()).settings.historyClearedAt, 1_790_000_000_000)
    }

    func testWrongPasswordOrChangedFile() throws {
        let sealed = try BackupFile.encrypt(Data("{}".utf8), password: "richtig1", salt: salt, nonce: nonce)
        XCTAssertThrowsError(try BackupFile.decrypt(sealed, password: "falsch12")) { XCTAssertEqual(($0 as? BackupError)?.reason, .wrongPassword) }
        var changed = sealed
        changed[changed.count - 1] = changed[changed.count - 1] &+ 1
        XCTAssertThrowsError(try BackupFile.decrypt(changed, password: "richtig1")) { XCTAssertEqual(($0 as? BackupError)?.reason, .wrongPassword) }
    }

    func testForeignFileIsRejected() {
        XCTAssertThrowsError(try BackupFile.decrypt(Data("hallo".utf8), password: "x")) { XCTAssertEqual(($0 as? BackupError)?.reason, .notABackup) }
        XCTAssertThrowsError(try BackupFile.decrypt(Data(repeating: 1, count: 60), password: "x")) { XCTAssertEqual(($0 as? BackupError)?.reason, .notABackup) }
        XCTAssertThrowsError(try BackupFile.encrypt(Data(), password: "x", salt: Data(count: 3), nonce: nonce))
    }

    func testJsonIsTolerant() throws {
        let minimal = try BackupPayload.fromJson(#"{"format":"phasewake-backup","version":2,"alarms":"phasewake-alarms 1\n","neu":[1,2.5,{"x":null}]}"#)
        XCTAssertEqual(minimal.createdAt, 0)
        XCTAssertEqual(minimal.nights, [])
        XCTAssertNil(minimal.routineItems)
        XCTAssertEqual(minimal.settings, BackupSettings())
        let escaped = try BackupPayload.fromJson(#" { "format" : "phasewake-backup", "alarms" : "aä\/\b\f\r\t" , "nights" : [ ] , "settings" : { } } "#)
        XCTAssertEqual(escaped.alarms, "aä/\u{08}\u{0C}\r\t")
    }

    func testJsonRejectsBrokenOrForeign() {
        let bad = [
            "", "[]", #"{"format":"anders","alarms":""}"#, #"{"format":"phasewake-backup"}"#,
            #"{"format":"phasewake-backup","alarms":"x""#, #"{"format":"phasewake-backup","alarms":"x"} x"#,
            #"{"format":"phasewake-backup","alarms":"\q"}"#, #"{"format":"phasewake-backup","alarms":"\u12"}"#,
            #"{"format":"phasewake-backup","alarms":"\uZZZZ"}"#, #"{"format":"phasewake-backup","alarms":"x","n":tru}"#,
            #"{"format":"phasewake-backup","alarms":"x","n":-}"#, #"{"format":"phasewake-backup" "alarms":"x"}"#,
            #"{format:1}"#, #"{"format":"phasewake-backup","alarms":"x","n":[1 2]}"#, #"{"a":"\""#,
        ]
        for text in bad { XCTAssertThrowsError(try BackupPayload.fromJson(text), text) }
    }
}
