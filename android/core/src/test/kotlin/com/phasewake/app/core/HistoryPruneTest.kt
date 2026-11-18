package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** «Älter als 90 Tage löschen»: Grenze, Zeitpunkt «Verlauf löschen», Nächte und Nachtdaten. Gleich in Swift. */
class HistoryPruneTest {
    private val now = Instant.parse("2026-11-18T14:00:00Z")
    private val zone = ZoneId.of("Europe/Zurich")

    private fun night(wokeAt: Instant, id: String) = NightRecord(
        alarmId = id,
        windowStart = wokeAt.minus(Duration.ofMinutes(20)),
        target = wokeAt.plus(Duration.ofMinutes(10)),
        wokeAt = wokeAt,
        reason = WakeReason.LIGHT_SLEEP,
        snoozes = 0,
        epochs = emptyList(),
    )

    @Test
    fun cutoffIsNinetyDaysAgo() {
        assertEquals(90L, HistoryPrune.DAYS)
        assertEquals(Instant.parse("2026-08-20T14:00:00Z"), HistoryPrune.cutoff(now))
    }

    @Test
    fun clearedAtNeverMovesBack() {
        val cutoff = HistoryPrune.cutoff(now)
        assertEquals(cutoff, HistoryPrune.clearedAt(null, cutoff), "nie gelöscht: bis zur Grenze ausblenden")
        assertEquals(cutoff, HistoryPrune.clearedAt(cutoff.minusSeconds(1), cutoff))
        // Ein späteres «Verlauf löschen» bleibt, sonst kämen dort gelöschte Nächte aus Health Connect zurück.
        val later = now.minus(Duration.ofDays(3))
        assertEquals(later, HistoryPrune.clearedAt(later, cutoff))
    }

    @Test
    fun nightsEndedByTheCutoffGo() {
        val cutoff = HistoryPrune.cutoff(now)
        val nights = listOf(
            night(cutoff.minusSeconds(60), "alt"), night(cutoff, "grenze"), night(cutoff.plusMillis(1), "neu"),
        )
        // Wie «Verlauf löschen»: was bis zur Grenze endete, ist weg; Health-Nächte genauso ([HealthHistory.visible]).
        assertEquals(listOf("neu"), HistoryPrune.nights(nights, cutoff).map { it.alarmId })
        val sessions = listOf(
            HealthSession(cutoff.minusSeconds(3_600), cutoff), HealthSession(cutoff, cutoff.plusMillis(1)),
        )
        assertEquals(listOf(sessions[1]), HealthHistory.visible(sessions, HistoryPrune.clearedAt(null, cutoff)))
    }

    /** Aufzeichnungen der noch offenen Lernphase bleiben, auch wenn sie älter als 90 Tage sind. Gleich in Swift. */
    @Test
    fun recordingsOfTheOpenLearningPhaseStay() {
        val cutoff = HistoryPrune.cutoff(now)
        val since = cutoff.minus(Duration.ofDays(20))
        val old = since.minus(Duration.ofDays(1))
        val end = { start: Instant -> start.plus(Duration.ofHours(8)) }
        // Ohne offene Lernphase: was bis zur Grenze endete, ist weg; laufende und neuere bleiben.
        assertEquals(false, HistoryPrune.keepsRecording(old, end(old), cutoff, null))
        assertEquals(false, HistoryPrune.keepsRecording(since, end(since), cutoff, null))
        assertEquals(true, HistoryPrune.keepsRecording(old, null, cutoff, null))
        assertEquals(true, HistoryPrune.keepsRecording(cutoff, end(cutoff), cutoff, null))
        assertEquals(false, HistoryPrune.keepsRecording(cutoff.minusSeconds(8 * 3_600), cutoff, cutoff, null))
        // Offene Lernphase ab [since]: ihre Aufzeichnungen (Beginn ab [since], wie Learning.counted) bleiben.
        assertEquals(true, HistoryPrune.keepsRecording(since, end(since), cutoff, since))
        assertEquals(false, HistoryPrune.keepsRecording(old, end(old), cutoff, since))
    }

    @Test
    fun datesFromTheCutoffNightStay() {
        val cutoff = HistoryPrune.cutoff(now)
        // 20.08.2026 16:00 Zürich: Nachtdatum 20.08. (Abend); der 19.08. ist älter.
        val notes = mapOf(
            LocalDate.of(2026, 8, 19) to listOf(SleepNotes.ALCOHOL),
            LocalDate.of(2026, 8, 20) to listOf(SleepNotes.NAP),
            LocalDate.of(2026, 11, 17) to listOf(SleepNotes.SICK),
        )
        assertEquals(
            setOf(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 11, 17)), HistoryPrune.dates(notes, cutoff, zone).keys,
        )
        assertEquals(emptyMap(), HistoryPrune.dates(emptyMap<LocalDate, Int>(), cutoff, zone))
    }
}
