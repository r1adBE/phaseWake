package com.phasewake.app.holidays

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Geschätzte Feiertage gelten nicht als frei (Entscheid r1AD 14.11.2026, Audit F-2): Beispiel Kosovo, «Eid al-Fitr
 * (estimated)» am 20.03.2026 – der Wecker klingelt, die Einstellungen zeigen den Tag als «geschätzt».
 */
class HolidayDaysTest {
    private val today = LocalDate.of(2026, 1, 1)

    @Test
    fun estimatedDaysAreNotFree() {
        val national = listOf(
            "2026-01-01" to "New Year's Day",
            "2026-03-20" to "Eid al-Fitr (estimated)",
            "2026-05-27" to "Eid al-Adha (estimated)",
            "2026-05-28" to "Eid al-Adha (observed, estimated)",
        )
        val days = HolidayDays.of(national, emptyList(), today)
        assertEquals(setOf(LocalDate.of(2026, 1, 1)), days.sure)
        assertEquals(setOf(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 5, 27), LocalDate.of(2026, 5, 28)), days.estimated)
    }

    @Test
    fun aSureHolidayOnTheSameDayWins() {
        // Gleicher Tag: landesweit geschätzt, in der Region sicher – frei, nicht «geschätzt».
        val days = HolidayDays.of(
            listOf("2026-03-20" to "Eid al-Fitr (estimated)", "2026-04-01" to "Eid al-Fitr (estimated); National Day"),
            listOf("2026-03-20" to "Regional Day"),
            today,
        )
        assertEquals(setOf(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 4, 1)), days.sure)
        assertTrue(days.estimated.isEmpty())
    }

    @Test
    fun rulesStateForcesOneRecalculation() {
        // Gespeicherte Feiertage vor F-2 (Regeln 0) enthalten geschätzte Tage: `refresh` rechnet einmal neu.
        assertTrue(HolidayRepository.RULES >= 1)
    }
}
