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
/// `MAGIC | Salz (16) | Nonce (12) | Geheimtext | Prüfsumme (16)`.
///
/// Schlüssel: PBKDF2-HMAC-SHA256 über das Passwort (Unicode NFC, UTF-8) mit `iterations` Runden, 256 Bit (CommonCrypto).
/// NFC, damit «ü» als ein Zeichen oder als «u» plus Trema (je nach Tastatur) denselben Schlüssel ergibt.
/// Verfahren: AES-256-GCM ohne zusätzliche Daten (CryptoKit). 210 000 Runden: Empfehlung von OWASP (2023)
/// für PBKDF2-SHA256. Identisch zu `BackupFile` (Kotlin).
public enum BackupFile {
    /// Kopf der Datei «PWBK1», Formatversion 1.
    public static let magic = Data("PWBK1".utf8)
    /// Länge des Salzes in Byte.
    public static let saltBytes = 16
    /// Länge der Nonce in Byte (Standard für GCM).
    public static let nonceBytes = 12
    /// Länge der Prüfsumme (GCM-Tag) in Byte.
    public static let tagBytes = 16
    /// Runden von PBKDF2.
    public static let iterations = 210_000
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

    /// Verschlüsselt `plain` mit `password`; `salt` (16 Byte) und `nonce` (12 Byte) kommen vom Aufrufer
    /// (zufällig, im Test fest). Nie eine Sicherung mit zu kurzem oder leerem Passwort.
    /// - Throws: ``BackupError`` (`unreadable`) bei zu kurzem Passwort (`passwordOk`), falscher Länge von Salz oder
    ///   Nonce oder einem Fehler der Verschlüsselung.
    public static func encrypt(_ plain: Data, password: String, salt: Data, nonce: Data) throws -> Data {
        guard salt.count == saltBytes, nonce.count == nonceBytes else { throw BackupError(.unreadable, "Salz oder Nonce mit falscher Länge") }
        guard passwordOk(password) else { throw BackupError(.unreadable, "Passwort zu kurz") }
        do {
            let key = try deriveKey(password, salt: salt)
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

    /// Entschlüsselt eine Datei aus `encrypt`.
    /// - Throws: ``BackupError`` mit `notABackup` (fremde Datei) oder `wrongPassword` (Passwort falsch, Datei verändert).
    public static func decrypt(_ file: Data, password: String) throws -> Data {
        let bytes = [UInt8](file)
        let head = magic.count + saltBytes
        guard bytes.count >= head + nonceBytes + tagBytes, Data(bytes[0..<magic.count]) == magic else {
            throw BackupError(.notABackup, "Keine Phase-Wake-Sicherung")
        }
        let salt = Data(bytes[magic.count..<head])
        do {
            let box = try AES.GCM.SealedBox(combined: Data(bytes[head...]))
            return try AES.GCM.open(box, using: deriveKey(password, salt: salt))
        } catch {
            throw BackupError(.wrongPassword, "Passwort falsch oder Datei verändert: \(error)")
        }
    }

    /// Unicode NFC (wie `Normalizer.Form.NFC` in Kotlin).
    private static func normalize(_ password: String) -> String { password.precomposedStringWithCanonicalMapping }

    /// PBKDF2-HMAC-SHA256 über NFC(Passwort) als UTF-8, 32 Byte.
    private static func deriveKey(_ password: String, salt: Data) throws -> SymmetricKey {
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
