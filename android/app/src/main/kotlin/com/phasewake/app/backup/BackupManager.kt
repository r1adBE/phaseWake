package com.phasewake.app.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.phasewake.app.PhaseWakeApp
import com.phasewake.app.core.BackupException
import com.phasewake.app.core.BackupFile
import com.phasewake.app.core.BackupPayload
import com.phasewake.app.core.Codec
import com.phasewake.app.core.CodecException
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.SleepNotes
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Vorschau vor dem Ersetzen: Anzahl Wecker, Nächte und Tage mit Notizen. */
data class BackupPreview(val payload: BackupPayload, val alarms: Int, val nights: Int, val noteDays: Int)

/**
 * Sichern und Wiederherstellen am Handy: Inhalt sammeln ([BackupPayload]), verschlüsseln ([BackupFile]) und
 * über das System speichern; beim Wiederherstellen alles ersetzen und die Wecker an die Uhr schicken.
 * Ohne Geräusch-Ausschnitte und ohne Gesundheitsdaten; das Befinden zu Nächten aus anderen Apps ist dabei.
 */
object BackupManager {
    private const val TAG = "BackupManager"
    private val random = SecureRandom()

    /** Schreibt eine verschlüsselte Sicherung nach [uri]. @return ob es geklappt hat. */
    suspend fun create(context: Context, app: PhaseWakeApp, uri: Uri, password: String): Boolean = try {
        val (notes, custom) = app.notes.raw()
        val (items, ticks) = app.routine.raw()
        val payload = BackupPayload(
            createdAt = System.currentTimeMillis(),
            alarms = Codec.encodeAlarms(app.alarms.current().alarms),
            nights = app.nights.load().map(Codec::encodeNight),
            notes = notes,
            customNotes = custom,
            routineItems = items,
            routineTicks = ticks,
            settings = app.settings.backupSettings(),
            healthFeelings = app.notes.rawHealthFeelings(),
        )
        val sealed = withContext(Dispatchers.Default) {
            val salt = ByteArray(BackupFile.SALT_BYTES).also(random::nextBytes)
            val nonce = ByteArray(BackupFile.NONCE_BYTES).also(random::nextBytes)
            BackupFile.encrypt(payload.toJson().encodeToByteArray(), password, salt, nonce)
        }
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(sealed) } ?: throw IOException("Ziel nicht beschreibbar")
        }
        app.diag.log(DiagKind.OTHER, "backup saved")
        true
    } catch (e: Exception) {
        // Jeder Fehler (Schreiben, Passwort zu kurz, Anbieter der Verschlüsselung) meldet «nicht gespeichert», statt
        // die App über den Bereich abstürzen zu lassen; nur der Abbruch der Coroutine geht weiter.
        if (e is CancellationException) throw e
        Log.w(TAG, "Sicherung nicht geschrieben", e)
        false
    }

    /**
     * Liest die gewählte Datei [uri], höchstens [BackupFile.MAX_FILE_BYTES] ([BackupFile.readLimited]).
     * @return Inhalt, oder `null`, wenn sie nicht lesbar oder zu gross ist (Meldung `restore_unreadable`, nichts geändert).
     */
    suspend fun read(context: Context, uri: Uri): ByteArray? = try {
        withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { BackupFile.readLimited(it) } }
    } catch (e: IOException) {
        Log.w(TAG, "Sicherung nicht lesbar", e)
        null
    } catch (e: SecurityException) {
        // Zugriff auf die Datei entzogen (Anbieter der Datei): wie nicht lesbar.
        Log.w(TAG, "Kein Zugriff auf die Sicherung", e)
        null
    }

    /**
     * Entfernt die Datei [uri], die das System beim Speichern schon angelegt hat, wenn doch nichts hineinkommt
     * (Passwort-Abfrage abgebrochen): Sonst bliebe eine leere `.pwbak` liegen. Kann der Anbieter nicht löschen, bleibt sie.
     */
    suspend fun discard(context: Context, uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } catch (e: FileNotFoundException) {
                Log.w(TAG, "Leere Sicherung schon weg", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Leere Sicherung nicht löschbar", e)
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "Anbieter kann nicht löschen", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Keine Dokument-Adresse", e)
            }
        }
    }

    /**
     * Entschlüsselt den Inhalt [bytes] einer Sicherung ([read]). Falsches Passwort, fremde oder kaputte Datei:
     * [BackupException] (nichts wird geändert).
     */
    suspend fun open(bytes: ByteArray, password: String): BackupPreview {
        val plain = withContext(Dispatchers.Default) { BackupFile.decrypt(bytes, password) }
        val payload = try {
            BackupPayload.fromJson(plain.decodeToString())
        } catch (e: CodecException) {
            throw BackupException(BackupException.Reason.UNREADABLE, "Inhalt unlesbar", e)
        }
        val alarms = try {
            Codec.decodeAlarms(payload.alarms).size
        } catch (e: CodecException) {
            throw BackupException(BackupException.Reason.UNREADABLE, "Wecker unlesbar", e)
        }
        return BackupPreview(payload, alarms, payload.nights.size, SleepNotes.decode(payload.notes).size)
    }

    /** Ersetzt alle Daten durch [payload] und gleicht mit der Uhr ab. Unlesbare Nächte fallen weg. */
    suspend fun restore(app: PhaseWakeApp, payload: BackupPayload) {
        val nights = payload.nights.mapNotNull { text ->
            try {
                Codec.decodeNight(text)
            } catch (e: CodecException) {
                Log.w(TAG, "Nacht in der Sicherung unlesbar, übersprungen", e)
                null
            }
        }
        app.nights.replaceAll(nights)
        app.notes.restore(payload.notes, payload.customNotes, payload.healthFeelings)
        app.routine.restore(payload.routineItems, payload.routineTicks)
        app.settings.restore(payload.settings)
        val snapshot = app.alarms.replaceAll(Codec.decodeAlarms(payload.alarms))
        app.diag.log(DiagKind.SYNC, "restored ${snapshot.alarms.size} alarms")
        app.sync.publishAlarms(snapshot)
        app.holidays.refresh(force = true)
        // Plant auch Bettzeit-Erinnerung und Wochenrückblick nach den neuen Einstellungen.
        app.backup.reschedule()
    }
}
