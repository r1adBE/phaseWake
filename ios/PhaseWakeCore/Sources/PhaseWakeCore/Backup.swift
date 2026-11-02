import CommonCrypto
import CryptoKit
import Foundation

/// Warum eine Sicherung nicht zu öffnen ist. Gleiche Gründe wie `BackupException` (Kotlin).
public struct BackupError: Error, Equatable, Sendable {
    /// Gründe, eine Sicherung abzulehnen.
    public enum Reason: Sendable {
        /// Keine Phase-Wake-Sicherung (Kopf fehlt oder Datei zu kurz).
        case notABackup
        /// Passwort falsch oder Datei verändert (Prüfsumme stimmt nicht).
        case wrongPassword
        /// Entschlüsselt, aber der Inhalt ist unlesbar.
        case unreadable
    }

    /// Grund, für die passende Meldung.
    public let reason: Reason
    /// Beschreibung nur für Logs, nicht für die Oberfläche.
    public let message: String

    /// Erzeugt einen Fehler.
    public init(_ reason: Reason, _ message: String) {
        self.reason = reason
        self.message = message
    }
}

/// Verschlüsselte Sicherungsdatei (`.pwbak`), auf Apple und Android gleich:
/// `Kopf | Salz (16) | Nonce (12) | Geheimtext | Prüfsumme (16)`.
///
/// Schlüssel: PBKDF2-HMAC-SHA256 über das Passwort (Unicode NFC, UTF-8), 256 Bit (CommonCrypto). NFC, damit «ü» als
/// ein Zeichen oder als «u» plus Trema (je nach Tastatur) denselben Schlüssel ergibt. Verfahren: AES-256-GCM ohne
/// zusätzliche Daten (CryptoKit). Die Runden stehen nicht in der Datei, der Kopf trägt die Formatversion:
/// - «PWBK2» (`magic`, geschrieben): `iterations` = 600 000 Runden, die Zahl von OWASP (2023) für PBKDF2-HMAC-SHA256.
/// - «PWBK1» (`magicV1`, nur gelesen): `iterationsV1` = 210 000 Runden (ältere Sicherungen bleiben lesbar).
/// Identisch zu `BackupFile` (Kotlin).
public enum BackupFile {
    /// Kopf der Datei «PWBK2», Formatversion 2 (wird geschrieben).
    public static let magic = Data("PWBK2".utf8)
    /// Kopf «PWBK1» der Formatversion 1 (nur noch gelesen).
    public static let magicV1 = Data("PWBK1".utf8)
    /// Länge des Salzes in Byte.
    public static let saltBytes = 16
    /// Länge der Nonce in Byte (Standard für GCM).
    public static let nonceBytes = 12
    /// Länge der Prüfsumme (GCM-Tag) in Byte.
    public static let tagBytes = 16
    /// Runden von PBKDF2 für `magic` (Formatversion 2).
    public static let iterations = 600_000
    /// Runden von PBKDF2 für `magicV1` (Formatversion 1).
    public static let iterationsV1 = 210_000
    /// Schlüssellänge in Bit (AES-256).
    public static let keyBits = 256
    /// Kürzestes erlaubtes Passwort in Zeichen (Unicode-Codepunkte nach NFC, siehe `passwordOk`).
    public static let minPassword = 8
    /// Grösste Datei in Byte, die das Wiederherstellen liest: 20 MiB. 90 Nächte mit Verlauf ergeben weit unter 1 MB;
    /// eine versehentlich gewählte grosse Datei (Video) wird so abgelehnt statt ganz in den Speicher gelesen.
    public static let maxFileBytes: Int64 = 20 * 1024 * 1024

    /// Ob `password` lang genug ist: mindestens `minPassword` Unicode-Codepunkte nach NFC (gleiche Zählung wie Kotlin,
    /// nicht Graphem-Gruppen).
    public static func passwordOk(_ password: String) -> Bool {
        normalize(password).unicodeScalars.count >= minPassword
    }

