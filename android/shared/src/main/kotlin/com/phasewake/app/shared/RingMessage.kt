package com.phasewake.app.shared

/**
 * Inhalt der Nachrichten [DataLayer.PATH_RING], [DataLayer.PATH_STOP], [DataLayer.PATH_TASK_SHOWN] und [DataLayer.PATH_TASK_GONE]:
 * «kennung|zielzeitMs|sendezeitMs|schlummerÜbrig|schlummerBisMs». Jede Meldung trägt die Sendezeit; eine ohne
 * ([isCurrent]) stammt von einer veralteten App und wird verworfen (kein Rückfall für ältere Versionen, 10.11.2026). Die
 * übrigen Felder fehlen, wo sie nicht passen (`null`), z. B. die Zielzeit nach dem Schlummern am Handy.
 *
 * @property target Zielzeit der laufenden Nacht (für das Aussetzen des Zweitweckers, nicht «jetzt»).
 * @property sentAt Sendezeit; ein verspätet zugestelltes «klingeln» wird verworfen.
 * @property snoozesLeft verbleibende Schlummer-Runden auf der Uhr (nur bei «klingeln»).
 * @property snoozeUntil nur bei «stop» beim Schlummern: Ende des Schlummerns. Das Handy stellt dann eine
 *   eigene Sicherung kurz danach, falls die Uhr nicht wieder klingelt; ohne das Feld ist die Nacht beendet.
 */
data class RingMessage(
    val alarmId: String?,
    val target: Long? = null,
    val sentAt: Long? = null,
    val snoozesLeft: Int? = null,
    val snoozeUntil: Long? = null,
) {
    fun encode(): ByteArray = listOf(
        alarmId.orEmpty(), target?.toString().orEmpty(), sentAt?.toString().orEmpty(),
        snoozesLeft?.toString().orEmpty(), snoozeUntil?.toString().orEmpty(),
    ).joinToString("|").encodeToByteArray()

    /** Ob die Meldung von einer aktuellen App stammt: mit Sendezeit. Sonst verwirft sie der Empfänger. */
    fun isCurrent(): Boolean = sentAt != null

    /** Ob ein «klingeln» der Uhr vollständig ist: aktuell ([isCurrent]) und mit Kennung und Schlummer-Runden. */
    fun isCompleteRing(): Boolean = isCurrent() && alarmId != null && snoozesLeft != null

    /** Ob die Nachricht zu spät ankam (älter als [MAX_AGE_MS]); ohne Sendezeit nie (solche verwirft [isCurrent]). */
    fun isStale(nowMs: Long): Boolean = sentAt != null && nowMs - sentAt > MAX_AGE_MS

    /**
     * Zeitpunkt der Handy-Sicherung nach einem «stop» beim Schlummern: [SNOOZE_SAFETY_MS] nach dem
     * Schlummer-Ende. `null` bei einem endgültigen «stop» oder wenn der Zeitpunkt schon vorbei ist
     * (verspätet zugestellt: die Uhr hat inzwischen wieder geklingelt).
     */
    fun snoozeSafetyAt(nowMs: Long): Long? = snoozeUntil?.plus(SNOOZE_SAFETY_MS)?.takeIf { it > nowMs }

    /**
     * Ob die Meldung zur Nacht mit [otherAlarmId] und [otherTarget] gehört. Fehlende Angaben (Sicherung ohne Zielzeit,
     * Zielzeit nach dem Schlummern am Handy) widersprechen nicht.
     */
    fun sameNight(otherAlarmId: String?, otherTarget: Long?): Boolean =
        (alarmId == null || otherAlarmId == null || alarmId == otherAlarmId) &&
            (target == null || otherTarget == null || target == otherTarget)

    companion object {
        /** Höchstalter eines «klingeln», danach gilt es als veraltet. */
        const val MAX_AGE_MS = 2 * 60_000L

        /** So lange nach dem Schlummer-Ende wartet das Handy auf ein neues «klingeln» der Uhr (wie auf dem iPhone). */
        const val SNOOZE_SAFETY_MS = 90_000L

        fun decode(data: ByteArray?): RingMessage {
            val parts = data?.decodeToString()?.split('|').orEmpty()
            return RingMessage(
                alarmId = parts.getOrNull(0)?.takeIf { it.isNotEmpty() },
                target = parts.getOrNull(1)?.toLongOrNull(),
                sentAt = parts.getOrNull(2)?.toLongOrNull(),
                snoozesLeft = parts.getOrNull(3)?.toIntOrNull(),
                snoozeUntil = parts.getOrNull(4)?.toLongOrNull(),
            )
        }
    }
}
