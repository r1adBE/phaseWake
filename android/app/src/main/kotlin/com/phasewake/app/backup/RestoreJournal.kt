package com.phasewake.app.backup

import android.util.AtomicFile
import com.phasewake.app.core.NightRecord
import com.phasewake.app.shared.NightRepository
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wiederherstellen gegen ein Prozessende absichern (Review K-03). Die Nächte liegen in einer eigenen Datei, alles andere
 * im DataStore; beides wird nacheinander geschrieben. Endet der Prozess dazwischen, bliebe eine Mischung.
 *
 * Darum vor dem Tauschen: zuerst eine Kopie der alten Nächte ([copy]), dann eine Marke mit einer Kennung ([begin]),
 * beides im App-Speicher [dir] neben den Nächten. Das `edit` des DataStore schreibt dieselbe Kennung mit. Nach dem
 * Erfolg und dem angestossenen Abgleich mit der Uhr fallen Marke und Kopie weg ([finish]). Liegt beim nächsten Start
 * noch eine Marke, entscheidet [recover]: Kennung im DataStore = der neue Stand ist vollständig (Abgleich nachholen,
 * dann [finish]); sonst die alten Nächte zurück.
 */
internal class RestoreJournal(dir: File) {
    private val marker = AtomicFile(File(dir, "restore.pending"))
    private val copyFile = File(dir, "restore-nights-before.v1")
    private val copy = NightRepository(copyFile)

    /** Ergebnis von [recover]. */
    enum class Outcome {
        /** Kein unterbrochenes Wiederherstellen. */
        NONE,

        /**
         * Der neue Stand war vollständig geschrieben. Die Marke bleibt, bis der Aufrufer den Abgleich mit der Uhr
         * angestossen hat und [finish] aufruft: Endet der Prozess vorher, holt der nächste Start den Abgleich nach.
         */
        FINISHED,

        /** Unterbrochen vor dem DataStore: die alten Nächte sind zurück, der alte Stand gilt. */
        ROLLED_BACK,
    }

    /** Vor dem Tauschen: Kopie der alten Nächte [before], danach die Marke mit [token] (in dieser Reihenfolge). */
    suspend fun begin(token: String, before: List<NightRecord>) {
        copy.replaceAll(before)
        withContext(Dispatchers.IO) {
            val stream = marker.startWrite()
            try {
                stream.write(token.encodeToByteArray())
                marker.finishWrite(stream)
            } catch (e: Exception) {
                marker.failWrite(stream)
                throw e
            }
        }
    }

    /** Kennung eines unterbrochenen Wiederherstellens; `null`, wenn keine Marke liegt. */
    suspend fun pendingToken(): String? = withContext(Dispatchers.IO) {
        try {
            marker.readFully().decodeToString().takeIf { it.isNotBlank() }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    /** Fertig: zuerst die Marke, dann die Kopie löschen (ohne Marke wird eine liegengebliebene Kopie nie gelesen). */
    suspend fun finish() = withContext(Dispatchers.IO) {
        marker.delete()
        AtomicFile(copyFile).delete()
    }

    /**
     * Beim Start nach einem Prozessende: [committedToken] ist die Kennung, die das letzte `edit` des DataStore
     * geschrieben hat. Stimmt sie mit der Marke überein, ist der neue Stand vollständig; sonst kommen die alten Nächte
     * aus der Kopie zurück nach [nights] (der DataStore hat den alten Stand noch). Danach ist aufgeräumt, ausser bei
     * [Outcome.FINISHED]: Dort ruft der Aufrufer [finish] erst nach dem Abgleich mit der Uhr auf (Review N-3).
     */
    suspend fun recover(committedToken: String?, nights: NightRepository): Outcome {
        val token = pendingToken()
        if (token == null) {
            // Prozessende vor der Marke: Der Tausch hatte nicht begonnen, die Kopie ist überflüssig.
            finish()
            return Outcome.NONE
        }
        if (token == committedToken) return Outcome.FINISHED
        // Ohne Kopie (darf nicht vorkommen, sie wird vor der Marke geschrieben) die Nächte nicht leeren.
        if (withContext(Dispatchers.IO) { copyFile.exists() }) nights.replaceAll(copy.load())
        finish()
        return Outcome.ROLLED_BACK
    }
}
