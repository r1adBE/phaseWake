package com.phasewake.app.core

/**
 * JSON der Sicherung, bewusst ohne Bibliothek (wie [Codec]): ein kleines, vollständig getestetes Lesen und
 * Schreiben für Objekte, Listen, Texte, ganze Zahlen, Wahrheitswerte und `null`. Unbekannte Schlüssel
 * werden ignoriert, fehlende ergeben die Vorgaben – so lesen ältere und neuere Versionen einander.
 */
object BackupJson {
    /** Wert von «format». */
    const val FORMAT: String = "phasewake-backup"

    /** Wert von «version»; neuere Versionen werden gelesen, soweit die Schlüssel bekannt sind. */
    const val VERSION: Int = 1

    /** Schreibt [payload] als JSON-Objekt. */
    fun write(payload: BackupPayload): String {
        val s = payload.settings
        val settings = linkedMapOf<String, Any?>(
            "sleepGoal" to s.sleepGoal, "bedtimeReminder" to s.bedtimeReminder, "weeklyReview" to s.weeklyReview,
            "soundRecDefault" to s.soundRecDefault, "theme" to s.theme, "holidayCountry" to s.holidayCountry,
            "holidayRegion" to s.holidayRegion, "historyClearedAt" to s.historyClearedAt,
        )
        val root = linkedMapOf<String, Any?>(
            "format" to FORMAT, "version" to VERSION, "createdAt" to payload.createdAt, "alarms" to payload.alarms,
            "nights" to payload.nights, "notes" to payload.notes, "customNotes" to payload.customNotes,
            "routineItems" to payload.routineItems, "routineTicks" to payload.routineTicks, "settings" to settings,
        )
        return buildString { value(root) }
    }

    /** Liest [write]. Kein Objekt, falsches «format» oder kaputtes JSON: [CodecException]. */
    fun read(text: String): BackupPayload {
        val root = JsonReader(text).document() as? Map<*, *> ?: throw CodecException("Kein JSON-Objekt")
        if (root["format"] != FORMAT) throw CodecException("Keine Phase-Wake-Sicherung")
        val s = root["settings"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val d = BackupSettings()
        return BackupPayload(
            createdAt = (root["createdAt"] as? Long) ?: 0L,
            alarms = root["alarms"] as? String ?: throw CodecException("Wecker fehlen"),
            nights = (root["nights"] as? List<*>).orEmpty().filterIsInstance<String>(),
            notes = root["notes"] as? String ?: "",
            customNotes = root["customNotes"] as? String ?: "",
            routineItems = root["routineItems"] as? String,
            routineTicks = root["routineTicks"] as? String ?: "",
            settings = BackupSettings(
                sleepGoal = (s["sleepGoal"] as? Long)?.toInt() ?: d.sleepGoal,
                bedtimeReminder = s["bedtimeReminder"] as? Boolean ?: d.bedtimeReminder,
                weeklyReview = s["weeklyReview"] as? Boolean ?: d.weeklyReview,
                soundRecDefault = s["soundRecDefault"] as? Boolean ?: d.soundRecDefault,
                theme = s["theme"] as? String ?: d.theme,
                holidayCountry = s["holidayCountry"] as? String ?: d.holidayCountry,
                holidayRegion = s["holidayRegion"] as? String ?: d.holidayRegion,
                historyClearedAt = s["historyClearedAt"] as? Long,
            ),
        )
    }

    private fun StringBuilder.value(v: Any?) {
        when (v) {
            null -> append("null")
            is String -> string(v)
            is Boolean, is Int, is Long -> append(v.toString())
            is Map<*, *> -> {
                append('{')
                v.entries.forEachIndexed { i, (k, item) ->
                    if (i > 0) append(',')
                    string(k.toString())
                    append(':')
                    value(item)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                v.forEachIndexed { i, item ->
                    if (i > 0) append(',')
                    value(item)
                }
                append(']')
            }
            else -> throw IllegalArgumentException("Nicht als JSON schreibbar: ${v::class.simpleName}")
        }
    }

    private fun StringBuilder.string(text: String) {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

    /** Liest ein JSON-Dokument; Zahlen werden zu [Long] (Brüche zu [Double]). */
    private class JsonReader(private val text: String) {
        private var pos = 0

        fun document(): Any? {
            val v = value()
            skipSpace()
            if (pos != text.length) fail("Zeichen nach dem Ende")
            return v
        }

        private fun value(): Any? {
            skipSpace()
            if (pos >= text.length) fail("Unerwartetes Ende")
            return when (text[pos]) {
                '{' -> obj()
                '[' -> list()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val out = linkedMapOf<String, Any?>()
            skipSpace()
            if (peek() == '}') return out.also { pos++ }
            while (true) {
                skipSpace()
                if (peek() != '"') fail("Schlüssel erwartet")
                val key = string()
                skipSpace()
                expect(':')
                out[key] = value()
                skipSpace()
                if (peek() == ',') pos++ else return out.also { expect('}') }
            }
        }

        private fun list(): List<Any?> {
            pos++
            val out = mutableListOf<Any?>()
            skipSpace()
            if (peek() == ']') return out.also { pos++ }
            while (true) {
                out += value()
                skipSpace()
                if (peek() == ',') pos++ else return out.also { expect(']') }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("Text nicht beendet")
                val c = text[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> out.append(escape())
                    else -> out.append(c)
                }
            }
        }

        private fun escape(): Char {
            if (pos >= text.length) fail("Escape nicht beendet")
            return when (val c = text[pos++]) {
                '"', '\\', '/' -> c
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (pos + 4 > text.length) fail("\\u unvollständig")
                    text.substring(pos, pos + 4).toIntOrNull(16)?.toChar()?.also { pos += 4 } ?: fail("\\u ungültig")
                }
                else -> fail("Unbekanntes Escape \\$c")
            }
        }

        private fun number(): Any {
            val start = pos
            while (pos < text.length && text[pos] in "+-0123456789.eE") pos++
            val raw = text.substring(start, pos)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: fail("Zahl erwartet")
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!text.startsWith(word, pos)) fail("$word erwartet")
            pos += word.length
            return v
        }

        private fun skipSpace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? = text.getOrNull(pos)

        private fun expect(c: Char) {
            if (peek() != c) fail("«$c» erwartet")
            pos++
        }

        private fun fail(message: String): Nothing = throw CodecException("JSON an Stelle $pos: $message")
    }
}
