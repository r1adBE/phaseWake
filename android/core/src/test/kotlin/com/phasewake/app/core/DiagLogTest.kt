package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Diagnose-Protokoll, Bericht der Uhr und Freischalten; Gegenstück: `DiagLogTests` (Swift). */
class DiagLogTest {
    private val now = Instant.parse("2026-10-29T06:00:00Z")

    @Test
    fun encodeAndDecode() {
        val entry = DiagEntry(now, DiagDevice.WATCH, DiagKind.RING, "light | sleep\nnow")
        val line = DiagLog.encode(entry)
        assertEquals("${now.toEpochMilli()}|W|RING|light   sleep now", line)
        assertEquals(entry.copy(detail = "light   sleep now"), DiagLog.decode(line))
        assertEquals(DiagKind.OTHER, DiagLog.decode("1|P|NEU|x")?.kind)
        assertEquals("", DiagLog.decode("1|P|BOOT")?.detail)
        assertEquals("a|b", DiagLog.decode("1|P|BOOT|a|b")?.detail)
        assertNull(DiagLog.decode("x|P|BOOT|"))
        assertNull(DiagLog.decode("1|Q|BOOT|"))
        assertNull(DiagLog.decode("1|P"))
        assertEquals(DiagLog.MAX_DETAIL, DiagLog.clean("x".repeat(500)).length)
    }

    @Test
    fun trimKeepsNewestWithinSevenDays() {
        val old = DiagEntry(now.minus(DiagLog.MAX_AGE).minusSeconds(1), DiagDevice.PHONE, DiagKind.BOOT, "")
        val edge = DiagEntry(now.minus(DiagLog.MAX_AGE), DiagDevice.PHONE, DiagKind.BOOT, "edge")
        assertEquals(listOf(edge), DiagLog.trim(listOf(old, edge), now))
        val many = (0 until DiagLog.MAX_ENTRIES + 20).map { DiagEntry(now.minusSeconds(it.toLong()), DiagDevice.PHONE, DiagKind.SYNC, "$it") }
        val kept = DiagLog.trim(many, now)
        assertEquals(DiagLog.MAX_ENTRIES, kept.size)
        assertEquals("0", kept.last().detail)
        assertTrue(kept.zipWithNext().all { (a, b) -> !a.at.isAfter(b.at) })
    }

    @Test
    fun formatForSharing() {
        val zone = ZoneId.of("Europe/Zurich")
        val text = DiagLog.format(
            listOf(
                DiagEntry(now.plus(Duration.ofMinutes(1)), DiagDevice.PHONE, DiagKind.STOP, ""),
                DiagEntry(now, DiagDevice.WATCH, DiagKind.RING, "light"),
            ),
            zone,
        )
        assertEquals("2026-10-29 07:00:00 watch RING light\n2026-10-29 07:01:00 phone STOP", text)
    }

    @Test
    fun reportRoundTrip() {
        val report = DiagReport(
            listOf("version" to "1.0", "next=x" to "a\nb", "battery" to "55"),
            listOf(DiagEntry(now, DiagDevice.WATCH, DiagKind.SCHEDULED, "07:00")),
        )
        val text = DiagReports.encode(report)
        assertTrue(text.startsWith("phasewake-diag 1\nversion=1.0\nnextx=a b\nbattery=55\n--\n"))
        val back = DiagReports.decode(text)
        assertEquals(listOf("version" to "1.0", "nextx" to "a b", "battery" to "55"), back?.status)
        assertEquals(report.entries, back?.entries)
        assertNull(DiagReports.decode(null))
        assertNull(DiagReports.decode("anders"))
        // Ohne Trennlinie: nur Zustand.
        assertEquals(DiagReport(listOf("a" to "1"), emptyList()), DiagReports.decode("phasewake-diag 1\na=1\nkaputt"))
    }

    @Test
    fun unlockAfterSevenTaps() {
        assertNull(DiagUnlock.remaining(3))
        assertEquals(3, DiagUnlock.remaining(4))
        assertEquals(1, DiagUnlock.remaining(6))
        assertNull(DiagUnlock.remaining(7))
        assertFalse(DiagUnlock.unlocked(6))
        assertTrue(DiagUnlock.unlocked(7))
    }
}
