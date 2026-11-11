package com.phasewake.app.shared

import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.TimeBounds
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Abgleich der Wecker je Wecker (Audit A-4, Entscheid «per-alarm updatedAt merge»): Früher gewann die neuere ganze Liste;
 * änderte ein Gerät automatisch einen Wecker (Einmal-Wecker nach dem Klingeln aus, Zweitwecker am Handy), während auf dem
 * anderen der Nutzer einen Wecker bearbeitete, ging eine der beiden Änderungen verloren. Jetzt trägt jeder Wecker den
 * Zeitpunkt seiner letzten Änderung, und ein gelöschter Wecker bleibt als Eintrag mit dem Zeitpunkt des Löschens
 * («Grabstein»), bis er [TOMBSTONE_MAX_AGE_MS] alt ist.
 */
object AlarmStamps {
    /** So lange bleibt ein gelöschter Wecker gemerkt (ein Gerät, das länger getrennt war, brächte ihn sonst zurück). */
    const val TOMBSTONE_MAX_AGE_MS = 90 * 24 * 3_600_000L

    /** Höchstens so viele gelöschte Wecker bleiben gemerkt, die neuesten. */
    const val MAX_TOMBSTONES = 200

    /**
     * Führt [local] und [remote] zusammen; das Ergebnis hängt nicht davon ab, welche Seite lokal ist (beide Geräte kommen
     * zum selben Stand). Je Wecker gilt die Fassung mit dem neueren Zeitpunkt ([AlarmSnapshot.stampOf]); bei gleichem
     * Zeitpunkt und verschiedenem Inhalt entscheidet ein fester Vergleich. Ein Löschen gewinnt, wenn es nicht älter als
     * diese Fassung ist; ein Wecker, den eine Seite nie kannte und nirgends gelöscht ist, bleibt. Listen-Zeitpunkt: der
     * spätere beider Seiten. Gelöschte Wecker älter als [TOMBSTONE_MAX_AGE_MS] vor [now] fallen weg.
     */
    fun merge(local: AlarmSnapshot, remote: AlarmSnapshot, now: Long): AlarmSnapshot {
        val mine = local.alarms.associateBy { it.id }
        val theirs = remote.alarms.associateBy { it.id }
        val ids = (mine.keys + theirs.keys + local.deleted.keys + remote.deleted.keys).sorted()
        val alarms = mutableListOf<AlarmSpec>()
        val stamps = mutableMapOf<String, Long>()
        val deleted = mutableMapOf<String, Long>()
        for (id in ids) {
            val version = newer(mine[id]?.let { it to local.stampOf(id) }, theirs[id]?.let { it to remote.stampOf(id) })
            val gone = listOfNotNull(local.deleted[id], remote.deleted[id]).maxOrNull()
            if (version != null && (gone == null || version.second > gone)) {
                alarms += version.first
                stamps[id] = version.second
            } else if (gone != null) {
                deleted[id] = gone
            }
        }
        return AlarmSnapshot(alarms, maxOf(local.updatedAt, remote.updatedAt), stamps, prune(deleted, now))
    }

    /** Die neuere von zwei Fassungen (Wecker, Zeitpunkt); bei Gleichstand fest nach Inhalt. */
    private fun newer(a: Pair<AlarmSpec, Long>?, b: Pair<AlarmSpec, Long>?): Pair<AlarmSpec, Long>? = when {
        a == null -> b
        b == null -> a
        a.second != b.second -> if (a.second > b.second) a else b
        else -> if (a.first.toString() >= b.first.toString()) a else b
    }

    /**
     * Gelöschte Wecker ohne zu alte ([TOMBSTONE_MAX_AGE_MS] vor [now]) und ohne unplausible Zeitpunkte ([TimeBounds],
     * sonst liefe `now − Zeitpunkt` über, Fix KN-4), höchstens [MAX_TOMBSTONES], die neuesten.
     */
    fun prune(deleted: Map<String, Long>, now: Long): Map<String, Long> =
        deleted.filterValues { TimeBounds.contains(it) && now - it <= TOMBSTONE_MAX_AGE_MS }
            .entries.sortedByDescending { it.value }.take(MAX_TOMBSTONES)
            .associate { it.key to it.value }

    /**
     * Stand der Gegenseite nach [TimeBounds] geprüft (Fix KN-4, wie `AlarmStamps.snapshot(context:)` in Swift):
     * unplausibler Listen-Zeitpunkt → `null` (Nachricht verwerfen); unplausible Zeitpunkte je Wecker oder gelöschter
     * Wecker fallen weg (der Wecker gilt dann mit dem Listen-Zeitpunkt, das Löschen ist vergessen). Für jeden Leser
     * empfangener Stände (`QueueMessages.decodeAlarms`).
     */
    fun checked(snapshot: AlarmSnapshot): AlarmSnapshot? {
        if (!TimeBounds.contains(snapshot.updatedAt)) return null
        return snapshot.copy(
            stamps = snapshot.stamps.filterValues(TimeBounds::contains),
            deleted = snapshot.deleted.filterValues(TimeBounds::contains),
        )
    }

    /** Kennung für Text mit «=» und Zeilen (URL-kodiert). */
    fun escape(id: String): String = URLEncoder.encode(id, "UTF-8")

    /** Gegenstück zu [escape]; `null`, wenn unlesbar. */
    fun unescape(text: String): String? = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrNull()

    /** Speicherformat: je Zeile «kennung=ms» (Kennung URL-kodiert). */
    fun encodeMap(map: Map<String, Long>): String = map.entries.joinToString("\n") { "${escape(it.key)}=${it.value}" }

    /** Liest [encodeMap]; unlesbare Zeilen und unplausible Zeitpunkte ([TimeBounds], Fix KN-4) fallen weg. */
    fun decodeMap(text: String?): Map<String, Long> = text.orEmpty().lines().mapNotNull { line ->
        val eq = line.lastIndexOf('=')
        if (eq <= 0) return@mapNotNull null
        val at = TimeBounds.checked(line.substring(eq + 1).toLongOrNull()) ?: return@mapNotNull null
        (unescape(line.substring(0, eq)) ?: return@mapNotNull null) to at
    }.toMap()
}
