import Foundation

/// Regel für das Passwort einer neuen Sicherung (Entscheid r1AD 10.11.2026): mindestens `minLength` Zeichen
/// (Unicode-Codepunkte nach NFC, nicht Graphem-Gruppen) und kein triviales Muster. Gilt nur beim Anlegen
/// (`BackupFile.encrypt`); ältere Sicherungen mit kürzerem Passwort lassen sich weiter öffnen (`BackupFile.decrypt`
/// prüft nichts davon). Identisch zu `BackupPassword` (Kotlin), mit denselben Tests.
public enum BackupPassword {
    /// Kürzestes erlaubtes Passwort in Zeichen.
    public static let minLength = 10

    /// Warum ein Passwort für eine neue Sicherung nicht genügt.
    public enum Problem: Sendable, Equatable {
        /// Kürzer als `minLength`.
        case tooShort
        /// Leicht zu erraten: ein wiederholter Block (1–5 Zeichen), eine einfache Folge, eine Tastaturreihe oder ein
        /// häufiges Passwort.
        case trivial
    }

    /// Buchstabenreihen der Tastatur (QWERTY, QWERTZ, AZERTY) am Stück, Reihe an Reihe; ein Passwort, das ein Ausschnitt
    /// davon ist (auch rückwärts), gilt als trivial. Die Ziffernreihe deckt `isSequence` ab.
    private static let keyboard = [
        "qwertyuiopasdfghjklzxcvbnm",
        "qwertzuiopasdfghjklyxcvbnm",
        "azertyuiopqsdfghjklmwxcvbn",
    ]

    /// Häufige Passwörter ab `minLength` Zeichen, kleingeschrieben (kürzere scheitern schon an der Länge).
    private static let common: Set<String> = [
        "password12", "password123", "password1234", "password12345",
        "passwort12", "passwort123", "passwort1234", "passwort12345",
        "qwerty1234", "qwerty12345", "qwerty123456", "qwertz1234", "qwertz12345", "azerty1234", "azerty12345",
        "1q2w3e4r5t", "1q2w3e4r5t6y", "q1w2e3r4t5", "q1w2e3r4t5y6", "1qaz2wsx3edc", "zaq12wsxcde3", "123qweasdzxc",
        "123456789a", "a123456789", "1234567890a", "123456789q", "1234567890q", "abc1234567", "abcd123456",
        "1234512345", "1234554321", "1234567891", "1122334455", "1231231231", "123123123123",
        "iloveyou12", "iloveyou123", "admin12345", "admin123456", "welcome123", "letmein123",
        "football12", "football123", "sunshine12", "princess12",
    ]

    /// Problem von `password` für eine neue Sicherung; `nil`, wenn es genügt.
    public static func problem(_ password: String) -> Problem? {
        let nfc = password.precomposedStringWithCanonicalMapping
        if nfc.unicodeScalars.count < minLength { return .tooShort }
        return isTrivial(nfc.lowercased()) ? .trivial : nil
    }

    /// Ob `password` für eine neue Sicherung genügt (`problem` ist `nil`).
    public static func ok(_ password: String) -> Bool { problem(password) == nil }

    private static func isTrivial(_ lower: String) -> Bool {
        let points = lower.unicodeScalars.map { Int($0.value) }
        if isRepeatedBlock(points) { return true }
        if isSequence(points, step: 1) || isSequence(points, step: -1) { return true }
        // Vergleich der Unicode-Skalare (wie Kotlin), nicht der Graphem-Gruppen.
        let scalars = Array(lower.unicodeScalars)
        if keyboard.contains(where: { row in occurs(scalars, in: row) || occurs(scalars, in: String(row.reversed())) }) { return true }
        return common.contains(lower)
    }

    /// Ob `needle` als Folge von Unicode-Skalaren in `row` vorkommt.
    private static func occurs(_ needle: [Unicode.Scalar], in row: String) -> Bool {
        let hay = Array(row.unicodeScalars)
        guard needle.count <= hay.count else { return false }
        return (0...(hay.count - needle.count)).contains { start in Array(hay[start..<(start + needle.count)]) == needle }
    }

    /// Längster Block, dessen Wiederholung als trivial gilt.
    private static let maxBlock = 5

    /// Ob das Passwort ein Block aus 1 bis `maxBlock` Zeichen ist, wiederholt bis zum Ende (der letzte darf angeschnitten
    /// sein): «aaaaaaaaaa», «1212121212», «abcabcabca».
    private static func isRepeatedBlock(_ points: [Int]) -> Bool {
        (1...maxBlock).contains { block in
            block < points.count && (block..<points.count).allSatisfy { points[$0] == points[$0 - block] }
        }
    }

    /// Ob jeder Codepunkt um `step` auf den vorigen folgt (1234…, abcd…, dcba…); Ziffern laufen im Kreis (…8, 9, 0, 1…).
    private static func isSequence(_ points: [Int], step: Int) -> Bool {
        (1..<points.count).allSatisfy { next(points[$0 - 1], step: step) == points[$0] }
    }

    private static func next(_ point: Int, step: Int) -> Int {
        if step > 0 && point == 0x39 { return 0x30 }  // «9» → «0»
        if step < 0 && point == 0x30 { return 0x39 }  // «0» → «9»
        return point + step
    }
}
