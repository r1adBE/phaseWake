package com.phasewake.app.shared

import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Codec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Gemeinsame Fälle mit Swift (`shared/testdata/alarm-stamps.vectors`): [AlarmStamps.merge] je
 * Wecker – neuere Fassung, fehlender Zeitpunkt, gleicher Zeitpunkt, Löschen gegen Bearbeiten, alte und unplausible
 * gelöschte Wecker. Jede Zeile auch mit vertauschten Seiten (beide Geräte kommen zum selben Stand). Gegenstück:
 * `AlarmStampsVectorsTests` (Swift).
 */
class AlarmStampsVectorsTest {

    @Test
    fun sharedVectors() {
        val lines = AlarmStampsVectors.lines()
        for (line in lines) {
            val f = line.split('\t')
            assertEquals(4, f.size, line)
            assertEquals(f[3], AlarmStampsVectors.merged(f[0], f[1], f[2]), line)
            assertEquals(f[3], AlarmStampsVectors.merged(f[1], f[0], f[2]), "vertauscht: $line")
        }
        assertEquals(21, lines.size)
    }
}

/** Leser von `alarm-stamps.vectors` (Format im Kopf der Datei). */
internal object AlarmStampsVectors {
    /** Ordner der gemeinsamen Testdaten; relativ zum Modul wie in `android/core`. */
    private fun directory(): File = File(System.getProperty("phasewake.testdata") ?: "../../shared/testdata")

    fun lines(): List<String> =
        File(directory(), "alarm-stamps.vectors").readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }

    /** Ergebnis von [AlarmStamps.merge] der Stände [local] und [remote] zu [now] (ms), als Text wie in der Datei. */
    fun merged(local: String, remote: String, now: String): String =
        text(AlarmStamps.merge(snapshot(local), snapshot(remote), now.toLong()))

    /** «Listen-Zeitpunkt Wecker Zeitpunkte Gelöschte», mit Leerzeichen getrennt. */
    private fun snapshot(text: String): AlarmSnapshot {
        val f = text.split(' ')
        require(f.size == 4) { "Stand mit 4 Teilen erwartet: $text" }
        val alarms = if (f[1] == "-") emptyList() else f[1].split(';').map(::alarm)
        return AlarmSnapshot(alarms, f[0].toLong(), map(f[2]), map(f[3]))
    }

    private fun alarm(line: String): AlarmSpec =
        Codec.decodeAlarms("phasewake-alarms 1\n$line\n").singleOrNull() ?: error("Weckerzeile unlesbar: $line")

    private fun map(text: String): Map<String, Long> =
        if (text == "-") emptyMap() else text.split(',').associate { it.substringBefore('=') to it.substringAfter('=').toLong() }

    private fun text(s: AlarmSnapshot): String {
        val alarms = Codec.encodeAlarms(s.alarms).lines().drop(1).filter { it.isNotEmpty() }
        fun pairs(m: Map<String, Long>) = if (m.isEmpty()) "-" else m.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        return listOf(s.updatedAt.toString(), alarms.joinToString(";").ifEmpty { "-" }, pairs(s.stamps), pairs(s.deleted)).joinToString(" ")
    }
}
