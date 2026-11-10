package com.phasewake.app.core

import java.text.Normalizer
import java.util.Locale

/**
 * Regel für das Passwort einer neuen Sicherung (Entscheid r1AD 10.11.2026): mindestens [MIN_LENGTH] Zeichen
 * (Unicode-Codepunkte nach NFC, nicht Graphem-Gruppen) und kein triviales Muster. Gilt nur beim Anlegen
 * ([BackupFile.encrypt]); ältere Sicherungen mit kürzerem Passwort lassen sich weiter öffnen ([BackupFile.decrypt] prüft
 * nichts davon). Swift (`BackupPassword.swift`) gleich, mit denselben Tests.
 */
object BackupPassword {
    /** Kürzestes erlaubtes Passwort in Zeichen. */
    const val MIN_LENGTH: Int = 10

    /** Warum ein Passwort für eine neue Sicherung nicht genügt. */
    enum class Problem {
        /** Kürzer als [MIN_LENGTH]. */
        TOO_SHORT,

        /**
         * Leicht zu erraten: ein wiederholter Block (1–5 Zeichen), eine einfache Folge, eine Tastaturreihe oder ein
         * häufiges Passwort.
         */
        TRIVIAL,
    }

    /**
     * Buchstabenreihen der Tastatur (QWERTY, QWERTZ, AZERTY) am Stück, Reihe an Reihe; ein Passwort, das ein Ausschnitt
     * davon ist (auch rückwärts), gilt als trivial. Die Ziffernreihe deckt [isSequence] ab.
     */
    private val KEYBOARD = listOf(
        "qwertyuiopasdfghjklzxcvbnm",
        "qwertzuiopasdfghjklyxcvbnm",
        "azertyuiopqsdfghjklmwxcvbn",
    )

    /** Häufige Passwörter ab [MIN_LENGTH] Zeichen, kleingeschrieben (kürzere scheitern schon an der Länge). */
    private val COMMON = setOf(
        "password12", "password123", "password1234", "password12345",
        "passwort12", "passwort123", "passwort1234", "passwort12345",
        "qwerty1234", "qwerty12345", "qwerty123456", "qwertz1234", "qwertz12345", "azerty1234", "azerty12345",
        "1q2w3e4r5t", "1q2w3e4r5t6y", "q1w2e3r4t5", "q1w2e3r4t5y6", "1qaz2wsx3edc", "zaq12wsxcde3", "123qweasdzxc",
        "123456789a", "a123456789", "1234567890a", "123456789q", "1234567890q", "abc1234567", "abcd123456",
        "1234512345", "1234554321", "1234567891", "1122334455", "1231231231", "123123123123",
        "iloveyou12", "iloveyou123", "admin12345", "admin123456", "welcome123", "letmein123",
        "football12", "football123", "sunshine12", "princess12",
    )

    /** Problem von [password] für eine neue Sicherung; `null`, wenn es genügt. */
    fun problem(password: String): Problem? {
        val nfc = Normalizer.normalize(password, Normalizer.Form.NFC)
        if (nfc.codePointCount(0, nfc.length) < MIN_LENGTH) return Problem.TOO_SHORT
        return if (isTrivial(nfc.lowercase(Locale.ROOT))) Problem.TRIVIAL else null
    }

    /** Ob [password] für eine neue Sicherung genügt ([problem] ist `null`). */
    fun ok(password: String): Boolean = problem(password) == null

    private fun isTrivial(lower: String): Boolean {
        val points = lower.codePoints().toArray()
        if (isRepeatedBlock(points)) return true
        if (isSequence(points, 1) || isSequence(points, -1)) return true
        if (KEYBOARD.any { row -> lower in row || lower in row.reversed() }) return true
        return lower in COMMON
    }

    /**
     * Ob das Passwort ein Block aus 1 bis [MAX_BLOCK] Zeichen ist, wiederholt bis zum Ende (der letzte darf angeschnitten
     * sein): «aaaaaaaaaa», «1212121212», «abcabcabca».
     */
    private fun isRepeatedBlock(points: IntArray): Boolean =
        (1..MAX_BLOCK).any { block -> block < points.size && (block until points.size).all { points[it] == points[it - block] } }

    /** Längster Block, dessen Wiederholung als trivial gilt. */
    private const val MAX_BLOCK = 5

    /** Ob jeder Codepunkt um [step] auf den vorigen folgt (1234…, abcd…, dcba…); Ziffern laufen im Kreis (…8, 9, 0, 1…). */
    private fun isSequence(points: IntArray, step: Int): Boolean =
        (1 until points.size).all { next(points[it - 1], step) == points[it] }

    private fun next(point: Int, step: Int): Int = when {
        step > 0 && point == '9'.code -> '0'.code
        step < 0 && point == '0'.code -> '9'.code
        else -> point + step
    }
}
