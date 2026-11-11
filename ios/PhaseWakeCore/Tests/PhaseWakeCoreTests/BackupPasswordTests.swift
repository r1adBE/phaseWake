import XCTest
import PhaseWakeCore

/// Regel für das Passwort einer neuen Sicherung. Gegenstück zu `BackupPasswordTest` (Kotlin), gleiche Fälle.
final class BackupPasswordTests: XCTestCase {

    func testTooShort() {
        XCTAssertEqual(BackupPassword.minLength, 10)
        XCTAssertEqual(BackupPassword.problem(""), .tooShort)
        XCTAssertEqual(BackupPassword.problem("Mondlicht"), .tooShort)
        XCTAssertEqual(BackupPassword.problem("123456789"), .tooShort)
        XCTAssertNil(BackupPassword.problem("Mond-Licht"))
        // «ä» als NFD (zwei Codepunkte) zählt als ein Zeichen: «abcdefghä» hat 9, «abcdefghiä» 10.
        XCTAssertEqual(BackupPassword.problem("abcdefgha\u{0308}"), .tooShort)
        XCTAssertNil(BackupPassword.problem("abcdefghia\u{0308}"))
    }

    func testSameCharacter() {
        XCTAssertEqual(BackupPassword.problem("aaaaaaaaaa"), .trivial)
        XCTAssertEqual(BackupPassword.problem("AAAAAAAaaaaa"), .trivial)
        XCTAssertEqual(BackupPassword.problem("0000000000"), .trivial)
        XCTAssertEqual(BackupPassword.problem(String(repeating: "a\u{0308}", count: 10)), .trivial)
    }

    func testRepeatedBlocks() {
        for p in ["1212121212", "abcabcabca", "ABCabcabcABC", "abcdabcdab", "12345123451", "xY!xY!xY!x"] {
            XCTAssertEqual(BackupPassword.problem(p), .trivial, p)
        }
        // Block aus 6 Zeichen: nicht mehr trivial.
        XCTAssertNil(BackupPassword.problem("abcdeXabcdeX"))
        XCTAssertNil(BackupPassword.problem("1212121213"))
    }

    func testSequences() {
        for p in ["1234567890", "0123456789", "7890123456", "9876543210", "0987654321", "abcdefghij", "ABCDEFGHIJK", "jihgfedcba", "klmnopqrstuvwxyz"] {
            XCTAssertEqual(BackupPassword.problem(p), .trivial, p)
        }
        XCTAssertNil(BackupPassword.problem("1234567899"))
        XCTAssertNil(BackupPassword.problem("abcdefghik"))
    }

    func testKeyboardRows() {
        for p in ["qwertyuiop", "QWERTZUIOP", "azertyuiop", "poiuytrewq", "qwertyuiopasdf", "asdfghjklzx", "mnbvcxzlkj"] {
            XCTAssertEqual(BackupPassword.problem(p), .trivial, p)
        }
        XCTAssertNil(BackupPassword.problem("qwertyuiopx"))
        XCTAssertNil(BackupPassword.problem("yxcvbnmasd"))
    }

    func testCommonPasswords() {
        for p in ["password123", "Password123", "PASSWORT1234", "1q2w3e4r5t", "iloveyou12", "1qaz2wsx3edc"] {
            XCTAssertEqual(BackupPassword.problem(p), .trivial, p)
        }
    }

    func testGoodPasswords() {
        for p in ["Mondlicht-2026", "Mondlicht-\u{e4}€-2026", "correct horse", "Velo fährt 7x", "Мой пароль 42"] {
            XCTAssertNil(BackupPassword.problem(p), p)
            XCTAssertTrue(BackupPassword.ok(p), p)
        }
    }

    func testSharedVectors() throws {
        // Gemeinsame Fälle mit Kotlin (shared/testdata/backup-password.vectors). Swift `lowercased()` hängt nicht von der
        // Sprache des Geräts ab (kein türkisches «ı»); Kotlin prüft dasselbe mit Türkisch als Gerätesprache.
        let text = try String(contentsOf: NightFixtures.directory.appendingPathComponent("backup-password.vectors"), encoding: .utf8)
        let lines = text.components(separatedBy: "\n").filter { !$0.isEmpty && !$0.hasPrefix("#") }
        XCTAssertTrue(lines.contains("TRIVIAL\tPASSWORD12"))
        for line in lines {
            let parts = line.split(separator: "\t", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
            XCTAssertEqual(parts.count, 2, line)
            guard parts.count == 2 else { continue }
            let expected: BackupPassword.Problem?
            switch parts[0] {
            case "OK": expected = nil
            case "TOO_SHORT": expected = .tooShort
            case "TRIVIAL": expected = .trivial
            default: XCTFail("unbekanntes Ergebnis \(parts[0])"); continue
            }
            XCTAssertEqual(BackupPassword.problem(parts[1]), expected, parts[1])
        }
        XCTAssertEqual("QWERTYUIOP".lowercased(), "qwertyuiop")
        XCTAssertEqual(BackupPassword.problem("PASSWORD12"), .trivial)
    }
}
