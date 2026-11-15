package com.phasewake.app.ui.history

import com.phasewake.app.core.HistoryItem
import com.phasewake.app.shared.NightRecording
import java.time.Instant
import java.time.LocalDate

// Einträge der Liste im Verlauf und wie die Aufzeichnungen zwischen die Nächte kommen.

/** Eintrag im Verlauf: eine Nacht, nur Geräusche eines Nachtdatums oder eine Aufzeichnung. */
internal sealed interface HistoryEntry {
    val key: String

    // Schlüssel wie bisher: Wecker und Fensterbeginn (zwei Wecker können gleich starten).
    data class Night(val item: HistoryItem) : HistoryEntry {
        override val key: String get() = item.night?.let { "${it.alarmId}@${it.windowStart.toEpochMilli()}" } ?: item.key
    }

    /** Nacht aus Health Connect ohne Phase-Wake-Wecker; höchstens eine je Nachtdatum. */
    data class Health(val item: HistoryItem) : HistoryEntry {
        override val key: String get() = "health@${item.date}"
    }

    /** [last] = Beginn des jüngsten Ausschnitts. */
    data class Sounds(val night: LocalDate, val last: Instant) : HistoryEntry {
        override val key: String get() = "sounds@$night"
    }

    /** Aufzeichnung der Galaxy Watch («Nacht aufzeichnen»), eingereiht nach ihrem Ende. */
    data class Recording(val recording: NightRecording) : HistoryEntry {
        override val key: String get() = "recording@${recording.start.toEpochMilli()}"
    }
}

/** Zeitpunkt eines Eintrags fürs Einreihen der Aufzeichnungen (Nächte: geweckt bzw. Ende des Schlafs). */
internal val HistoryEntry.at: Instant
    get() = when (this) {
        is HistoryEntry.Night -> item.at
        is HistoryEntry.Health -> item.at
        is HistoryEntry.Sounds -> last
        is HistoryEntry.Recording -> recording.end ?: recording.start
    }

/** Reiht [recordings] (älteste zuerst) nach ihrem Ende in [entries] (neueste zuerst) ein. */
internal fun withRecordings(entries: List<HistoryEntry>, recordings: List<NightRecording>): List<HistoryEntry> {
    if (recordings.isEmpty()) return entries
    val pending = ArrayDeque(recordings.sortedByDescending { it.end ?: it.start }.map { HistoryEntry.Recording(it) })
    return buildList {
        for (entry in entries) {
            while (pending.isNotEmpty() && pending.first().at.isAfter(entry.at)) add(pending.removeFirst())
            add(entry)
        }
        addAll(pending)
    }
}