    /// Verschlüsselt `plain` mit `password` im Format `magic` (`iterations` Runden); `salt` (16 Byte) und `nonce`
    /// (12 Byte) kommen vom Aufrufer (zufällig, im Test fest). Nie eine Sicherung mit zu kurzem oder leerem Passwort.
    /// - Throws: ``BackupError`` (`unreadable`) bei zu kurzem Passwort (`passwordOk`), falscher Länge von Salz oder
    ///   Nonce oder einem Fehler der Verschlüsselung.
    public static func encrypt(_ plain: Data, password: String, salt: Data, nonce: Data) throws -> Data {
        guard salt.count == saltBytes, nonce.count == nonceBytes else { throw BackupError(.unreadable, "Salz oder Nonce mit falscher Länge") }
        guard passwordOk(password) else { throw BackupError(.unreadable, "Passwort zu kurz") }
        do {
            let key = try deriveKey(password, salt: salt, iterations: iterations)
            let sealed = try AES.GCM.seal(plain, using: key, nonce: AES.GCM.Nonce(data: nonce))
            // `combined` ist nonce | Geheimtext | Tag; bei 12 Byte Nonce immer vorhanden.
            guard let combined = sealed.combined else { throw BackupError(.unreadable, "Nonce ohne Standardlänge") }
            return magic + salt + combined
        } catch let error as BackupError {
            throw error
        } catch {
            throw BackupError(.unreadable, "Verschlüsseln fehlgeschlagen: \(error)")
        }
    }

    /// Entschlüsselt eine Datei aus `encrypt` (Kopf `magic`) oder eine ältere mit `magicV1`.
    /// - Throws: ``BackupError`` mit `notABackup` (fremde Datei) oder `wrongPassword` (Passwort falsch, Datei verändert).
    public static func decrypt(_ file: Data, password: String) throws -> Data {
        let bytes = [UInt8](file)
        let head = magic.count + saltBytes
        let fileMagic = bytes.count >= magic.count ? Data(bytes[0..<magic.count]) : Data()
        let rounds: Int
        if fileMagic == magic {
            rounds = iterations
        } else if fileMagic == magicV1 {
            rounds = iterationsV1
        } else {
            rounds = 0
        }
        guard bytes.count >= head + nonceBytes + tagBytes, rounds > 0 else {
            throw BackupError(.notABackup, "Keine Phase-Wake-Sicherung")
        }
        let salt = Data(bytes[magic.count..<head])
        do {
            let box = try AES.GCM.SealedBox(combined: Data(bytes[head...]))
            return try AES.GCM.open(box, using: deriveKey(password, salt: salt, iterations: rounds))
        } catch {
            throw BackupError(.wrongPassword, "Passwort falsch oder Datei verändert: \(error)")
        }
    }

    /// Unicode NFC (wie `Normalizer.Form.NFC` in Kotlin).
    private static func normalize(_ password: String) -> String { password.precomposedStringWithCanonicalMapping }

    /// PBKDF2-HMAC-SHA256 über NFC(Passwort) als UTF-8 mit `iterations` Runden, 32 Byte.
    private static func deriveKey(_ password: String, salt: Data, iterations: Int) throws -> SymmetricKey {
        let keyLength = keyBits / 8
        var key = [UInt8](repeating: 0, count: keyLength)
        let passwordBytes = [UInt8](normalize(password).utf8)
        let saltBytes = [UInt8](salt)
        let status = passwordBytes.withUnsafeBufferPointer { pw in
            pw.withMemoryRebound(to: CChar.self) { chars in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2), chars.baseAddress, passwordBytes.count,
                    saltBytes, saltBytes.count, CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    UInt32(iterations), &key, keyLength
                )
            }
        }
        guard status == Int32(kCCSuccess) else { throw BackupError(.unreadable, "Schlüssel nicht ableitbar (\(status))") }
        return SymmetricKey(data: key)
    }
}
