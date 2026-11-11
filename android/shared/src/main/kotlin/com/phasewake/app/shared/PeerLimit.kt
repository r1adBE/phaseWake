package com.phasewake.app.shared

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Obergrenze für Inhalte der Gegenseite (Audit S-2): Ein Kanal ([WearSync.readChannel]) wird nie ohne Grenze in den
 * Speicher gelesen, und eine Nachricht der Warteschlange über der Grenze wird nicht verarbeitet.
 *
 * Grösse des grössten echten Inhalts, einer Aufzeichnung von 12 Std. ([NightRecording.MAX_DURATION]): 1440 Epochen à
 * 30 s, je eine Zeile «Bewegung;Puls» mit höchstens etwa 45 Zeichen (zwei Dezimalzahlen), also rund 65 KB samt Kopf.
 * [MAX_BYTES] (8 MB) lässt damit mehr als das Hundertfache Spielraum und schützt trotzdem vor einem Speicherüberlauf.
 */
object PeerLimit {
    const val MAX_BYTES: Int = 8 * 1024 * 1024

    /** Liest [input] bis zum Ende; mehr als [max] Bytes: `null` (der Rest wird nicht mehr gelesen). */
    fun read(input: InputStream, max: Int = MAX_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > max) return null
            out.write(buffer, 0, n)
        }
    }

    /** Ob [data] innerhalb der Grenze liegt (`null` zählt als leer). */
    fun fits(data: ByteArray?, max: Int = MAX_BYTES): Boolean = (data?.size ?: 0) <= max

    private const val BUFFER_BYTES = 16 * 1024
}
