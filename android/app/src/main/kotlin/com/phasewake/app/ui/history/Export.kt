package com.phasewake.app.ui.history

import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.phasewake.app.R
import com.phasewake.app.core.NightHistory
import com.phasewake.app.core.NightRecord
import com.phasewake.app.phoneApp
import com.phasewake.app.shared.NightRecording
import java.io.File
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Teilt den Verlauf als CSV-Datei (Format siehe [NightHistory.exportCsv]): ohne Namen, Kennungen und Datumsangaben,
 * aber mit Bewegungs- und Pulsreihen je Nacht, also nicht anonym im engeren Sinn ([ExportDialog] sagt das vorher).
 * Gibt es Aufzeichnungen («Nacht aufzeichnen»), kommt eine zweite Datei im selben Format dazu ([NightRecording.exportCsv]).
 * Die Datei liegt nur kurz im Cache: Die empfangende App liest sie oft erst später (z. B. beim Senden einer Mail), darum
 * wird sie nicht sofort gelöscht, sondern nach [KEEP] ([cleanup]: nach dem Teilen, im täglichen Aufräumen und beim
 * nächsten Start der App).
 */
object Export {
    /** So lange bleibt eine geteilte Datei im Cache. */
    val KEEP: Duration = Duration.ofHours(1)

    private const val DIR = "export"

    /**
     * Schreibt die Datei im Hintergrund (Review K-09: Text bauen und Schreiben nicht auf dem Hauptthread) und öffnet
     * danach die Teilen-Auswahl. Hält die Activity nicht fest: nur die App und der schon gelesene Betreff.
     */
    fun share(context: Context, nights: List<NightRecord>, notes: Map<java.time.LocalDate, List<String>> = emptyMap()) {
        val app = context.phoneApp
        val subject = context.getString(R.string.history_export_subject)
        val zone = java.time.ZoneId.systemDefault()
        app.appScope.launch(Dispatchers.Main) {
            val recordings = app.recordings.load()
            val uris = withContext(Dispatchers.IO) {
                val dir = File(app.cacheDir, DIR).apply { mkdirs() }
                buildList {
                    if (nights.isNotEmpty() || recordings.isEmpty()) {
                        add(write(app, File(dir, "phasewake-nights.csv"), NightHistory.exportCsv(nights, notes, zone)))
                    }
                    if (recordings.isNotEmpty()) add(write(app, File(dir, "phasewake-recordings.csv"), NightRecording.exportCsv(recordings)))
                }
            }
            // Eine Datei wie bisher; mit Aufzeichnungen beide Dateien im selben Teilen.
            val base = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            val send = base
                .setType("text/csv")
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Nach [KEEP] löschen; endet der Prozess vorher, räumen das tägliche Aufräumen oder der nächste Start auf.
            delay(KEEP.toMillis())
            cleanup(app)
        }
    }

    private fun write(context: Context, file: File, text: String): android.net.Uri {
        file.writeText(text)
        return FileProvider.getUriForFile(context, "${context.packageName}.export", file)
    }

    /** Löscht geteilte Dateien, die älter als [olderThan] sind (`Duration.ZERO`: alle). */
    suspend fun cleanup(context: Context, olderThan: Duration = KEEP) = withContext(Dispatchers.IO) {
        val before = System.currentTimeMillis() - olderThan.toMillis()
        File(context.cacheDir, DIR).listFiles().orEmpty().filter { it.lastModified() <= before }.forEach { it.delete() }
    }
}

/**
 * Vor dem Teilen ein Satz dazu, was die Datei enthält: Bewegungs- und Pulsreihen je Nacht. Ohne Namen
 * und Datumsangaben, aber nicht anonym im engeren Sinn – das soll man vor dem Weitergeben wissen.
 */
@Composable
fun ExportDialog(onShare: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(R.string.export_contents_hint)) },
        confirmButton = { TextButton(onClick = onShare) { Text(stringResource(R.string.history_export)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
