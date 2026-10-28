package com.phasewake.app.ui.history

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.phasewake.app.R
import com.phasewake.app.core.NightHistory
import com.phasewake.app.core.NightRecord
import java.io.File

/** Teilt den Verlauf als anonyme CSV-Datei (Format siehe [NightHistory.exportCsv]). */
object Export {
    fun share(context: Context, nights: List<NightRecord>) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "phasewake-nights.csv")
        file.writeText(NightHistory.exportCsv(nights))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.export", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.history_export_subject))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
