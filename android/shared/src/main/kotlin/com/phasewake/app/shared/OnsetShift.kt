package com.phasewake.app.shared

import android.net.Uri
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.phasewake.app.core.WakePlan
import java.time.Duration
import java.time.Instant

/**
 * Frühere Zielzeit einer Nacht aus dem Einschlaf-Modus der Galaxy Watch (Review K-07): Wecker [alarmId], eingestellte
 * Zielzeit [original], neue Zielzeit [target] (Einschlafzeit + Schlafdauer), festgelegt um [at] (alles in ms).
 *
 * Die Uhr legt sie als Datenpaket ab ([DataLayer.PATH_ONSET_SHIFT]), nicht nur als Nachricht: So kommt sie auch an,
 * wenn das Handy gerade nicht erreichbar ist. Das Handy merkt sie sich und stellt den Zweitwecker dieser Nacht auf die
 * frühere Zielzeit ([applyTo]); nach der Nacht fällt sie weg ([expired]).
 */
data class OnsetShift(val alarmId: String, val original: Long, val target: Long, val at: Long) {

    /**
     * Plan des Zweitweckers mit der früheren Zielzeit: nur für dieselbe Nacht desselben Weckers ([plan] liegt auf
     * [original]), nur früher und nur, solange die frühere Zielzeit samt Nachfrist [grace] noch nicht vorbei ist
     * (sonst gilt die eingestellte Zeit als späte Sicherung). Sonst [plan] unverändert.
     */
    fun applyTo(plan: WakePlan, now: Instant, grace: Duration): WakePlan {
        val earlier = Instant.ofEpochMilli(target)
        if (plan.alarmId != alarmId || plan.target.toEpochMilli() != original || target >= original) return plan
        if (!earlier.plus(grace).isAfter(now)) return plan
        return plan.copy(windowStart = minOf(plan.windowStart, earlier), target = earlier)
    }

    /** Ob die Nacht vorbei ist (eingestellte Zielzeit plus [grace] erreicht): dann nicht mehr merken. */
    fun expired(now: Instant, grace: Duration): Boolean = !Instant.ofEpochMilli(original).plus(grace).isAfter(now)

    /** Für den eigenen Speicher des Handys: «kennung|eingestellt|neu|zeitpunkt» (Kennung URL-kodiert). */
    fun encode(): String = listOf(Uri.encode(alarmId), original, target, at).joinToString("|")

    companion object {
        /** Liest [encode]; `null`, wenn leer oder unlesbar. */
        fun decode(text: String?): OnsetShift? {
            val f = text?.split("|")?.takeIf { it.size >= 4 } ?: return null
            val original = f[1].toLongOrNull() ?: return null
            val target = f[2].toLongOrNull() ?: return null
            val at = f[3].toLongOrNull() ?: return null
            return OnsetShift(Uri.decode(f[0]), original, target, at)
        }

        /** Datenpaket der Uhr (dringend: das Handy soll den Zweitwecker gleich verschieben). */
        fun request(shift: OnsetShift): PutDataRequest = PutDataMapRequest.create(DataLayer.PATH_ONSET_SHIFT).apply {
            dataMap.putString(DataLayer.KEY_TEXT, shift.alarmId)
            dataMap.putLong(DataLayer.KEY_ORIGINAL_TARGET, shift.original)
            dataMap.putLong(DataLayer.KEY_TARGET, shift.target)
            dataMap.putLong(DataLayer.KEY_UPDATED_AT, shift.at)
        }.asPutDataRequest().setUrgent()

        /** Liest ein empfangenes Paket; `null`, wenn eine Angabe fehlt. */
        fun read(map: DataMap): OnsetShift? {
            val id = map.getString(DataLayer.KEY_TEXT)?.takeIf { it.isNotEmpty() } ?: return null
            if (!map.containsKey(DataLayer.KEY_ORIGINAL_TARGET) || !map.containsKey(DataLayer.KEY_TARGET)) return null
            return OnsetShift(id, map.getLong(DataLayer.KEY_ORIGINAL_TARGET), map.getLong(DataLayer.KEY_TARGET), map.getLong(DataLayer.KEY_UPDATED_AT))
        }
    }
}
