package com.phasewake.app.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.text.Normalizer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Warum eine Sicherung nicht zu öffnen ist. */
class BackupException(
    /** Grund, für die passende Meldung. */
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Gründe, eine Sicherung abzulehnen. */
    enum class Reason {
        /** Keine Phase-Wake-Sicherung (Kopf fehlt oder Datei zu kurz). */
        NOT_A_BACKUP,

        /** Passwort falsch oder Datei verändert (Prüfsumme stimmt nicht). */
        WRONG_PASSWORD,

        /** Entschlüsselt, aber der Inhalt ist unlesbar. */
        UNREADABLE,
    }
}

/**
 * Verschlüsselte Sicherungsdatei (`.pwbak`), auf Android und Apple gleich:
 * `MAGIC | Salz (16) | Nonce (12) | Geheimtext | Prüfsumme (16)`.
 *
 * Schlüssel: PBKDF2-HMAC-SHA256 über das Passwort (Unicode NFC, UTF-8) mit [ITERATIONS] Runden, 256 Bit. NFC,
 * damit «ü» als ein Zeichen oder als «u» plus Trema (je nach Tastatur) denselben Schlüssel ergibt. Verfahren:
 * AES-256-GCM ohne zusätzliche Daten. 210 000 Runden: Empfehlung von OWASP (2023) für PBKDF2-SHA256.
 */
object BackupFile {
    /** Kopf der Datei, Formatversion 1. */
    val MAGIC: ByteArray = "PWBK1".toByteArray(StandardCharsets.US_ASCII)

    /** Länge des Salzes in Byte. */
    const val SALT_BYTES: Int = 16

    /** Länge der Nonce in Byte (Standard für GCM). */
    const val NONCE_BYTES: Int = 12

    /** Länge der Prüfsumme (GCM-Tag) in Byte. */
    const val TAG_BYTES: Int = 16

    /** Runden von PBKDF2. */
    const val ITERATIONS: Int = 210_000

    /** Schlüssellänge in Bit (AES-256). */
    const val KEY_BITS: Int = 256

    /** Kürzestes erlaubtes Passwort in Zeichen (Unicode-Codepunkte nach NFC, siehe [passwordOk]). */
    const val MIN_PASSWORD: Int = 8

    /**
     * Grösste Datei, die das Wiederherstellen liest: 20 MiB. 90 Nächte mit Verlauf ergeben weit unter 1 MB; eine
     * versehentlich gewählte grosse Datei (Video) wird so abgelehnt statt ganz in den Speicher gelesen.
     */
    const val MAX_FILE_BYTES: Long = 20L * 1024 * 1024

    /** Lesepuffer von [readLimited] in Byte. */
    private const val READ_BUFFER: Int = 64 * 1024

    /**
     * Liest [input] ganz, aber höchstens [limit] Byte (Vorgabe [MAX_FILE_BYTES]). `null`, wenn die Datei grösser
     * ist: Sie wird nicht ganz in den Speicher gelesen, und die App meldet «Datei nicht lesbar» (`restore_unreadable`),
     * nicht «Falsches Passwort». Fehler beim Lesen ([java.io.IOException]) gehen an den Aufrufer.
     * Apple prüft stattdessen die Dateigrösse vorab (Dateiattribut), mit derselben Grenze und derselben Meldung.
     */
    fun readLimited(input: InputStream, limit: Long = MAX_FILE_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Ob [password] lang genug ist: mindestens [MIN_PASSWORD] Unicode-Codepunkte nach NFC. Gleiche Zählung in Swift. */
    fun passwordOk(password: String): Boolean {
        val nfc = normalize(password)
        return nfc.codePointCount(0, nfc.length) >= MIN_PASSWORD
    }

    /**
     * Verschlüsselt [plain] mit [password]; [salt] und [nonce] kommen vom Aufrufer (zufällig, im Test fest).
     * Ein zu kurzes Passwort ([passwordOk]) wirft [IllegalArgumentException]: Nie eine Sicherung mit leerem Passwort.
     */
    fun encrypt(plain: ByteArray, password: String, salt: ByteArray, nonce: ByteArray): ByteArray {
        require(salt.size == SALT_BYTES && nonce.size == NONCE_BYTES) { "Salz oder Nonce mit falscher Länge" }
        require(passwordOk(password)) { "Passwort zu kurz" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BYTES * 8, nonce))
        return MAGIC + salt + nonce + cipher.doFinal(plain)
    }

