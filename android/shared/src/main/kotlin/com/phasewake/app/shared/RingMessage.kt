package com.phasewake.app.shared

/**
 * Inhalt der Nachrichten [DataLayer.PATH_RING] und [DataLayer.PATH_STOP]: «kennung|zielzeitMs|sendezeitMs|schlummerÜbrig».
 * Ältere Versionen schicken nur die Kennung (oder nichts); fehlende Felder sind dann `null`.
 *
 * @property target Zielzeit der laufenden Nacht (für das Aussetzen des Zweitweckers, nicht «jetzt»).
 * @property sentAt Sendezeit; ein verspätet zugestelltes «klingeln» wird verworfen.
 * @property snoozesLeft verbleibende Schlummer-Runden auf der Uhr (nur bei «klingeln»).
 */
data class RingMessage(
    val alarmId: String?,
    val target: Long? = null,
    val sentAt: Long? = null,
    val snoozesLeft: Int? = null,
) {
    fun encode(): ByteArray = listOf(alarmId.orEmpty(), target?.toString().orEmpty(), sentAt?.toString().orEmpty(), snoozesLeft?.toString().orEmpty())
        .joinToString("|").encodeToByteArray()

    /** Ob die Nachricht zu spät ankam (älter als [MAX_AGE_MS]); ohne Sendezeit (ältere Version) nie. */
    fun isStale(nowMs: Long): Boolean = sentAt != null && nowMs - sentAt > MAX_AGE_MS

    companion object {
        /** Höchstalter eines «klingeln», danach gilt es als veraltet. */
        const val MAX_AGE_MS = 2 * 60_000L

        fun decode(data: ByteArray?): RingMessage {
            val parts = data?.decodeToString()?.split('|').orEmpty()
            return RingMessage(
                alarmId = parts.getOrNull(0)?.takeIf { it.isNotEmpty() },
                target = parts.getOrNull(1)?.toLongOrNull(),
                sentAt = parts.getOrNull(2)?.toLongOrNull(),
                snoozesLeft = parts.getOrNull(3)?.toIntOrNull(),
            )
        }
    }
}
