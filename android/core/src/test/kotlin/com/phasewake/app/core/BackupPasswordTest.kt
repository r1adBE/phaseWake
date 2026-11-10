package com.phasewake.app.core

import com.phasewake.app.core.BackupPassword.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regel für das Passwort einer neuen Sicherung. Gegenstück: `BackupPasswordTests` (Swift), gleiche Fälle. */
class BackupPasswordTest {

    @Test
    fun tooShort() {
        assertEquals(10, BackupPassword.MIN_LENGTH)
        assertEquals(Problem.TOO_SHORT, BackupPassword.problem(""))
        assertEquals(Problem.TOO_SHORT, BackupPassword.problem("Mondlicht"))
        assertEquals(Problem.TOO_SHORT, BackupPassword.problem("123456789"))
        assertNull(BackupPassword.problem("Mond-Licht"))
        // «ä» als NFD (zwei Codepunkte) zählt als ein Zeichen: «abcdefghä» hat 9, «abcdefghiä» 10.
        assertEquals(Problem.TOO_SHORT, BackupPassword.problem("abcdefghä"))
        assertNull(BackupPassword.problem("abcdefghiä"))
    }

    @Test
    fun sameCharacter() {
        assertEquals(Problem.TRIVIAL, BackupPassword.problem("aaaaaaaaaa"))
        assertEquals(Problem.TRIVIAL, BackupPassword.problem("AAAAAAAaaaaa"))
        assertEquals(Problem.TRIVIAL, BackupPassword.problem("0000000000"))
        assertEquals(Problem.TRIVIAL, BackupPassword.problem("ä".repeat(10)))
    }

    @Test
    fun repeatedBlocks() {
        for (p in listOf("1212121212", "abcabcabca", "ABCabcabcABC", "abcdabcdab", "12345123451", "xY!xY!xY!x")) {
            assertEquals(Problem.TRIVIAL, BackupPassword.problem(p), p)
        }
        // Block aus 6 Zeichen: nicht mehr trivial.
        assertNull(BackupPassword.problem("abcdeXabcdeX"))
        assertNull(BackupPassword.problem("1212121213"))
    }

    @Test
    fun sequences() {
        for (p in listOf("1234567890", "0123456789", "7890123456", "9876543210", "0987654321", "abcdefghij", "ABCDEFGHIJK", "jihgfedcba", "klmnopqrstuvwxyz")) {
            assertEquals(Problem.TRIVIAL, BackupPassword.problem(p), p)
        }
        assertNull(BackupPassword.problem("1234567899"))
        assertNull(BackupPassword.problem("abcdefghik"))
    }

    @Test
    fun keyboardRows() {
        for (p in listOf("qwertyuiop", "QWERTZUIOP", "azertyuiop", "poiuytrewq", "qwertyuiopasdf", "asdfghjklzx", "mnbvcxzlkj")) {
            assertEquals(Problem.TRIVIAL, BackupPassword.problem(p), p)
        }
        assertNull(BackupPassword.problem("qwertyuiopx"))
        assertNull(BackupPassword.problem("yxcvbnmasd"))
    }

    @Test
    fun commonPasswords() {
        for (p in listOf("password123", "Password123", "PASSWORT1234", "1q2w3e4r5t", "iloveyou12", "1qaz2wsx3edc")) {
            assertEquals(Problem.TRIVIAL, BackupPassword.problem(p), p)
        }
    }

    @Test
    fun goodPasswords() {
        for (p in listOf("Mondlicht-2026", "Mondlicht-ä€-2026", "correct horse", "Velo fährt 7x", "Мой пароль 42")) {
            assertNull(BackupPassword.problem(p), p)
            assertTrue(BackupPassword.ok(p), p)
        }
    }
}