    /** Entschlüsselt eine Datei aus [encrypt]. Fremde Datei oder falsches Passwort: [BackupException]. */
    fun decrypt(file: ByteArray, password: String): ByteArray {
        val head = MAGIC.size + SALT_BYTES + NONCE_BYTES
        if (file.size < head + TAG_BYTES || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupException(BackupException.Reason.NOT_A_BACKUP, "Keine Phase-Wake-Sicherung")
        }
        val salt = file.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val nonce = file.copyOfRange(MAGIC.size + SALT_BYTES, head)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.doFinal(file, head, file.size - head)
        } catch (e: AEADBadTagException) {
            throw BackupException(BackupException.Reason.WRONG_PASSWORD, "Passwort falsch oder Datei verändert", e)
        } catch (e: GeneralSecurityException) {
            throw BackupException(BackupException.Reason.WRONG_PASSWORD, "Entschlüsseln fehlgeschlagen", e)
        }
    }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun normalize(password: String): String = Normalizer.normalize(password, Normalizer.Form.NFC)

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(normalize(password).toCharArray(), salt, ITERATIONS, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}

/** Einstellungen in der Sicherung (nur das Handy; Uhr-Einstellungen gibt es nicht). */
data class BackupSettings(
    /** Schlafziel in Minuten. */
    val sleepGoal: Int = SleepGoal.DEFAULT_MINUTES,
    /** Bettzeit-Erinnerung an. */
    val bedtimeReminder: Boolean = false,
    /** Wochenrückblick an. */
    val weeklyReview: Boolean = false,
    /** Geräusche aufnehmen für neue Nächte. */
    val soundRecDefault: Boolean = false,
    /** Darstellung als Name («SYSTEM», «LIGHT», «DARK»); unbekannt = System. */
    val theme: String = "SYSTEM",
    /** Land der Feiertage (z. B. «CH»); leer = keine. */
    val holidayCountry: String = "",
    /** Region der Feiertage (z. B. «BE»); leer = ganzes Land. */
    val holidayRegion: String = "",
    /**
     * Zeitpunkt von «Verlauf löschen» (ms seit 1970); Nächte aus der Gesundheits-App, die bis dahin enden,
     * bleiben ausgeblendet ([HealthHistory.visible]). `null` = nie gelöscht.
     */
    val historyClearedAt: Long? = null,
)

/**
 * Inhalt einer Sicherung: die gespeicherten Texte in ihren eigenen Formaten (Wecker, Nächte, Notizen,
 * Abendroutine) und die Einstellungen. Ohne Geräusch-Ausschnitte und ohne Gesundheitsdaten.
 */
data class BackupPayload(
    /** Erstellt (ms seit 1970). */
    val createdAt: Long,
    /** Wecker im Format von [Codec.encodeAlarms]. */
    val alarms: String,
    /** Nächte, je eine im Format von [Codec.encodeNight]. */
    val nights: List<String>,
    /** Notizen je Nachtdatum ([SleepNotes.encode]). */
    val notes: String,
    /** Eigene Notizen ([SleepNotes.encodeCustom]). */
    val customNotes: String,
    /** Punkte der Abendroutine ([Routine.encodeItems]); `null` = nie geändert (Vorschläge). */
    val routineItems: String?,
    /** Haken der Abendroutine ([Routine.encodeTicks]). */
    val routineTicks: String,
    /** Einstellungen. */
    val settings: BackupSettings,
) {
    /** Als JSON, siehe [BackupJson]. */
    fun toJson(): String = BackupJson.write(this)

    /** Lesen aus JSON. */
    companion object {
        /** Liest [toJson]; fremdes Format wirft [CodecException]. */
        fun fromJson(text: String): BackupPayload = BackupJson.read(text)
    }
}
