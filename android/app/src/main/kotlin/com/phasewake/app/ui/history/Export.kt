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
import java.io.File

/** Teilt den Verlauf als anonyme CSV-Datei (Format siehe [NightHistory.exportCsv]). */
object Export {
    fun share(context: Context, nights: List<NightRecord>, notes: Map<java.time.LocalDate, List<String>> = emptyMap()) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "phasewake-nights.csv")
        file.writeText(NightHistory.exportCsv(nights, notes, java.time.ZoneId.systemDefault()))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.export", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.history_export_subject))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Vor dem Teilen ein Satz dazu, was die Datei enthält: Bewegungs- und Pulsreihen je Nacht. Ohne Namen
 * und Daten, aber nicht anonym im engeren Sinn – das soll man vor dem Weitergeben wissen.
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
